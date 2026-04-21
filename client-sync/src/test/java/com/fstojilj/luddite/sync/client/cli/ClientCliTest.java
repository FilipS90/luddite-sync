package com.fstojilj.luddite.sync.client.cli;

import com.fstojilj.luddite.sync.client.service.ClientSyncService;
import com.fstojilj.luddite.sync.client.service.RootDirService;
import com.fstojilj.luddite.sync.common.model.SyncHandshakeEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClientCliTest {

    @Mock
    private RootDirService rootDirService;
    @Mock
    private ClientSyncService clientSyncService;

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
        when(rootDirService.findAll()).thenReturn(List.of(new SyncHandshakeEntry("photos", 42L)));
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
        verify(rootDirService).registerIfAbsent("photos");
        assertThat(output()).contains("photos");
    }

    @Test
    void handle_add_multipleIndices_registersAll() throws Exception {
        when(rootDirService.findAll()).thenReturn(List.of());
        handle("add 1,2");
        verify(rootDirService).registerIfAbsent("photos");
        verify(rootDirService).registerIfAbsent("documents");
    }

    // ── remove ────────────────────────────────────────────────────────────────

    @Test
    void handle_remove_noArg_printsUsage() throws Exception {
        when(rootDirService.findAll()).thenReturn(List.of());
        handle("remove");
        assertThat(output()).contains("Usage: remove");
    }

    @Test
    void handle_remove_unknownDir_printsError() throws Exception {
        when(rootDirService.findAll()).thenReturn(List.of(new SyncHandshakeEntry("photos", 1L)));
        handle("remove unknown");
        assertThat(output()).contains("No such directory");
    }

    @Test
    void handle_remove_knownDir_callsRemove() throws Exception {
        when(rootDirService.findAll()).thenReturn(List.of(new SyncHandshakeEntry("photos", 1L)));
        handle("remove photos");
        verify(rootDirService).removeDirectory("photos");
        assertThat(output()).contains("photos");
    }

    // ── refresh ───────────────────────────────────────────────────────────────

    @Test
    void handle_refresh_callsReconnect() throws Exception {
        when(rootDirService.findAll()).thenReturn(List.of());
        handle("refresh");
        verify(clientSyncService).reconnect();
    }

    // ── shutdown-server ───────────────────────────────────────────────────────

    @Test
    void handle_shutdownServer_callsSendShutdown() throws Exception {
        when(rootDirService.findAll()).thenReturn(List.of());
        handle("shutdown-server");
        verify(clientSyncService).sendShutdown();
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
}

