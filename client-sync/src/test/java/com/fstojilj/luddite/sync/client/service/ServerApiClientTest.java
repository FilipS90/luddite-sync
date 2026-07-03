package com.fstojilj.luddite.sync.client.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fstojilj.luddite.sync.common.dto.DirListResponse;
import com.fstojilj.luddite.sync.common.dto.TreeResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ServerApiClientTest {

    private static final String BASE_URL = "http://localhost:8080";
    private final ObjectMapper objectMapper = new ObjectMapper();

    private MockRestServiceServer mockServer;
    private ServerApiClient client;

    @BeforeEach
    void setUp() {
        org.springframework.web.client.RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        mockServer = MockRestServiceServer.bindTo(builder).build();
        client = new ServerApiClient(builder.build());
    }

    // ── fetchPublicDirs ───────────────────────────────────────────────────────

    @Test
    void fetchPublicDirs_returnsListOnSuccess() throws Exception {
        DirListResponse body = new DirListResponse(List.of("Movies", "Photos"));
        mockServer.expect(requestTo(BASE_URL + "/api/dirs"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(objectMapper.writeValueAsString(body), MediaType.APPLICATION_JSON));

        List<String> result = client.fetchPublicDirs();

        assertThat(result).containsExactly("Movies", "Photos");
        mockServer.verify();
    }

    @Test
    void fetchPublicDirs_returnsEmptyOnServerError() {
        mockServer.expect(requestTo(BASE_URL + "/api/dirs"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        List<String> result = client.fetchPublicDirs();

        assertThat(result).isEmpty();
        mockServer.verify();
    }

    // ── fetchTree ─────────────────────────────────────────────────────────────

    @Test
    void fetchTree_returnsChildrenAtRootLevel() throws Exception {
        TreeResponse body = new TreeResponse(List.of("Action", "Drama"), List.of("cover.jpg"));
        mockServer.expect(requestTo(BASE_URL + "/api/dirs/Movies/tree"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(objectMapper.writeValueAsString(body), MediaType.APPLICATION_JSON));

        TreeResponse result = client.fetchTree("Movies", "", null);

        assertThat(result.childNames()).containsExactly("Action", "Drama");
        assertThat(result.fileNames()).containsExactly("cover.jpg");
        mockServer.verify();
    }

    @Test
    void fetchTree_sendsUnderParam() throws Exception {
        TreeResponse body = new TreeResponse(List.of("2024", "2023"), List.of());
        mockServer.expect(requestTo(BASE_URL + "/api/dirs/Movies/tree?under=Action"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(objectMapper.writeValueAsString(body), MediaType.APPLICATION_JSON));

        TreeResponse result = client.fetchTree("Movies", "Action", null);

        assertThat(result.childNames()).containsExactly("2024", "2023");
        assertThat(result.fileNames()).isEmpty();
        mockServer.verify();
    }

    @Test
    void fetchTree_sendsAuthHashHeaderForPrivateDir() throws Exception {
        TreeResponse body = new TreeResponse(List.of("hidden"), List.of());
        mockServer.expect(requestTo(BASE_URL + "/api/dirs/Secrets/tree"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Auth-Hash", "abc123"))
                .andRespond(withSuccess(objectMapper.writeValueAsString(body), MediaType.APPLICATION_JSON));

        TreeResponse result = client.fetchTree("Secrets", "", "abc123");

        assertThat(result.childNames()).containsExactly("hidden");
        mockServer.verify();
    }

    @Test
    void fetchTree_returnsEmptyOn403() {
        mockServer.expect(requestTo(BASE_URL + "/api/dirs/Secrets/tree"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));

        TreeResponse result = client.fetchTree("Secrets", "", "wronghash");

        assertThat(result.childNames()).isEmpty();
        assertThat(result.fileNames()).isEmpty();
        mockServer.verify();
    }

    // ── authenticate ─────────────────────────────────────────────────────────

    @Test
    void authenticate_returnsTrueOn200() {
        mockServer.expect(requestTo(BASE_URL + "/api/dirs/Secrets/auth"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess());

        boolean result = client.authenticate("client-1", "Secrets", "abc123");

        assertThat(result).isTrue();
        mockServer.verify();
    }

    @Test
    void authenticate_returnsFalseOn403() {
        mockServer.expect(requestTo(BASE_URL + "/api/dirs/Secrets/auth"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));

        boolean result = client.authenticate("client-1", "Secrets", "wronghash");

        assertThat(result).isFalse();
        mockServer.verify();
    }

    @Test
    void authenticate_returnsFalseOnNetworkError() {
        mockServer.expect(requestTo(BASE_URL + "/api/dirs/Secrets/auth"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        boolean result = client.authenticate("client-1", "Secrets", "hash");

        assertThat(result).isFalse();
        mockServer.verify();
    }
}
