package com.fstojilj.luddite.sync.client.service;

import com.fstojilj.luddite.sync.common.dto.TreeEntry;
import com.fstojilj.luddite.sync.common.dto.TreeResponse;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import java.time.Duration;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
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
        ReflectionTestUtils.setField(downloadService, "readTimeoutMs", 500);
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

    @Test
    void downloadSingleFile_multiBufferFile_arrivesIntact() throws Exception {
        byte[] content = patterned(3 * 1024 * 1024 + 17);
        fakeServer.respondWith("Movies/movie.mkv", content);

        Path destination = tempDir.resolve("downloads/movie.mkv");
        boolean result = downloadService.downloadSingleFile("Movies/movie.mkv", destination);

        assertThat(result).isTrue();
        assertThat(Files.readAllBytes(destination)).isEqualTo(content);
        assertThat(Files.exists(partialOf(destination))).isFalse();
    }

    @Test
    void downloadSingleFile_connectionClosedEarly_failsAndRemovesPartialFile() {
        byte[] content = patterned(2 * 1024 * 1024);
        fakeServer.respondTruncated("Movies/movie.mkv", content, content.length + 4096);

        Path destination = tempDir.resolve("downloads/movie.mkv");
        boolean result = assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> downloadService.downloadSingleFile("Movies/movie.mkv", destination));

        assertThat(result).isFalse();
        assertThat(Files.exists(destination)).isFalse();
        assertThat(Files.exists(partialOf(destination))).isFalse();
    }

    @Test
    void downloadSingleFile_stalledConnection_failsAfterReadTimeout() throws Exception {
        byte[] content = patterned(1024);
        CountDownLatch release = fakeServer.respondThenStall("Movies/movie.mkv", content, content.length + 1);

        Path destination = tempDir.resolve("downloads/movie.mkv");
        try {
            boolean result = assertTimeoutPreemptively(Duration.ofSeconds(5),
                    () -> downloadService.downloadSingleFile("Movies/movie.mkv", destination));

            assertThat(result).isFalse();
            assertThat(Files.exists(destination)).isFalse();
            assertThat(Files.exists(partialOf(destination))).isFalse();
        } finally {
            release.countDown();
        }
    }

    @Test
    void downloadSingleFile_failure_leavesPreviousFileUntouched() throws Exception {
        Path destination = tempDir.resolve("downloads/movie.mkv");
        Files.createDirectories(destination.getParent());
        Files.writeString(destination, "old content");
        fakeServer.respondTruncated("Movies/movie.mkv", patterned(100), 200);

        boolean result = downloadService.downloadSingleFile("Movies/movie.mkv", destination);

        assertThat(result).isFalse();
        assertThat(Files.readString(destination)).isEqualTo("old content");
    }

    // ── progress reporting ────────────────────────────────────────────────────

    @Test
    void downloadSingleFile_reportsProgressEveryStepAndOnCompletion() {
        long size = 2 * DownloadService.PROGRESS_STEP_BYTES + 12345;
        fakeServer.respondWith("Movies/movie.mkv", patterned((int) size));
        List<long[]> reports = new ArrayList<>();

        boolean result = downloadService.downloadSingleFile("Movies/movie.mkv",
                tempDir.resolve("downloads/movie.mkv"),
                (path, done, total) -> {
                    assertThat(path).isEqualTo("Movies/movie.mkv");
                    reports.add(new long[]{done, total});
                });

        assertThat(result).isTrue();
        assertThat(reports).extracting(r -> r[1]).containsOnly(size);
        assertThat(reports).extracting(r -> r[0]).containsExactly(
                DownloadService.PROGRESS_STEP_BYTES, 2 * DownloadService.PROGRESS_STEP_BYTES, size);
    }

    @Test
    void downloadSingleFile_smallFile_reportsCompletionOnce() {
        fakeServer.respondWith("Movies/movie.mkv", "bytes".getBytes(StandardCharsets.UTF_8));
        List<long[]> reports = new ArrayList<>();

        downloadService.downloadSingleFile("Movies/movie.mkv",
                tempDir.resolve("downloads/movie.mkv"),
                (path, done, total) -> reports.add(new long[]{done, total}));

        assertThat(reports).hasSize(1);
        assertThat(reports.get(0)).containsExactly(5, 5);
    }

    @Test
    void download_passesListenerThroughForEveryFile() {
        when(rootDirService.getPasswordHash("Movies")).thenReturn(null);
        when(serverApiClient.fetchTree("Movies", "", null))
                .thenReturn(tree(List.of(), List.of("a.txt", "b.txt")));
        fakeServer.respondWith("Movies/a.txt", "aa".getBytes(StandardCharsets.UTF_8));
        fakeServer.respondWith("Movies/b.txt", "bbb".getBytes(StandardCharsets.UTF_8));
        List<String> reported = new ArrayList<>();

        int count = downloadService.download("Movies", "", false,
                (path, done, total) -> reported.add(path + ":" + done + "/" + total));

        assertThat(count).isEqualTo(2);
        assertThat(reported).containsExactlyInAnyOrder("Movies/a.txt:2/2", "Movies/b.txt:3/3");
    }

    private static Path partialOf(Path destination) {
        return destination.resolveSibling(destination.getFileName() + DownloadService.PARTIAL_SUFFIX);
    }

    private static byte[] patterned(int length) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = (byte) (i * 31 + 7);
        }
        return bytes;
    }

    private static TreeResponse tree(List<String> dirs, List<String> files) {
        return new TreeResponse(
                dirs.stream().map(d -> new TreeEntry(d, 0)).toList(),
                files.stream().map(f -> new TreeEntry(f, 0)).toList());
    }

    // ── collectDescendantFiles ────────────────────────────────────────────────

    @Test
    void collectDescendantFiles_walksNestedTreeAndPreservesStructure() {
        when(serverApiClient.fetchTree("Movies", "The_Rock", null))
                .thenReturn(tree(List.of("extras"), List.of("movie.mkv")));
        when(serverApiClient.fetchTree("Movies", "The_Rock/extras", null))
                .thenReturn(tree(List.of(), List.of("deleted_scene.mkv")));

        List<String> result = downloadService.collectDescendantFiles("Movies", "The_Rock", null);

        assertThat(result).containsExactlyInAnyOrder("movie.mkv", "extras/deleted_scene.mkv");
    }

    @Test
    void collectDescendantFiles_rootDir_usesEmptySubPath() {
        when(serverApiClient.fetchTree("Movies", "", null))
                .thenReturn(tree(List.of(), List.of("readme.txt")));

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
                .thenReturn(tree(List.of(), List.of("movie.mkv")));
        fakeServer.respondWith("Movies/The_Rock/movie.mkv", "bytes".getBytes(StandardCharsets.UTF_8));

        int count = downloadService.download("Movies", "The_Rock", false);

        assertThat(count).isEqualTo(1);
        assertThat(Files.exists(tempDir.resolve("downloads/The_Rock/movie.mkv"))).isTrue();
    }

    @Test
    void download_rootDir_usesRootDirNameAsDestinationFolder() {
        when(rootDirService.getPasswordHash("Movies")).thenReturn(null);
        when(serverApiClient.fetchTree("Movies", "", null))
                .thenReturn(tree(List.of(), List.of("readme.txt")));
        fakeServer.respondWith("Movies/readme.txt", "bytes".getBytes(StandardCharsets.UTF_8));

        int count = downloadService.download("Movies", "", false);

        assertThat(count).isEqualTo(1);
        assertThat(Files.exists(tempDir.resolve("downloads/Movies/readme.txt"))).isTrue();
    }

    @Test
    void download_refreshesSocketPortBeforeConnecting() {
        when(socketFactory.getServerPort()).thenReturn(8888);
        when(serverApiClient.fetchSocketPort(8888)).thenReturn(18888);
        fakeServer.respondWith("Movies/movie.mkv", "bytes".getBytes(StandardCharsets.UTF_8));

        downloadService.download("Movies", "movie.mkv", true);

        verify(socketFactory).setServerPort(18888);
    }

    // ── Fake server ───────────────────────────────────────────────────────────

    /**
     * Minimal stand-in for the server's {@code FileSocketService}: accepts connections in a
     * loop, reads the clientId frame and a DOWNLOAD_FILE (0x02) request, and replies with a
     * pre-registered response for the requested qualified path (or {@code -1L} if none was
     * registered).
     */
    private static final class FakeFileServer {
        /** What the server sends for one path: the size header, the bytes, then optionally a stall. */
        private record Response(long declaredSize, byte[] content, CountDownLatch stallUntil) { }

        private final ServerSocket serverSocket;
        private final Map<String, Response> responses = new HashMap<>();
        private volatile boolean running = true;

        FakeFileServer() throws Exception {
            serverSocket = new ServerSocket(0);
        }

        int port() {
            return serverSocket.getLocalPort();
        }

        void respondWith(String qualifiedPath, byte[] content) {
            responses.put(qualifiedPath, new Response(content.length, content, null));
        }

        void respondNotFound(String qualifiedPath) {
            responses.put(qualifiedPath, null);
        }

        /** Declares {@code declaredSize} bytes but sends only {@code content} and closes. */
        void respondTruncated(String qualifiedPath, byte[] content, long declaredSize) {
            responses.put(qualifiedPath, new Response(declaredSize, content, null));
        }

        /**
         * Declares {@code declaredSize} bytes, sends {@code content}, then keeps the socket
         * open without sending anything until the returned latch is released.
         */
        CountDownLatch respondThenStall(String qualifiedPath, byte[] content, long declaredSize) {
            CountDownLatch latch = new CountDownLatch(1);
            responses.put(qualifiedPath, new Response(declaredSize, content, latch));
            return latch;
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
                        if (type != 0x02) continue; // only DOWNLOAD_FILE requests supported

                        int pathLen = in.readInt();
                        String qualifiedPath = new String(in.readNBytes(pathLen), StandardCharsets.UTF_8);

                        Response response = responses.get(qualifiedPath);
                        if (response == null) {
                            out.writeLong(-1L);
                        } else {
                            out.writeLong(response.declaredSize());
                            out.write(response.content());
                        }
                        out.flush();
                        if (response != null && response.stallUntil() != null) {
                            response.stallUntil().await(10, TimeUnit.SECONDS);
                        }
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
