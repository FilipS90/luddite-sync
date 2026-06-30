package com.fstojilj.luddite.sync.server.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fstojilj.luddite.sync.common.dto.AuthRequest;
import com.fstojilj.luddite.sync.common.dto.DirVersionCheckRequest;
import com.fstojilj.luddite.sync.common.dto.DirVersionEntry;
import com.fstojilj.luddite.sync.common.model.RootDir;
import com.fstojilj.luddite.sync.server.service.AuthCacheService;
import com.fstojilj.luddite.sync.server.service.FileMetadataService;
import com.fstojilj.luddite.sync.server.service.RootDirService;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class DirsControllerTest {

    @Mock
    private RootDirService rootDirService;

    @Mock
    private FileMetadataService fileMetadataService;

    @Mock
    private AuthCacheService authCacheService;

    @InjectMocks
    private DirsController controller;

    private MockMvc mvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    // ── GET /api/dirs ─────────────────────────────────────────────────────────

    @Test
    void listDirs_returnsOnlyPublicDirNames() throws Exception {
        RootDir pub = RootDir.builder().id(1).name("Movies").isPrivate(false).build();
        RootDir priv = RootDir.builder().id(2).name("Secrets").isPrivate(true).build();
        when(rootDirService.findAll()).thenReturn(Set.of(pub, priv));

        mvc.perform(get("/api/dirs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dirs").isArray())
                .andExpect(jsonPath("$.dirs[0]").value("Movies"))
                .andExpect(jsonPath("$.dirs.length()").value(1));
    }

    @Test
    void listDirs_emptyWhenNoDirsConfigured() throws Exception {
        when(rootDirService.findAll()).thenReturn(Set.of());

        mvc.perform(get("/api/dirs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dirs").isEmpty());
    }

    // ── GET /api/dirs/{name}/tree ─────────────────────────────────────────────

    @Test
    void getTree_returns404ForUnknownDir() throws Exception {
        when(rootDirService.findByName("unknown")).thenReturn(Optional.empty());

        mvc.perform(get("/api/dirs/unknown/tree"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getTree_returnsChildNamesForPublicDir() throws Exception {
        RootDir dir = RootDir.builder().id(1).name("Movies").isPrivate(false).build();
        when(rootDirService.findByName("Movies")).thenReturn(Optional.of(dir));
        when(fileMetadataService.findImmediateChildDirNames(eq(1), anyString()))
                .thenReturn(List.of("Action", "Drama"));
        when(fileMetadataService.findImmediateChildFileNames(eq(1), anyString()))
                .thenReturn(List.of());

        mvc.perform(get("/api/dirs/Movies/tree"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.childNames[0]").value("Action"))
                .andExpect(jsonPath("$.childNames[1]").value("Drama"));
    }

    @Test
    void getTree_returns403ForPrivateDirWithoutHash() throws Exception {
        RootDir dir = RootDir.builder().id(2).name("Secrets").isPrivate(true).password("abc123").build();
        when(rootDirService.findByName("Secrets")).thenReturn(Optional.of(dir));

        mvc.perform(get("/api/dirs/Secrets/tree"))
                .andExpect(status().isForbidden());
    }

    @Test
    void getTree_returns403ForPrivateDirWithWrongHash() throws Exception {
        RootDir dir = RootDir.builder().id(2).name("Secrets").isPrivate(true).password("abc123").build();
        when(rootDirService.findByName("Secrets")).thenReturn(Optional.of(dir));

        mvc.perform(get("/api/dirs/Secrets/tree").header("X-Auth-Hash", "wronghash"))
                .andExpect(status().isForbidden());
    }

    @Test
    void getTree_allowsPrivateDirWithCorrectHash() throws Exception {
        RootDir dir = RootDir.builder().id(2).name("Secrets").isPrivate(true).password("abc123").build();
        when(rootDirService.findByName("Secrets")).thenReturn(Optional.of(dir));
        when(fileMetadataService.findImmediateChildDirNames(anyInt(), anyString()))
                .thenReturn(List.of("hidden"));
        when(fileMetadataService.findImmediateChildFileNames(anyInt(), anyString()))
                .thenReturn(List.of());

        mvc.perform(get("/api/dirs/Secrets/tree").header("X-Auth-Hash", "abc123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.childNames[0]").value("hidden"));
    }

    @Test
    void getTree_passesUnderParamToService() throws Exception {
        RootDir dir = RootDir.builder().id(1).name("Movies").isPrivate(false).build();
        when(rootDirService.findByName("Movies")).thenReturn(Optional.of(dir));
        when(fileMetadataService.findImmediateChildDirNames(eq(1), eq("Action")))
                .thenReturn(List.of("2024", "2023"));
        when(fileMetadataService.findImmediateChildFileNames(eq(1), eq("Action")))
                .thenReturn(List.of());

        mvc.perform(get("/api/dirs/Movies/tree").param("under", "Action"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.childNames.length()").value(2));
    }

    // ── POST /api/dirs/{name}/auth ────────────────────────────────────────────

    @Test
    void auth_returns400ForPublicDir() throws Exception {
        RootDir pub = RootDir.builder().id(1).name("Movies").isPrivate(false).build();
        when(rootDirService.findByName("Movies")).thenReturn(Optional.of(pub));

        mvc.perform(post("/api/dirs/Movies/auth")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AuthRequest("client-1", "hash"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void auth_returns400ForUnknownDir() throws Exception {
        when(rootDirService.findByName("ghost")).thenReturn(Optional.empty());

        mvc.perform(post("/api/dirs/ghost/auth")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AuthRequest("client-1", "hash"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void auth_returnsGrantedTrueOnCorrectPassword() throws Exception {
        RootDir priv = RootDir.builder().id(2).name("Secrets").isPrivate(true).password("abc123").build();
        when(rootDirService.findByName("Secrets")).thenReturn(Optional.of(priv));

        mvc.perform(post("/api/dirs/Secrets/auth")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AuthRequest("client-1", "abc123"))))
                .andExpect(status().isOk());
    }

    @Test
    void auth_returns403OnWrongPassword() throws Exception {
        RootDir priv = RootDir.builder().id(2).name("Secrets").isPrivate(true).password("abc123").build();
        when(rootDirService.findByName("Secrets")).thenReturn(Optional.of(priv));

        mvc.perform(post("/api/dirs/Secrets/auth")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AuthRequest("client-1", "wronghash"))))
                .andExpect(status().isForbidden());
    }

    // ── POST /api/sync/versions ───────────────────────────────────────────────

    @Test
    void checkVersions_returnsCurrentVersionForPublicDir() throws Exception {
        RootDir pub = RootDir.builder().id(1).name("Movies").isPrivate(false).build();
        when(rootDirService.findByName("Movies")).thenReturn(Optional.of(pub));
        when(fileMetadataService.getMaxSyncVersionForDir(1)).thenReturn(42L);

        DirVersionCheckRequest req = new DirVersionCheckRequest(List.of(new DirVersionEntry("Movies", null)));

        mvc.perform(post("/api/dirs/versions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versions.Movies").value(42));
    }

    @Test
    void checkVersions_omitsPrivateDirOnWrongHash() throws Exception {
        RootDir priv = RootDir.builder().id(2).name("Secrets").isPrivate(true).password("abc123").build();
        when(rootDirService.findByName("Secrets")).thenReturn(Optional.of(priv));

        DirVersionCheckRequest req = new DirVersionCheckRequest(
                List.of(new DirVersionEntry("Secrets", "wronghash")));

        mvc.perform(post("/api/dirs/versions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versions.Secrets").doesNotExist());
    }

    @Test
    void checkVersions_includesPrivateDirOnCorrectHash() throws Exception {
        RootDir priv = RootDir.builder().id(2).name("Secrets").isPrivate(true).password("abc123").build();
        when(rootDirService.findByName("Secrets")).thenReturn(Optional.of(priv));
        when(fileMetadataService.getMaxSyncVersionForDir(2)).thenReturn(7L);

        DirVersionCheckRequest req = new DirVersionCheckRequest(
                List.of(new DirVersionEntry("Secrets", "abc123")));

        mvc.perform(post("/api/dirs/versions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versions.Secrets").value(7));
    }

    @Test
    void checkVersions_returnsZeroWhenNoVersionsExist() throws Exception {
        RootDir pub = RootDir.builder().id(1).name("Empty").isPrivate(false).build();
        when(rootDirService.findByName("Empty")).thenReturn(Optional.of(pub));
        when(fileMetadataService.getMaxSyncVersionForDir(1)).thenReturn(null);

        DirVersionCheckRequest req = new DirVersionCheckRequest(List.of(new DirVersionEntry("Empty", null)));

        mvc.perform(post("/api/dirs/versions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versions.Empty").value(0));
    }
}
