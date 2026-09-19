package com.fstojilj.luddite.sync.client.service;

import com.fstojilj.luddite.sync.common.dto.TreeEntry;
import com.fstojilj.luddite.sync.common.dto.TreeResponse;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Handles one-time downloads of a root directory, subdirectory, or single file, triggered
 * from the client UI's {@code [ DOWNLOAD ]} button.
 *
 * <p>Unlike continuous directory sync ({@link ClientSyncService}), each download opens its
 * own short-lived socket connection via {@link ClientSocketFactory} and closes it once the
 * transfer completes — it never touches the persistent sync connection or its poll loop.
 *
 * <h2>Wire protocol (DOWNLOAD_FILE request, {@code 0x02})</h2>
 * <pre>
 * On connect:
 *   Client → [4b idLen][clientId (UTF-8)]
 * Per file:
 *   Client → [1b 0x02][4b pathLen][qualifiedPath (UTF-8, "dirName/relativePath")]
 *   Server → [8b fileSize (-1L = not found/denied)][fileSize bytes if ≥ 0]
 * </pre>
 *
 * <p>The server-side handler for this request type ({@code FileSocketService.handleDownloadFile})
 * is already fully implemented — this class is purely client-side wiring.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DownloadService {

    private static final byte DOWNLOAD_FILE = 0x02;

    /** Size of the read/write buffer used to stream file bytes from the socket to disk. */
    private static final int BUFFER_BYTES = 1024 * 1024;

    /** Progress is reported to the listener every time this many more bytes have landed on disk. */
    static final long PROGRESS_STEP_BYTES = 16L * 1024 * 1024;

    /** Suffix of the temporary file a download is written to until it is complete. */
    static final String PARTIAL_SUFFIX = ".part";

    /**
     * Receives progress updates while a file is being downloaded: one call per
     * {@value #PROGRESS_STEP_BYTES} bytes received, plus one when the file is complete.
     */
    @FunctionalInterface
    public interface ProgressListener {
        ProgressListener NONE = (qualifiedPath, bytesDone, totalBytes) -> { };

        void onProgress(String qualifiedPath, long bytesDone, long totalBytes);
    }

    private final ServerApiClient serverApiClient;
    private final RootDirService rootDirService;
    private final ClientIdService clientIdService;
    private final ClientSocketFactory socketFactory;

    @Value("${sync.client.mirror-dir}")
    private String mirrorDirPath;

    /**
     * How long a download socket may sit without receiving any bytes before the transfer
     * is abandoned, so a dead connection fails instead of blocking the caller forever.
     */
    @Value("${sync.client.download-read-timeout-ms:60000}")
    private int readTimeoutMs;

    /**
     * Downloads a root directory, subdirectory, or single file from the server into the
     * local downloads folder ({@code ${sync.client.mirror-dir}/downloads/...}).
     *
     * <p>A single file is saved flat as {@code downloads/{filename}}. A subdirectory or root
     * directory is saved under {@code downloads/{name}/...}, preserving its internal
     * structure. In both cases, any existing file at the destination is overwritten.
     *
     * @param dirName root directory name the item belongs to
     * @param subPath path relative to the root dir ({@code ""} to download the root dir
     *                itself); for a file, this is the file's own relative path
     * @param isFile  {@code true} if {@code subPath} identifies a single file rather than
     *                a directory
     * @return the number of files successfully downloaded
     */
    public int download(String dirName, String subPath, boolean isFile) {
        return download(dirName, subPath, isFile, ProgressListener.NONE);
    }

    /**
     * Same as {@link #download(String, String, boolean)}, reporting per-file progress to
     * {@code listener} as bytes arrive. The listener is invoked on the calling thread.
     */
    public int download(String dirName, String subPath, boolean isFile, ProgressListener listener) {
        refreshSocketPort();
        Path downloadsRoot = Path.of(mirrorDirPath, "downloads");

        if (isFile) {
            String qualifiedPath = dirName + "/" + subPath;
            Path destination = downloadsRoot.resolve(lastSegment(subPath));
            boolean ok = downloadSingleFile(qualifiedPath, destination, listener);
            log.info("Download of file '{}' {}", qualifiedPath, ok ? "succeeded" : "failed");
            return ok ? 1 : 0;
        }

        String passwordHash = rootDirService.getPasswordHash(dirName);
        String itemName = subPath.isEmpty() ? dirName : lastSegment(subPath);
        Path destinationRoot = downloadsRoot.resolve(itemName);

        List<String> relativeFiles = collectDescendantFiles(dirName, subPath, passwordHash);
        int successCount = 0;
        for (String relFile : relativeFiles) {
            String qualifiedPath = subPath.isEmpty() ? dirName + "/" + relFile : dirName + "/" + subPath + "/" + relFile;
            Path destination = destinationRoot.resolve(adjustPathSeparatorsForOS(relFile));
            if (downloadSingleFile(qualifiedPath, destination, listener)) {
                successCount++;
            }
        }
        log.info("Download of '{}/{}' complete: {}/{} file(s) succeeded",
                dirName, subPath, successCount, relativeFiles.size());
        return successCount;
    }

    /**
     * Re-discovers the server's socket port over REST before opening a download connection.
     *
     * <p>{@link ClientSyncService} only reaches its own port discovery once the client has at
     * least one subscribed directory, so a download that precedes any subscription would
     * otherwise still be aimed at the bootstrap port.
     */
    private void refreshSocketPort() {
        socketFactory.setServerPort(serverApiClient.fetchSocketPort(socketFactory.getServerPort()));
    }

    /**
     * Recursively walks the server's directory tree under {@code subPath}, returning the
     * relative path (relative to {@code subPath}, using {@code '/'} separators) of every
     * descendant file, preserving nested subdirectory structure.
     *
     * @param dirName      root directory name
     * @param subPath      path relative to the root dir to walk from ({@code ""} for the root)
     * @param passwordHash password hash for private dirs, or {@code null} for public dirs
     * @return relative paths of all descendant files under {@code subPath}
     */
    public List<String> collectDescendantFiles(String dirName, String subPath, String passwordHash) {
        List<String> result = new ArrayList<>();
        collectDescendantFiles(dirName, subPath, passwordHash, "", result);
        return result;
    }

    private void collectDescendantFiles(String dirName, String subPath, String passwordHash,
                                        String relativePrefix, List<String> result) {
        TreeResponse tree = serverApiClient.fetchTree(dirName, subPath, passwordHash);

        for (TreeEntry file : tree.files()) {
            result.add(relativePrefix.isEmpty() ? file.name() : relativePrefix + "/" + file.name());
        }

        for (TreeEntry dir : tree.childDirs()) {
            String childDir = dir.name();
            String childSubPath = subPath.isEmpty() ? childDir : subPath + "/" + childDir;
            String childPrefix = relativePrefix.isEmpty() ? childDir : relativePrefix + "/" + childDir;
            collectDescendantFiles(dirName, childSubPath, passwordHash, childPrefix, result);
        }
    }

    /**
     * Downloads a single file over a dedicated one-off socket connection (independent of
     * the persistent sync connection), writing it to {@code destination} and overwriting
     * any existing file there.
     *
     * <p>Bytes are streamed into {@code destination + ".part"} and the partial file is only
     * renamed to {@code destination} once every byte has arrived, so an interrupted transfer
     * never leaves a truncated file that looks complete. On failure the partial file is removed.
     *
     * @param qualifiedPath qualified path of the form {@code "dirName/relativePath"}
     * @param destination   local destination path to write the file to
     * @return {@code true} if the file was found and downloaded successfully
     */
    public boolean downloadSingleFile(String qualifiedPath, Path destination) {
        return downloadSingleFile(qualifiedPath, destination, ProgressListener.NONE);
    }

    public boolean downloadSingleFile(String qualifiedPath, Path destination, ProgressListener listener) {
        Path partial = destination.resolveSibling(destination.getFileName() + PARTIAL_SUFFIX);
        try (Socket socket = socketFactory.connect()) {
            socket.setSoTimeout(readTimeoutMs);
            var out = new DataOutputStream(socket.getOutputStream());
            var in = new DataInputStream(socket.getInputStream());

            sendClientId(out);

            byte[] pathBytes = qualifiedPath.getBytes(StandardCharsets.UTF_8);
            out.writeByte(DOWNLOAD_FILE);
            out.writeInt(pathBytes.length);
            out.write(pathBytes);
            out.flush();

            long fileSize = in.readLong();
            if (fileSize < 0) {
                log.warn("Download failed — not found or access denied: '{}'", qualifiedPath);
                return false;
            }

            Files.createDirectories(destination.getParent());
            writeFileBytes(in, partial, fileSize, qualifiedPath, listener);
            Files.move(partial, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            log.debug("Downloaded '{}' ({} bytes) to '{}'", qualifiedPath, fileSize, destination);
            return true;
        } catch (Exception e) {
            log.warn("Download failed for '{}': {}", qualifiedPath, e.getMessage());
            deleteQuietly(partial);
            return false;
        }
    }

    /**
     * Sends the client's stable client ID, as required by the server immediately after
     * the socket connection is established.
     *
     * @param out the server output stream
     * @throws IOException if writing fails
     */
    private void sendClientId(DataOutputStream out) throws IOException {
        byte[] idBytes = clientIdService.getClientId().getBytes(StandardCharsets.UTF_8);
        out.writeInt(idBytes.length);
        out.write(idBytes);
        out.flush();
    }

    /**
     * Reads exactly {@code fileSize} bytes from {@code in} and writes them to {@code destination},
     * streaming through a fixed-size buffer so the file grows on disk as bytes arrive.
     *
     * @param in            the server input stream, positioned at the start of the file content
     * @param destination   local file path to write to
     * @param fileSize      number of bytes to read
     * @param qualifiedPath the file being downloaded, passed through to {@code listener}
     * @param listener      receives progress every {@value #PROGRESS_STEP_BYTES} bytes and on completion
     * @throws EOFException if the stream ends before {@code fileSize} bytes have been read
     * @throws IOException  if reading or writing fails
     */
    private void writeFileBytes(DataInputStream in, Path destination, long fileSize,
                                String qualifiedPath, ProgressListener listener) throws IOException {
        try (var fileOut = Files.newOutputStream(destination)) {
            byte[] buf = new byte[BUFFER_BYTES];
            long done = 0;
            long lastReported = 0;
            while (done < fileSize) {
                int toRead = (int) Math.min(buf.length, fileSize - done);
                int read = in.readNBytes(buf, 0, toRead);
                if (read < toRead) {
                    throw new EOFException("connection closed after " + (done + read) + " of " + fileSize + " bytes");
                }
                fileOut.write(buf, 0, read);
                done += read;
                if (done - lastReported >= PROGRESS_STEP_BYTES) {
                    lastReported = done;
                    log.debug("Downloading '{}': {}/{} bytes", qualifiedPath, done, fileSize);
                    listener.onProgress(qualifiedPath, done, fileSize);
                }
            }
            if (lastReported != fileSize) {
                listener.onProgress(qualifiedPath, fileSize, fileSize);
            }
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Could not remove partial download '{}': {}", path, e.getMessage());
        }
    }

    /**
     * Returns the last {@code '/'}-separated segment of {@code path}.
     *
     * @param path a relative path using {@code '/'} separators
     * @return the final path segment, or the whole path if it has no separator
     */
    private String lastSegment(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    /**
     * Converts {@code '/'}-separated relative paths from the wire into the local OS's
     * native separator, mirroring {@code ClientSyncService}'s equivalent adjustment for
     * continuously-synced files.
     *
     * @param relativePath a relative path using {@code '/'} separators
     * @return the path with separators adjusted for the current OS
     */
    private String adjustPathSeparatorsForOS(String relativePath) {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("windows")) {
            return relativePath.replace("/", "\\");
        }
        return relativePath.replace("\\", "/");
    }
}
