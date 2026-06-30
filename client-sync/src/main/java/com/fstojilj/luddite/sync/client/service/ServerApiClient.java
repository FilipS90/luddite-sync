package com.fstojilj.luddite.sync.client.service;

import com.fstojilj.luddite.sync.common.dto.AuthRequest;
import com.fstojilj.luddite.sync.common.dto.DirListResponse;
import com.fstojilj.luddite.sync.common.dto.TreeResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Thin HTTP client for the server's directory REST API.
 * All methods swallow exceptions and return safe empty/false defaults so callers
 * never need to handle network errors directly.
 */
@Service
@Slf4j
public class ServerApiClient {

    private final RestClient restClient;

    @Autowired
    public ServerApiClient(@Value("${sync.server.api-url}") String apiUrl) {
        this.restClient = RestClient.builder()
                .baseUrl(apiUrl)
                .build();
    }

    /** Package-private constructor for testing with a pre-built RestClient. */
    ServerApiClient(RestClient restClient) {
        this.restClient = restClient;
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
