package com.example.cdq.rag.lifecycle;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class DocumentHasher {

    private DocumentHasher() {}

    /**
     * Normalizes raw document content so that equivalent documents (differing only in
     * line endings or trailing whitespace) produce the same hash.
     * Normalization: CRLF and CR → LF; trailing whitespace stripped from end of file.
     */
    public static String normalize(String raw) {
        return raw
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .stripTrailing();
    }

    /** Returns the SHA-256 hex digest (64 lowercase characters) of the given string (UTF-8). */
    public static String sha256Hex(String content) {
        return sha256Hex(content.getBytes(StandardCharsets.UTF_8));
    }

    /** Returns the SHA-256 hex digest (64 lowercase characters) of the given bytes. */
    public static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
