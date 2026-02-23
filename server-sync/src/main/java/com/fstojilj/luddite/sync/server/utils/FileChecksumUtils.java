package com.fstojilj.luddite.sync.server.utils;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class FileChecksumUtils {

    private static final int BUFFER_SIZE = 8192;

    private FileChecksumUtils() {
        // Utility class - prevent instantiation
    }

    public static String calculateFileChecksum(Path filePath) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            try (InputStream fis = Files.newInputStream(filePath);
                 DigestInputStream dis = new DigestInputStream(fis, digest)) {

                // Read the file content in chunks (efficient for large photos)
                byte[] buffer = new byte[BUFFER_SIZE];
                while (dis.read(buffer) != -1) {
                    // DigestInputStream automatically updates the digest
                }
            }

            // Convert byte array to hex string
            byte[] hashBytes = digest.digest();
            StringBuilder hexString = new StringBuilder();
            for (byte b : hashBytes) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();

        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        } catch (IOException e) {
            throw new UncheckedIOException("Error reading file for checksum calculation: " + filePath, e);
        }
    }
}
