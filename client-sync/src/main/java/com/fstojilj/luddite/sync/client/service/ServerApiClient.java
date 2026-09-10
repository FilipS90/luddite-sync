package com.fstojilj.luddite.sync.client.service;

import com.fstojilj.luddite.sync.common.dto.AuthRequest;
import com.fstojilj.luddite.sync.common.dto.DirListResponse;
import com.fstojilj.luddite.sync.common.dto.ServerPortResponse;
import com.fstojilj.luddite.sync.common.dto.TreeResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;

/**
 * Thin HTTP client for the server's directory REST API.
 * All methods swallow exceptions and return safe empty/false defaults so callers
 * never need to handle network errors directly.
 *
 * <p>The host is not known at construction time — it comes from the client's DB
 * (see {@code HostSettingsInitializer}), the single source of truth for it. The
 * {@code restClient} built here has no base URL and is never used before
 * {@link #switchHost} is called during startup.
 */
@Service
@Slf4j
public class ServerApiClient {

    /**
     * Bounds every REST call. Without these, a request to an unreachable host blocks on
     * the OS-default TCP connect timeout (~45 s), so calls issued just before a host
     * switch keep failing against the *old* host long after the switch — and the UI's
     * 2 s refresh stacks up worker threads behind them.
     */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    private volatile RestClient restClient;
    private final int apiPort;

    @Autowired
    public ServerApiClient(@Value("${sync.server.api-port:8080}") int apiPort) {
        this.apiPort = apiPort;
        this.restClient = buildClient(null);
    }

    /** Package-private constructor for testing with a pre-built RestClient. */
    ServerApiClient(RestClient restClient) {
        this.restClient = restClient;
        this.apiPort = -1;
    }

    /**
     * Rebuilds the REST client against a different host, keeping the same API port.
     * Used when the user switches servers at runtime from the UI.
     *
     * @param newHost the new server host
     */
    public synchronized void switchHost(String newHost) {
        this.restClient = buildClient("http://" + newHost + ":" + apiPort);
    }

    /**
     * Builds a timeout-bounded client. {@code baseUrl} is {@code null} only at
     * construction time, before the host is known — see the class javadoc.
     */
    private static RestClient buildClient(String baseUrl) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
        factory.setReadTimeout(READ_TIMEOUT);
        RestClient.Builder builder = RestClient.builder().requestFactory(factory);
        if (baseUrl != null) builder.baseUrl(baseUrl);
        return builder.build();
    }

    /**
     * Fetches the port the server's file-transfer socket is currently listening on.
     * Falls back to {@code fallback} on any error (e.g. an older server without this
     * endpoint, or a network failure) so callers never need to handle it directly.
     *
     * @param fallback port to return if the request fails
     * @return the server's current socket port, or {@code fallback}
     */
    public int fetchSocketPort(int fallback) {
        try {
            ServerPortResponse response = restClient.get()
                    .uri("/api/server/socket-port")
                    .retrieve()
                    .body(ServerPortResponse.class);
            return response != null ? response.port() : fallback;
        } catch (Exception e) {
            log.warn("fetchSocketPort failed: {}", e.getMessage());
            return fallback;
        }
    }

    /**
     * Returns all public (non-private) root directory names advertised by the server.
     */
    public List<String> fetchPublicDirs() {
        try {
            DirListResponse response = restClient.get()
                    .uri("/api/dirs")
                    .retrieve()
                    .body(DirListResponse.class);
            return response != null && response.dirs() != null ? response.dirs() : List.of();
        } catch (Exception e) {
            log.warn("fetchPublicDirs failed: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Returns the immediate child directories and files under {@code under} for the given root dir.
     *
     * @param dirName      root directory name
     * @param under        relative subpath to expand; {@code ""} for root level
     * @param passwordHash SHA-256 password hash for private dirs; {@code null} for public dirs
     */
    public TreeResponse fetchTree(String dirName, String under, String passwordHash) {
        try {
            TreeResponse response = restClient.get()
                    .uri(uriBuilder -> {
                        var builder = uriBuilder.path("/api/dirs/{name}/tree");
                        if (under != null && !under.isBlank()) {
                            builder.queryParam("under", under);
                        }
                        return builder.build(dirName);
                    })
                    .headers(headers -> {
                        if (passwordHash != null) {
                            headers.set("X-Auth-Hash", passwordHash);
                        }
                    })
                    .retrieve()
                    .body(TreeResponse.class);
            if (response == null) return new TreeResponse(List.of(), List.of());
            return new TreeResponse(
                    response.childNames() != null ? response.childNames() : List.of(),
                    response.fileNames() != null ? response.fileNames() : List.of());
        } catch (HttpClientErrorException.Forbidden e) {
            log.warn("fetchTree: access denied for private dir '{}'", dirName);
            return new TreeResponse(List.of(), List.of());
        } catch (Exception e) {
            log.warn("fetchTree failed for dir='{}' under='{}': {}", dirName, under, e.getMessage());
            return new TreeResponse(List.of(), List.of());
        }
    }

    /**
     * Authenticates against a private directory.
     *
     * @param clientId     the stable client identifier
     * @param dirName      root directory name
     * @param passwordHash SHA-256 password hash
     * @return {@code true} if the server returned 200 (granted), {@code false} otherwise
     */
    public boolean authenticate(String clientId, String dirName, String passwordHash) {
        try {
            restClient.post()
                    .uri("/api/dirs/{name}/auth", dirName)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new AuthRequest(clientId, passwordHash))
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (HttpClientErrorException.Forbidden e) {
            log.warn("authenticate: access denied for dir '{}'", dirName);
            return false;
        } catch (Exception e) {
            log.warn("authenticate failed for dir='{}': {}", dirName, e.getMessage());
            return false;
        }
    }
}
