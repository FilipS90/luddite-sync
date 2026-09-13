package com.fstojilj.luddite.sync.client.cli;

import com.fstojilj.luddite.sync.client.model.ClientRootDir;
import com.fstojilj.luddite.sync.client.service.ClientSyncService;
import com.fstojilj.luddite.sync.client.service.DownloadService;
import com.fstojilj.luddite.sync.client.service.HostSettingsService;
import com.fstojilj.luddite.sync.client.service.RootDirService;
import com.fstojilj.luddite.sync.client.service.ServerApiClient;
import com.fstojilj.luddite.sync.common.dto.TreeResponse;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClientCliTest {

    @Mock
    private RootDirService rootDirService;
    @Mock
    private ClientSyncService clientSyncService;
    @Mock
    private ServerApiClient serverApiClient;
    @Mock
    private DownloadService downloadService;
    @Mock
    private HostSettingsService hostSettingsService;

    @InjectMocks
    private ClientCli clientCli;

    private ByteArrayOutputStream out;
    private Method handleMethod;

    @BeforeEach
    void setUp() throws Exception {
        out = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out));
        ReflectionTestUtils.setField(clientCli, "mirrorDir", "/mirror");

        // Seed server dirs for 'add' index-based lookup
        List<String> dirs = new ArrayList<>(List.of("photos", "documents", "music"));
        ReflectionTestUtils.setField(ClientSyncService.class, "serverDirs", dirs);

        handleMethod = ClientCli.class.getDeclaredMethod("handle", String.class);
        handleMethod.setAccessible(true);
    }

    private void handle(String cmd) throws Exception {
        handleMethod.invoke(clientCli, cmd);
    }

    private String output() {
        return out.toString();
    }

    // ── empty line ────────────────────────────────────────────────────────────

    @Test
    void handle_emptyLine_doesNothing() throws Exception {
        handle("");
        assertThat(output()).isEmpty();
    }

    // ── list ──────────────────────────────────────────────────────────────────

    @Test
    void handle_list_noDirs_printsNone() throws Exception {
        when(rootDirService.findAll()).thenReturn(List.of());
        handle("list");
        assertThat(output()).contains("no directories configured");
    }

    @Test
    void handle_list_withDirs_printsDirs() throws Exception {
        when(rootDirService.findAll()).thenReturn(List.of(new ClientRootDir("photos", 42L, null)));
        handle("list");
        assertThat(output()).contains("photos");
        assertThat(output()).contains("42");
    }

    // ── add ───────────────────────────────────────────────────────────────────

    @Test
    void handle_add_noArg_printsUsage() throws Exception {
        when(rootDirService.findAll()).thenReturn(List.of());
        handle("add");
        assertThat(output()).contains("Usage:");
    }

    @Test
    void handle_add_validIndex_registersDir() throws Exception {
        when(rootDirService.findAll()).thenReturn(List.of());
        handle("add 1");
        verify(rootDirService).registerWithDefaultPath("photos");
        assertThat(output()).contains("photos");
    }

    @Test
    void handle_add_multipleIndices_registersAll() throws Exception {
        when(rootDirService.findAll()).thenReturn(List.of());
        handle("add 1,2");
        verify(rootDirService).registerWithDefaultPath("photos");
        verify(rootDirService).registerWithDefaultPath("documents");
    }

    // ── remove ────────────────────────────────────────────────────────────────

    @Test
    void handle_remove_noArg_printsUsage() throws Exception {
        when(rootDirService.findAll()).thenReturn(List.of());
        handle("remove");
        assertThat(output()).contains("Usage (unsubscribe from dir): remove <dir-name>");
    }

    @Test
    void handle_remove_unknownDir_printsError() throws Exception {
        when(rootDirService.findAll()).thenReturn(List.of(new ClientRootDir("photos", 1L, null)));
        handle("remove unknown");
        assertThat(output()).contains("No such directory");
    }

    @Test
    void handle_remove_knownDir_callsRemove() throws Exception {
        when(rootDirService.findAll()).thenReturn(List.of(new ClientRootDir("photos", 1L, null)));
        handle("remove photos");
        verify(rootDirService).removeDirectory("photos", false);
        assertThat(output()).contains("photos");
    }

    // ── refresh ───────────────────────────────────────────────────────────────

    @Test
    void handle_refresh_callsReconnect() throws Exception {
        when(rootDirService.findAll()).thenReturn(List.of());
        handle("refresh");
        verify(clientSyncService).reconnect();
    }

    // ── mirror ────────────────────────────────────────────────────────────────

    @Test
    void handle_mirror_printsMirrorDir() throws Exception {
        when(rootDirService.findAll()).thenReturn(List.of());
        handle("mirror");
        assertThat(output()).contains("/mirror");
    }

    // ── help ──────────────────────────────────────────────────────────────────

    @Test
    void handle_help_printsHelp() throws Exception {
        when(rootDirService.findAll()).thenReturn(List.of());
        handle("help");
        assertThat(output()).contains("Luddite Sync Client");
    }

    // ── unknown command ───────────────────────────────────────────────────────

    @Test
    void handle_unknownCommand_printsError() throws Exception {
        when(rootDirService.findAll()).thenReturn(List.of());
        handle("foobar");
        assertThat(output()).contains("Unknown command");
    }

    // ── add by name ───────────────────────────────────────────────────────────

    @Test
    void handle_add_validName_registersDir() throws Exception {
        when(rootDirService.findAll()).thenReturn(List.of());
        handle("add photos");
        verify(rootDirService).registerWithDefaultPath("photos");
        assertThat(output()).contains("Subscribed to: photos");
    }

    @Test
    void handle_add_multipleNames_registersAll() throws Exception {
        when(rootDirService.findAll()).thenReturn(List.of());
        handle("add photos, music");
        verify(rootDirService).registerWithDefaultPath("photos");
        verify(rootDirService).registerWithDefaultPath("music");
    }

    @Test
    void handle_add_outOfRangeIndex_printsError() throws Exception {
        when(rootDirService.findAll()).thenReturn(List.of());
        handle("add 9");
        verify(rootDirService, never()).registerWithDefaultPath(any());
        assertThat(output()).contains("No directory at index 9");
    }

    @Test
    void handle_add_alreadySubscribed_doesNotReregister() throws Exception {
        when(rootDirService.findAll()).thenReturn(List.of(new ClientRootDir("photos", 1L, null)));
        handle("add photos");
        verify(rootDirService, never()).registerWithDefaultPath(any());
        verify(clientSyncService, never()).reconnect();
        assertThat(output()).contains("Already subscribed to: photos");
    }

    @Test
    void handle_add_withCustomPath_registersCustomPath() throws Exception {
        when(rootDirService.findAll()).thenReturn(List.of());
        handle("add photos --path /data/pics");
        String expectedPath = Path.of("/data/pics").toAbsolutePath().normalize().toString();
        verify(rootDirService).registerWithCustomPath("photos", expectedPath);
    }

    @Test
    void handle_add_customPathWithMultipleDirs_printsError() throws Exception {
        when(rootDirService.findAll()).thenReturn(List.of());
        handle("add photos,music --path /data/pics");
        verify(rootDirService, never()).registerWithCustomPath(any(), any());
        assertThat(output()).contains("--path applies to a single directory only");
    }

    // ── dirs ──────────────────────────────────────────────────────────────────

    @Test
    void handle_dirs_printsServerDirs() throws Exception {
        when(rootDirService.findAll()).thenReturn(List.of());
        handle("dirs");
        assertThat(output()).contains("1. photos").contains("2. documents").contains("3. music");
    }

    // ── browse ────────────────────────────────────────────────────────────────

    @Test
    void handle_browse_noArg_atTopLevel_printsServerDirs() throws Exception {
        when(rootDirService.findAll()).thenReturn(List.of());
        handle("browse");
        assertThat(output()).contains("1. photos").contains("2. documents").contains("3. music");
    }

    @Test
    void handle_browse_rootDir_printsChildrenAndFiles() throws Exception {
        when(rootDirService.findAll()).thenReturn(List.of());
        when(serverApiClient.fetchTree("photos", "", null))
                .thenReturn(new TreeResponse(List.of("2024"), List.of("cover.jpg")));
        handle("browse photos");
        assertThat(output()).contains("1. [dir ] 2024").contains("2. [file] cover.jpg");
    }

    @Test
    void handle_browse_subPath_passesPasswordHashAndNormalizesPath() throws Exception {
        when(rootDirService.findAll()).thenReturn(List.of());
        when(rootDirService.getPasswordHash("photos")).thenReturn("hash");
        when(serverApiClient.fetchTree("photos", "2024/may", "hash"))
                .thenReturn(new TreeResponse(List.of(), List.of("a.jpg")));
        handle("browse photos /2024/may/");
        assertThat(output()).contains("photos/2024/may").contains("1. [file] a.jpg");
    }

    @Test
    void handle_browse_emptyTree_printsEmpty() throws Exception {
        when(rootDirService.findAll()).thenReturn(List.of());
        when(serverApiClient.fetchTree("photos", "", null))
                .thenReturn(new TreeResponse(List.of(), List.of()));
        handle("browse photos");
        assertThat(output()).contains("(empty, or access denied)");
    }

    @Test
    void handle_tree_isAliasForBrowse() throws Exception {
        when(rootDirService.findAll()).thenReturn(List.of());
        when(serverApiClient.fetchTree("music", "", null))
                .thenReturn(new TreeResponse(List.of("rock"), List.of()));
        handle("tree music");
        assertThat(output()).contains("1. [dir ] rock");
    }

    // ── download ──────────────────────────────────────────────────────────────

    @Test
    void handle_download_noArg_printsUsage() throws Exception {
        when(rootDirService.findAll()).thenReturn(List.of());
        handle("download");
        assertThat(output()).contains("Usage: download");
    }

    @Test
    void handle_download_rootDir_downloadsAsDirectory() throws Exception {
        when(rootDirService.findAll()).thenReturn(List.of());
        when(downloadService.download("photos", "", false)).thenReturn(3);
        handle("download photos");
        verify(downloadService).download("photos", "", false);
        assertThat(output()).contains("Downloaded 3 file(s)");
    }

    @Test
    void handle_download_fileSubPath_downloadsAsFile() throws Exception {
        when(rootDirService.findAll()).thenReturn(List.of());
        when(serverApiClient.fetchTree("photos", "2024", null))
                .thenReturn(new TreeResponse(List.of(), List.of("a.jpg")));
        when(downloadService.download("photos", "2024/a.jpg", true)).thenReturn(1);
        handle("download photos 2024/a.jpg");
        verify(downloadService).download("photos", "2024/a.jpg", true);
        assertThat(output()).contains("Downloaded 1 file(s)");
    }

    @Test
    void handle_download_dirSubPath_downloadsAsDirectory() throws Exception {
        when(rootDirService.findAll()).thenReturn(List.of());
        when(serverApiClient.fetchTree("photos", "", null))
                .thenReturn(new TreeResponse(List.of("2024"), List.of()));
        when(downloadService.download("photos", "2024", false)).thenReturn(5);
        handle("download photos 2024");
        verify(downloadService).download("photos", "2024", false);
    }

    @Test
    void handle_download_byIndex_resolvesDirName() throws Exception {
        when(rootDirService.findAll()).thenReturn(List.of());
        when(downloadService.download("documents", "", false)).thenReturn(1);
        handle("download 2");
        verify(downloadService).download("documents", "", false);
    }

    @Test
    void handle_download_noFiles_printsFailure() throws Exception {
        when(rootDirService.findAll()).thenReturn(List.of());
        when(downloadService.download("photos", "", false)).thenReturn(0);
        handle("download photos");
        assertThat(output()).contains("Download failed or found no files");
    }

    // ── private ───────────────────────────────────────────────────────────────

    @Test
    void handle_private_noPassword_printsUsage() throws Exception {
        when(rootDirService.findAll()).thenReturn(List.of());
        handle("private secrets");
        verify(clientSyncService, never()).requestPrivateDir(any(), any());
        assertThat(output()).contains("Usage: private <dir-name> --pswd <password>");
    }

    @Test
    void handle_private_accessGranted_printsGranted() throws Exception {
        when(rootDirService.findAll()).thenReturn(List.of());
        when(clientSyncService.getPrivateAuthResults()).thenReturn(Map.of("secrets", true));
        handle("private secrets --pswd hunter2");
        verify(clientSyncService).requestPrivateDir("secrets", "hunter2");
        assertThat(output()).contains("Access granted to private dir: secrets");
    }

    @Test
    void handle_private_accessDenied_unregistersDir() throws Exception {
        when(rootDirService.findAll()).thenReturn(List.of());
        when(clientSyncService.getPrivateAuthResults()).thenReturn(Map.of("secrets", false));
        handle("private secrets --pswd wrong");
        verify(rootDirService).remove("secrets");
        assertThat(output()).contains("Access denied");
    }

    @Test
    void handle_private_passwordWithSpaces_isPassedWhole() throws Exception {
        when(rootDirService.findAll()).thenReturn(List.of());
        when(clientSyncService.getPrivateAuthResults()).thenReturn(Map.of("secrets", true));
        handle("private secrets --pswd correct horse battery");
        verify(clientSyncService).requestPrivateDir("secrets", "correct horse battery");
    }

    @Test
    void handle_private_alreadySubscribed_doesNotRequest() throws Exception {
        when(rootDirService.findAll()).thenReturn(List.of(new ClientRootDir("secrets", 1L, null)));
        handle("private secrets --pswd hunter2");
        verify(clientSyncService, never()).requestPrivateDir(any(), any());
        assertThat(output()).contains("Already subscribed to: secrets");
    }

    // ── remove --delete ───────────────────────────────────────────────────────

    @Test
    void handle_remove_withDeleteFlag_deletesLocalFiles() throws Exception {
        when(rootDirService.findAll()).thenReturn(List.of(new ClientRootDir("photos", 1L, null)));
        handle("remove photos --delete");
        verify(rootDirService).removeDirectory("photos", true);
        assertThat(output()).contains("deleted its local files");
    }

    // ── host ──────────────────────────────────────────────────────────────────

    @Test
    void handle_host_noArg_printsCurrentAndHistory() throws Exception {
        when(rootDirService.findAll()).thenReturn(List.of());
        when(hostSettingsService.getCurrentHost()).thenReturn("192.168.1.10");
        when(hostSettingsService.getHistory()).thenReturn(List.of("192.168.1.10", "nas.local"));
        handle("host");
        assertThat(output()).contains("Current host: 192.168.1.10").contains("nas.local");
    }

    @Test
    void handle_host_noHistory_printsNone() throws Exception {
        when(rootDirService.findAll()).thenReturn(List.of());
        when(hostSettingsService.getCurrentHost()).thenReturn("localhost");
        when(hostSettingsService.getHistory()).thenReturn(List.of());
        handle("host");
        assertThat(output()).contains("(no previously used hosts)");
    }

    @Test
    void handle_host_withName_switchesHost() throws Exception {
        when(rootDirService.findAll()).thenReturn(List.of());
        handle("host nas.local");
        verify(hostSettingsService).switchTo("nas.local");
        assertThat(output()).contains("Switching to host: nas.local");
    }

    @Test
    void handle_host_forget_removesFromHistory() throws Exception {
        when(rootDirService.findAll()).thenReturn(List.of());
        handle("host --forget nas.local");
        verify(hostSettingsService).forget("nas.local");
        verify(hostSettingsService, never()).switchTo(any());
        assertThat(output()).contains("Forgot host: nas.local");
    }

    @Test
    void handle_host_forgetNoArg_printsUsage() throws Exception {
        when(rootDirService.findAll()).thenReturn(List.of());
        handle("host --forget");
        verify(hostSettingsService, never()).forget(any());
        assertThat(output()).contains("Usage: host --forget <host>");
    }

    // ── browse navigation ─────────────────────────────────────────────────────

    @Test
    void handle_browse_number_atTopLevel_entersServerDir() throws Exception {
        when(rootDirService.findAll()).thenReturn(List.of());
        when(serverApiClient.fetchTree("documents", "", null))
                .thenReturn(new TreeResponse(List.of("tax"), List.of()));
        handle("browse 2");
        assertThat(output()).contains("documents").contains("1. [dir ] tax");
    }

    @Test
    void handle_browse_number_afterListing_entersThatEntry() throws Exception {
        when(rootDirService.findAll()).thenReturn(List.of());
        when(serverApiClient.fetchTree("photos", "", null))
                .thenReturn(new TreeResponse(List.of("2024"), List.of("cover.jpg")));
        when(serverApiClient.fetchTree("photos", "2024", null))
                .thenReturn(new TreeResponse(List.of("may"), List.of()));
        when(serverApiClient.fetchTree("photos", "2024/may", null))
                .thenReturn(new TreeResponse(List.of(), List.of("a.jpg")));
        handle("browse photos");
        handle("browse 1");
        handle("browse 1");
        assertThat(output()).contains("photos/2024").contains("photos/2024/may").contains("1. [file] a.jpg");
    }

    @Test
    void handle_browse_number_onFile_pointsToDownload() throws Exception {
        when(rootDirService.findAll()).thenReturn(List.of());
        when(serverApiClient.fetchTree("photos", "", null))
                .thenReturn(new TreeResponse(List.of("2024"), List.of("cover.jpg")));
        handle("browse photos");
        handle("browse 2");
        verify(serverApiClient, never()).fetchTree(eq("photos"), eq("cover.jpg"), any());
        assertThat(output()).contains("'cover.jpg' is a file").contains("download 2");
    }

    @Test
    void handle_browse_number_outOfRange_printsError() throws Exception {
        when(rootDirService.findAll()).thenReturn(List.of());
        handle("browse 7");
        verify(serverApiClient, never()).fetchTree(any(), any(), any());
        assertThat(output()).contains("No entry 7 in the current listing");
    }

    @Test
    void handle_browse_noArg_afterListing_relistsCurrentLocation() throws Exception {
        when(rootDirService.findAll()).thenReturn(List.of());
        when(serverApiClient.fetchTree("photos", "2024", null))
                .thenReturn(new TreeResponse(List.of("may"), List.of()));
        handle("browse photos 2024");
        handle("browse");
        verify(serverApiClient, times(2)).fetchTree("photos", "2024", null);
    }

    @Test
    void handle_up_goesToParentAndStopsAtLevelOne() throws Exception {
        when(rootDirService.findAll()).thenReturn(List.of());
        when(serverApiClient.fetchTree("photos", "2024/may", null))
                .thenReturn(new TreeResponse(List.of(), List.of("a.jpg")));
        when(serverApiClient.fetchTree("photos", "2024", null))
                .thenReturn(new TreeResponse(List.of("may"), List.of()));
        when(serverApiClient.fetchTree("photos", "", null))
                .thenReturn(new TreeResponse(List.of("2024"), List.of()));
        handle("browse photos 2024/may");
        handle("up");
        handle("browse up");
        handle("up");
        verify(serverApiClient).fetchTree("photos", "2024", null);
        verify(serverApiClient).fetchTree("photos", "", null);
        assertThat(output()).contains("Already at level one of 'photos'");
        assertThat(output()).doesNotContain("Server has the following directories available");
    }

    @Test
    void handle_up_atServerDirs_printsTopLevelMessage() throws Exception {
        when(rootDirService.findAll()).thenReturn(List.of());
        handle("up");
        verify(serverApiClient, never()).fetchTree(any(), any(), any());
        assertThat(output()).contains("Already at the top level");
    }

    @Test
    void handle_up_atLevelOne_keepsListingUsable() throws Exception {
        when(rootDirService.findAll()).thenReturn(List.of());
        when(serverApiClient.fetchTree("photos", "", null))
                .thenReturn(new TreeResponse(List.of("2024"), List.of()));
        when(serverApiClient.fetchTree("photos", "2024", null))
                .thenReturn(new TreeResponse(List.of(), List.of()));
        handle("browse photos");
        handle("browse up");
        handle("browse 1");
        verify(serverApiClient).fetchTree("photos", "2024", null);
    }

    @Test
    void handle_browse_unknownName_printsErrorWithoutFetching() throws Exception {
        when(rootDirService.findAll()).thenReturn(List.of());
        handle("browse ghost");
        verify(serverApiClient, never()).fetchTree(any(), any(), any());
        assertThat(output()).contains("No such directory 'ghost'");
    }

    @Test
    void handle_browse_subscribedPrivateName_isAllowed() throws Exception {
        when(rootDirService.findAll()).thenReturn(List.of(new ClientRootDir("vault", 1L, null)));
        when(rootDirService.getPasswordHash("vault")).thenReturn("hash");
        when(serverApiClient.fetchTree("vault", "", "hash"))
                .thenReturn(new TreeResponse(List.of(), List.of("secret.txt")));
        handle("browse vault");
        assertThat(output()).contains("1. [file] secret.txt");
    }

    @Test
    void handle_dirs_resetsListingToServerDirs() throws Exception {
        when(rootDirService.findAll()).thenReturn(List.of());
        when(serverApiClient.fetchTree("photos", "", null))
                .thenReturn(new TreeResponse(List.of("2024"), List.of()));
        when(serverApiClient.fetchTree("music", "", null))
                .thenReturn(new TreeResponse(List.of(), List.of()));
        handle("browse photos");
        handle("dirs");
        handle("browse 3");
        verify(serverApiClient).fetchTree("music", "", null);
    }

    @Test
    void handle_download_number_afterListing_downloadsThatEntry() throws Exception {
        when(rootDirService.findAll()).thenReturn(List.of());
        when(serverApiClient.fetchTree("photos", "", null))
                .thenReturn(new TreeResponse(List.of("2024"), List.of("cover.jpg")));
        when(downloadService.download("photos", "cover.jpg", true)).thenReturn(1);
        handle("browse photos");
        handle("download 2");
        verify(downloadService).download("photos", "cover.jpg", true);
        verify(serverApiClient, times(1)).fetchTree(any(), any(), any());
    }

    @Test
    void handle_download_number_afterListing_downloadsSubdirEntry() throws Exception {
        when(rootDirService.findAll()).thenReturn(List.of());
        when(serverApiClient.fetchTree("photos", "", null))
                .thenReturn(new TreeResponse(List.of("2024"), List.of("cover.jpg")));
        when(downloadService.download("photos", "2024", false)).thenReturn(3);
        handle("browse photos");
        handle("download 1");
        verify(downloadService).download("photos", "2024", false);
    }

    // ── browse --depth ────────────────────────────────────────────────────────

    @Test
    void handle_browse_depth_listsNestedLevelsIndentedAndNumberedFlat() throws Exception {
        when(rootDirService.findAll()).thenReturn(List.of());
        when(serverApiClient.fetchTree("photos", "", null))
                .thenReturn(new TreeResponse(List.of("2024"), List.of("cover.jpg")));
        when(serverApiClient.fetchTree("photos", "2024", null))
                .thenReturn(new TreeResponse(List.of("may"), List.of()));
        handle("browse photos --depth 2");
        verify(serverApiClient, never()).fetchTree(eq("photos"), eq("2024/may"), any());
        assertThat(output())
                .contains("1. [dir ] 2024")
                .contains("2.   [dir ] may")
                .contains("3. [file] cover.jpg");
    }

    @Test
    void handle_browse_depth_entriesAtAnyLevelAreAddressableByNumber() throws Exception {
        when(rootDirService.findAll()).thenReturn(List.of());
        when(serverApiClient.fetchTree("photos", "", null))
                .thenReturn(new TreeResponse(List.of("2024"), List.of("cover.jpg")));
        when(serverApiClient.fetchTree("photos", "2024", null))
                .thenReturn(new TreeResponse(List.of("may"), List.of()));
        when(downloadService.download("photos", "2024/may", false)).thenReturn(2);
        handle("browse photos --depth 2");
        handle("download 2");
        verify(downloadService).download("photos", "2024/may", false);
    }

    @Test
    void handle_browse_depth_onNumberAndOnCurrentLocation() throws Exception {
        when(rootDirService.findAll()).thenReturn(List.of());
        when(serverApiClient.fetchTree("photos", "", null))
                .thenReturn(new TreeResponse(List.of("2024"), List.of()));
        when(serverApiClient.fetchTree("photos", "2024", null))
                .thenReturn(new TreeResponse(List.of("may"), List.of()));
        when(serverApiClient.fetchTree("photos", "2024/may", null))
                .thenReturn(new TreeResponse(List.of(), List.of("a.jpg")));
        handle("browse 1 --depth 2");
        handle("browse --depth 3");
        verify(serverApiClient, times(1)).fetchTree("photos", "2024/may", null);
        verify(serverApiClient, times(2)).fetchTree("photos", "", null);
    }

    @Test
    void handle_browse_depth_invalid_printsError() throws Exception {
        when(rootDirService.findAll()).thenReturn(List.of());
        handle("browse photos --depth 0");
        handle("browse photos --depth 9");
        handle("browse photos --depth x");
        verify(serverApiClient, never()).fetchTree(any(), any(), any());
        assertThat(output()).contains("--depth must be a number from 1 to 5");
    }
}
