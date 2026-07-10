package com.fstojilj.luddite.sync.client.service;

import com.fstojilj.luddite.sync.common.dto.TreeResponse;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DownloadServiceTest {

    @Mock
    private ServerApiClient serverApiClient;
    @Mock
    private RootDirService rootDirService;
    @Mock
    private ClientIdService clientIdService;
    @Mock
    private ClientSocketFactory socketFactory;

    @InjectMocks
    private DownloadService downloadService;

    @TempDir
    Path tempDir;

    private FakeFileServer fakeServer;

    @BeforeEach
    void setUp() throws Exception {
        ReflectionTestUtils.setField(downloadService, "mirrorDirPath", tempDir.toString());
        lenient().when(clientIdService.getClientId()).thenReturn("test-client");

        fakeServer = new FakeFileServer();
        fakeServer.start();
        lenient().when(socketFactory.connect()).thenAnswer(inv -> new Socket("localhost", fakeServer.port()));
    }

    @AfterEach
    void tearDown() throws Exception {
        fakeServer.stop();
    }

    // ── downloadSingleFile ──────────────────────────────────────────────────

    @Test
    void downloadSingleFile_writesFileContentToDestination() throws Exception {
        byte[] content = "hello world".getBytes(StandardCharsets.UTF_8);
        fakeServer.respondWith("Movies/movie.mkv", content);

        Path destination = tempDir.resolve("downloads/movie.mkv");
        boolean result = downloadService.downloadSingleFile("Movies/movie.mkv", destination);

        assertThat(result).isTrue();
        assertThat(Files.readAllBytes(destination)).isEqualTo(content);
    }

    @Test
    void downloadSingleFile_overwritesExistingFile() throws Exception {
        Path destination = tempDir.resolve("downloads/movie.mkv");
        Files.createDirectories(destination.getParent());
        Files.writeString(destination, "old content");

        byte[] newContent = "new content".getBytes(StandardCharsets.UTF_8);
        fakeServer.respondWith("Movies/movie.mkv", newContent);

        boolean result = downloadService.downloadSingleFile("Movies/movie.mkv", destination);

        assertThat(result).isTrue();
        assertThat(Files.readAllBytes(destination)).isEqualTo(newContent);
    }

    @Test
    void downloadSingleFile_returnsFalseWhenNotFoundOrDenied() {
        fakeServer.respondNotFound("Movies/missing.mkv");

        Path destination = tempDir.resolve("downloads/missing.mkv");
        boolean result = downloadService.downloadSingleFile("Movies/missing.mkv", destination);

        assertThat(result).isFalse();
        assertThat(Files.exists(destination)).isFalse();
    }

    // ── collectDescendantFiles ────────────────────────────────────────────────

    @Test
    void collectDescendantFiles_walksNestedTreeAndPreservesStructure() {
        when(serverApiClient.fetchTree("Movies", "The_Rock", null))
                .thenReturn(new TreeResponse(List.of("extras"), List.of("movie.mkv")));
        when(serverApiClient.fetchTree("Movies", "The_Rock/extras", null))
                .thenReturn(new TreeResponse(List.of(), List.of("deleted_scene.mkv")));

        List<String> result = downloadService.collectDescendantFiles("Movies", "The_Rock", null);

        assertThat(result).containsExactlyInAnyOrder("movie.mkv", "extras/deleted_scene.mkv");
    }

    @Test
    void collectDescendantFiles_rootDir_usesEmptySubPath() {
        when(serverApiClient.fetchTree("Movies", "", null))
                .thenReturn(new TreeResponse(List.of(), List.of("readme.txt")));

        List<String> result = downloadService.collectDescendantFiles("Movies", "", null);

        assertThat(result).containsExactly("readme.txt");
    }

    // ── download (public entry point) ────────────────────────────────────────

    @Test
    void download_singleFile_savesFlatUnderDownloadsFolder() {
        fakeServer.respondWith("Movies/The_Rock/movie.mkv", "bytes".getBytes(StandardCharsets.UTF_8));

        int count = downloadService.download("Movies", "The_Rock/movie.mkv", true);

        assertThat(count).isEqualTo(1);
        assertThat(Files.exists(tempDir.resolve("downloads/movie.mkv"))).isTrue();
    }

    @Test
    void download_subdir_recreatesStructureUnderItemName() {
        when(rootDirService.getPasswordHash("Movies")).thenReturn(null);
        when(serverApiClient.fetchTree("Movies", "The_Rock", null))
                .thenReturn(new TreeResponse(List.of(), List.of("movie.mkv")));
        fakeServer.respondWith("Movies/The_Rock/movie.mkv", "bytes".getBytes(StandardCharsets.UTF_8));

        int count = downloadService.download("Movies", "The_Rock", false);

        assertThat(count).isEqualTo(1);
        assertThat(Files.exists(tempDir.resolve("downloads/The_Rock/movie.mkv"))).isTrue();
    }

    @Test
    void download_rootDir_usesRootDirNameAsDestinationFolder() {
        when(rootDirService.getPasswordHash("Movies")).thenReturn(null);
        when(serverApiClient.fetchTree("Movies", "", null))
                .thenReturn(new TreeResponse(List.of(), List.of("readme.txt")));
        fakeServer.respondWith("Movies/readme.txt", "bytes".getBytes(StandardCharsets.UTF_8));

        int count = downloadService.download("Movies", "", false);

        assertThat(count).isEqualTo(1);
        assertThat(Files.exists(tempDir.resolve("downloads/Movies/readme.txt"))).isTrue();
    }

    // ── Fake server ───────────────────────────────────────────────────────────

    /**
     * Minimal stand-in for the server's {@code FileSocketService}: accepts connections in a
     * loop, reads the clientId frame and a FILE (0x02) request, and replies with a
     * pre-registered response for the requested qualified path (or {@code -1L} if none was
     * registered).
     */
    private static final class FakeFileServer {
        private final ServerSocket serverSocket;
        private final Map<String, byte[]> responses = new HashMap<>();
        private volatile boolean running = true;

        FakeFileServer() throws Exception {
            serverSocket = new ServerSocket(0);
        }

        int port() {
            return serverSocket.getLocalPort();
        }

        void respondWith(String qualifiedPath, byte[] content) {
            responses.put(qualifiedPath, content);
        }

        void respondNotFound(String qualifiedPath) {
            responses.put(qualifiedPath, null);
        }

        void start() {
            Thread.ofPlatform().daemon(true).start(() -> {
                while (running) {
                    try (Socket client = serverSocket.accept();
                         var in = new DataInputStream(client.getInputStream());
                         var out = new DataOutputStream(client.getOutputStream())) {

                        int idLen = in.readInt();
                        in.readNBytes(idLen); // clientId — not asserted here

                        byte type = in.readByte();
                        if (type != 0x02) continue; // only FILE requests supported

                        int pathLen = in.readInt();
                        String qualifiedPath = new String(in.readNBytes(pathLen), StandardCharsets.UTF_8);

                        byte[] content = responses.get(qualifiedPath);
                        if (content == null) {
                            out.writeLong(-1L);
                        } else {
                            out.writeLong(content.length);
                            out.write(content);
                        }
                        out.flush();
                    } catch (Exception e) {
                        // Socket closed during shutdown — expected, stop looping
                        break;
                    }
                }
            });
        }

        void stop() throws Exception {
            running = false;
            serverSocket.close();
        }
    }
}
