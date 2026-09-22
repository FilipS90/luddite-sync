package com.fstojilj.luddite.sync.server.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fstojilj.luddite.sync.common.dto.AuthRequest;
import com.fstojilj.luddite.sync.common.dto.TreeEntry;
import com.fstojilj.luddite.sync.common.dto.TreeResponse;
import com.fstojilj.luddite.sync.common.model.RootDir;
import com.fstojilj.luddite.sync.server.service.AuthCacheService;
import com.fstojilj.luddite.sync.server.service.FileMetadataService;
import com.fstojilj.luddite.sync.server.service.RootDirService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
    void listDirs_returnsOnlyPublicDirsWithSizes() throws Exception {
        RootDir pub = RootDir.builder().id(1).name("Movies").isPrivate(false).build();
        RootDir priv = RootDir.builder().id(2).name("Secrets").isPrivate(true).build();
        when(rootDirService.findAll()).thenReturn(Set.of(pub, priv));
        when(fileMetadataService.sumFileSizeByRootDir()).thenReturn(Map.of(1, 4096L, 2, 99L));

        mvc.perform(get("/api/dirs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dirs").isArray())
                .andExpect(jsonPath("$.dirs[0].name").value("Movies"))
                .andExpect(jsonPath("$.dirs[0].size").value(4096))
                .andExpect(jsonPath("$.dirs.length()").value(1));
    }

    @Test
    void listDirs_dirWithoutFilesHasZeroSize() throws Exception {
        RootDir pub = RootDir.builder().id(1).name("Movies").isPrivate(false).build();
        when(rootDirService.findAll()).thenReturn(Set.of(pub));
        when(fileMetadataService.sumFileSizeByRootDir()).thenReturn(Map.of());

        mvc.perform(get("/api/dirs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dirs[0].size").value(0));
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
    void getTree_returnsChildrenForPublicDir() throws Exception {
        RootDir dir = RootDir.builder().id(1).name("Movies").isPrivate(false).build();
        when(rootDirService.findByName("Movies")).thenReturn(Optional.of(dir));
        when(fileMetadataService.findImmediateChildren(eq(1), anyString())).thenReturn(new TreeResponse(
                List.of(new TreeEntry("Action", 3000L), new TreeEntry("Drama", 10L)),
                List.of(new TreeEntry("readme.txt", 42L))));

        mvc.perform(get("/api/dirs/Movies/tree"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.childDirs[0].name").value("Action"))
                .andExpect(jsonPath("$.childDirs[0].size").value(3000))
                .andExpect(jsonPath("$.childDirs[1].name").value("Drama"))
                .andExpect(jsonPath("$.files[0].name").value("readme.txt"))
                .andExpect(jsonPath("$.files[0].size").value(42));
    }

    @Test
    void getTree_returns404ForPrivateDirWithoutHash() throws Exception {
        RootDir dir = RootDir.builder().id(2).name("Secrets").isPrivate(true).password("abc123").build();
        when(rootDirService.findByName("Secrets")).thenReturn(Optional.of(dir));

        mvc.perform(get("/api/dirs/Secrets/tree"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getTree_returns404ForPrivateDirWithWrongHash() throws Exception {
        RootDir dir = RootDir.builder().id(2).name("Secrets").isPrivate(true).password("abc123").build();
        when(rootDirService.findByName("Secrets")).thenReturn(Optional.of(dir));

        mvc.perform(get("/api/dirs/Secrets/tree").header("X-Auth-Hash", "wronghash"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getTree_allowsPrivateDirWithCorrectHash() throws Exception {
        RootDir dir = RootDir.builder().id(2).name("Secrets").isPrivate(true).password("abc123").build();
        when(rootDirService.findByName("Secrets")).thenReturn(Optional.of(dir));
        when(fileMetadataService.findImmediateChildren(anyInt(), anyString()))
                .thenReturn(new TreeResponse(List.of(new TreeEntry("hidden", 1L)), List.of()));

        mvc.perform(get("/api/dirs/Secrets/tree").header("X-Auth-Hash", "abc123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.childDirs[0].name").value("hidden"));
    }

    @Test
    void getTree_passesUnderParamToService() throws Exception {
        RootDir dir = RootDir.builder().id(1).name("Movies").isPrivate(false).build();
        when(rootDirService.findByName("Movies")).thenReturn(Optional.of(dir));
        when(fileMetadataService.findImmediateChildren(eq(1), eq("Action")))
                .thenReturn(new TreeResponse(List.of(new TreeEntry("2023", 1L), new TreeEntry("2024", 2L)), List.of()));

        mvc.perform(get("/api/dirs/Movies/tree").param("under", "Action"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.childDirs.length()").value(2));
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
    void auth_returns403ForUnknownDir() throws Exception {
        when(rootDirService.findByName("ghost")).thenReturn(Optional.empty());

        mvc.perform(post("/api/dirs/ghost/auth")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AuthRequest("client-1", "hash"))))
                .andExpect(status().isForbidden());
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
}
