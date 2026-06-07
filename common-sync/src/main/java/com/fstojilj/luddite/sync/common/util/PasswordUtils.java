package com.fstojilj.luddite.sync.common.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Utility for hashing passwords with SHA-256 before storage or comparison.
 * Both the server (at creation time) and the client (before sending over the wire)
 * use this so the raw password is never persisted or transmitted.
 */
public final class PasswordUtils {

    private PasswordUtils() {
    }

    /**
     * Returns the SHA-256 hex digest of the given plain-text password.
     *
     * @param plaintext the raw password
     * @return lowercase hex string of the SHA-256 digest
     */
    public static String hash(String plaintext) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(plaintext.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}

