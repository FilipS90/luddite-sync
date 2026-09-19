package com.fstojilj.luddite.sync.client.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fstojilj.luddite.sync.common.dto.DirListResponse;
import com.fstojilj.luddite.sync.common.dto.TreeEntry;
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

    private static final String BASE_URL = "http://luddite-server:8080";
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
        DirListResponse body = new DirListResponse(List.of(new TreeEntry("Movies", 100L), new TreeEntry("Photos", 0L)));
        mockServer.expect(requestTo(BASE_URL + "/api/dirs"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(objectMapper.writeValueAsString(body), MediaType.APPLICATION_JSON));

        List<TreeEntry> result = client.fetchPublicDirs();

        assertThat(result).containsExactly(new TreeEntry("Movies", 100L), new TreeEntry("Photos", 0L));
        mockServer.verify();
    }

    @Test
    void fetchPublicDirs_returnsEmptyOnServerError() {
        mockServer.expect(requestTo(BASE_URL + "/api/dirs"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        List<TreeEntry> result = client.fetchPublicDirs();

        assertThat(result).isEmpty();
        mockServer.verify();
    }

    @Test
    void fetchPublicDirs_recoversAfterServerError() throws Exception {
        mockServer.expect(requestTo(BASE_URL + "/api/dirs"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        mockServer.expect(requestTo(BASE_URL + "/api/dirs"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        DirListResponse body = new DirListResponse(List.of(new TreeEntry("Movies", 1L)));
        mockServer.expect(requestTo(BASE_URL + "/api/dirs"))
                .andRespond(withSuccess(objectMapper.writeValueAsString(body), MediaType.APPLICATION_JSON));

        assertThat(client.fetchPublicDirs()).isEmpty();
        assertThat(client.fetchPublicDirs()).isEmpty();
        assertThat(client.fetchPublicDirs()).containsExactly(new TreeEntry("Movies", 1L));
        mockServer.verify();
    }

    // ── fetchTree ─────────────────────────────────────────────────────────────

    @Test
    void fetchTree_returnsChildrenAtRootLevel() throws Exception {
        TreeResponse body = new TreeResponse(
                List.of(new TreeEntry("Action", 10L), new TreeEntry("Drama", 20L)), List.of(new TreeEntry("cover.jpg", 5L)));
        mockServer.expect(requestTo(BASE_URL + "/api/dirs/Movies/tree"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(objectMapper.writeValueAsString(body), MediaType.APPLICATION_JSON));

        TreeResponse result = client.fetchTree("Movies", "", null);

        assertThat(result.childDirs()).containsExactly(new TreeEntry("Action", 10L), new TreeEntry("Drama", 20L));
        assertThat(result.files()).containsExactly(new TreeEntry("cover.jpg", 5L));
        mockServer.verify();
    }

    @Test
    void fetchTree_sendsUnderParam() throws Exception {
        TreeResponse body = new TreeResponse(List.of(new TreeEntry("2024", 1L), new TreeEntry("2023", 1L)), List.of());
        mockServer.expect(requestTo(BASE_URL + "/api/dirs/Movies/tree?under=Action"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(objectMapper.writeValueAsString(body), MediaType.APPLICATION_JSON));

        TreeResponse result = client.fetchTree("Movies", "Action", null);

        assertThat(result.childDirs()).extracting(TreeEntry::name).containsExactly("2024", "2023");
        assertThat(result.files()).isEmpty();
        mockServer.verify();
    }

    @Test
    void fetchTree_sendsAuthHashHeaderForPrivateDir() throws Exception {
        TreeResponse body = new TreeResponse(List.of(new TreeEntry("hidden", 1L)), List.of());
        mockServer.expect(requestTo(BASE_URL + "/api/dirs/Secrets/tree"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Auth-Hash", "abc123"))
                .andRespond(withSuccess(objectMapper.writeValueAsString(body), MediaType.APPLICATION_JSON));

        TreeResponse result = client.fetchTree("Secrets", "", "abc123");

        assertThat(result.childDirs()).extracting(TreeEntry::name).containsExactly("hidden");
        mockServer.verify();
    }

    @Test
    void fetchTree_returnsEmptyOn404() {
        mockServer.expect(requestTo(BASE_URL + "/api/dirs/Secrets/tree"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        TreeResponse result = client.fetchTree("Secrets", "", "wronghash");

        assertThat(result.childDirs()).isEmpty();
        assertThat(result.files()).isEmpty();
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
