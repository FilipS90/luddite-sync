package com.fstojilj.luddite.sync.server.cli;

import com.fstojilj.luddite.sync.common.model.RootDir;
import com.fstojilj.luddite.sync.common.util.PasswordUtils;
import com.fstojilj.luddite.sync.server.service.FileSocketService;
import com.fstojilj.luddite.sync.server.service.RootDirService;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminCliTest {

    @Mock
    private RootDirService rootDirService;
    @Mock
    private FileSocketService fileSocketService;

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
                RootDir.builder().id(1).name("photos").absolutePath("/photos").build()
        ));
        handle("list");
        assertThat(output()).contains("/photos");
    }

    @Test
    void handle_listc_noClients_shouldPrintNone() throws Exception {
        when(fileSocketService.listConnectedClients()).thenReturn(List.of());
        handle("listc");
        assertThat(output()).contains("no clients");
    }

    @Test
    void handle_listc_withClients_shouldPrintClients() throws Exception {
        when(fileSocketService.listConnectedClients()).thenReturn(List.of("hw-id-abc123"));
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
        verify(rootDirService).addRootDir("/home/user/photos", false, null);
        assertThat(output()).contains("Added and watching");
    }

    @Test
    void handle_add_withPathAndFlags_shouldAddAndPrint() throws Exception {
        handle("add /home/user/photos --private --pswd admin123");
        verify(rootDirService).addRootDir("/home/user/photos", true, PasswordUtils.hash("admin123"));
        assertThat(output()).contains("Added and watching");
    }

    @Test
    void handle_add_withPathAndFlags2_shouldAddAndPrint() throws Exception {
        handle("add /home/user/photos --pswd admin123 --private");
        verify(rootDirService).addRootDir("/home/user/photos", true, PasswordUtils.hash("admin123"));
        assertThat(output()).contains("Added and watching");
    }

    @Test
    void handle_remove_noArg_shouldPrintUsage() throws Exception {
        handle("remove");
        assertThat(output()).contains("Usage: remove");
    }

    @Test
    void handle_remove_validId_removed_shouldPrint() throws Exception {
        when(rootDirService.removeRootDir(1)).thenReturn(true);
        handle("remove 1");
        assertThat(output()).contains("Removed root dir id=1");
    }

    @Test
    void handle_remove_validId_notFound_shouldPrint() throws Exception {
        when(rootDirService.removeRootDir(99)).thenReturn(false);
        handle("remove 99");
        assertThat(output()).contains("No root dir found");
    }

    @Test
    void handle_remove_invalidId_shouldPrintError() throws Exception {
        handle("remove abc");
        assertThat(output()).contains("id must be a number");
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

