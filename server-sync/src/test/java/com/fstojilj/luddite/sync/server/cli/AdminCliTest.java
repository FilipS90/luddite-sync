package com.fstojilj.luddite.sync.server.cli;

import com.fstojilj.luddite.sync.common.model.RootDir;
import com.fstojilj.luddite.sync.server.dns.DuckDNSUpdateJob;
import com.fstojilj.luddite.sync.server.service.RootDirService;
import com.fstojilj.luddite.sync.server.service.SyncPollService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminCliTest {

    @Mock
    private RootDirService rootDirService;
    @Mock
    private DuckDNSUpdateJob duckDNSUpdateJob;
    @Mock
    private SyncPollService syncPollService;

    @InjectMocks
    private AdminCli adminCli;

    private ByteArrayOutputStream out;
    private Method handleMethod;

    @BeforeEach
    void setUp() throws Exception {
        out = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out));
        handleMethod = AdminCli.class.getDeclaredMethod("handle", String.class);
        handleMethod.setAccessible(true);
    }

    private void handle(String cmd) throws Exception {
        handleMethod.invoke(adminCli, cmd);
    }

    private String output() {
        return out.toString();
    }

    @Test
    void handle_emptyLine_shouldDoNothing() throws Exception {
        handle("");
        assertThat(output()).isEmpty();
    }

    @Test
    void handle_list_noDirs_shouldPrintNone() throws Exception {
        when(rootDirService.findAll()).thenReturn(Set.of());
        handle("list");
        assertThat(output()).contains("no root dirs");
    }

    @Test
    void handle_list_withDirs_shouldPrintDirs() throws Exception {
        when(rootDirService.findAll()).thenReturn(Set.of(
                RootDir.builder().id(1L).name("photos").absolutePath("/photos").build()
        ));
        handle("list");
        assertThat(output()).contains("/photos");
    }

    @Test
    void handle_listc_noClients_shouldPrintNone() throws Exception {
        when(syncPollService.listConnectedClients()).thenReturn(List.of());
        handle("listc");
        assertThat(output()).contains("no clients");
    }

    @Test
    void handle_listc_withClients_shouldPrintClients() throws Exception {
        when(syncPollService.listConnectedClients()).thenReturn(List.of("hw-id-abc123"));
        handle("listc");
        assertThat(output()).contains("hw-id-abc123");
    }

    @Test
    void handle_add_noArg_shouldPrintUsage() throws Exception {
        handle("add");
        assertThat(output()).contains("Usage: add");
    }

    @Test
    void handle_add_withPath_shouldAddAndPrint() throws Exception {
        handle("add /home/user/photos");
        verify(rootDirService).addRootDir("/home/user/photos");
        assertThat(output()).contains("Added and watching");
    }

    @Test
    void handle_add_serviceThrows_shouldPrintError() throws Exception {
        doThrow(new IllegalArgumentException("bad path")).when(rootDirService).addRootDir("/bad");
        handle("add /bad");
        assertThat(output()).contains("Error: bad path");
    }

    @Test
    void handle_remove_noArg_shouldPrintUsage() throws Exception {
        handle("remove");
        assertThat(output()).contains("Usage: remove");
    }

    @Test
    void handle_remove_validId_removed_shouldPrint() throws Exception {
        when(rootDirService.removeRootDir(1L)).thenReturn(true);
        handle("remove 1");
        assertThat(output()).contains("Removed root dir id=1");
    }

    @Test
    void handle_remove_validId_notFound_shouldPrint() throws Exception {
        when(rootDirService.removeRootDir(99L)).thenReturn(false);
        handle("remove 99");
        assertThat(output()).contains("No root dir found");
    }

    @Test
    void handle_remove_invalidId_shouldPrintError() throws Exception {
        handle("remove abc");
        assertThat(output()).contains("id must be a number");
    }

    @Test
    void handle_dns_noArg_shouldPrintDomainAndToken() throws Exception {
        when(duckDNSUpdateJob.getDomain()).thenReturn("my-domain");
        when(duckDNSUpdateJob.getToken()).thenReturn("my-token");
        handle("dns");
        assertThat(output()).contains("my-domain").contains("my-token");
    }

    @Test
    void handle_dns_domain_shouldUpdateDomain() throws Exception {
        handle("dns domain new-domain");
        verify(duckDNSUpdateJob).setDomain("new-domain");
        assertThat(output()).contains("new-domain");
    }

    @Test
    void handle_dns_domain_noArg_shouldPrintUsage() throws Exception {
        handle("dns domain");
        assertThat(output()).contains("Usage: dns domain");
    }

    @Test
    void handle_dns_token_shouldUpdateToken() throws Exception {
        handle("dns token new-token");
        verify(duckDNSUpdateJob).setToken("new-token");
        assertThat(output()).contains("new-token");
    }

    @Test
    void handle_dns_token_noArg_shouldPrintUsage() throws Exception {
        handle("dns token");
        assertThat(output()).contains("Usage: dns token");
    }

    @Test
    void handle_dns_update_shouldTriggerUpdate() throws Exception {
        handle("dns update");
        verify(duckDNSUpdateJob).updateDuckDNS();
    }

    @Test
    void handle_dns_unknownSubcommand_shouldPrintUnknown() throws Exception {
        handle("dns foobar");
        assertThat(output()).contains("Unknown dns sub-command");
    }

    @Test
    void handle_switchMode_noArg_shouldPrintUsage() throws Exception {
        handle("switch-mode");
        assertThat(output()).contains("Usage: switch-mode");
    }

    @Test
    void handle_switchMode_clientFound_shouldConfirm() throws Exception {
        when(syncPollService.sendResumeServerMode("hw-id-abc123")).thenReturn(true);
        handle("switch-mode hw-id-abc123");
        assertThat(output()).contains("Switch-mode signal sent");
    }

    @Test
    void handle_switchMode_clientNotFound_shouldPrintNotFound() throws Exception {
        when(syncPollService.sendResumeServerMode("hw-id-unknown")).thenReturn(false);
        handle("switch-mode hw-id-unknown");
        assertThat(output()).contains("No connected client");
    }

    @Test
    void handle_help_shouldPrintHelp() throws Exception {
        handle("help");
        assertThat(output()).contains("Luddite Sync Server");
    }

    @Test
    void handle_unknown_shouldPrintUnknown() throws Exception {
        handle("foobar");
        assertThat(output()).contains("Unknown command");
    }
}

