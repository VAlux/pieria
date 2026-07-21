package dev.alvo.pieria.tools;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class Hash {
  private Hash() {

  }

  /**
   * SHA-256 of the concatenated UTF-8 {@code parts}, truncated to the first 128 bits (16 bytes)
   * and hex-encoded.
   */
  public static String hash128(String... parts) {
    MessageDigest digest = sha256Digest();
    for (String part : parts) {
      digest.update(part.getBytes(StandardCharsets.UTF_8));
    }
    return hex(digest.digest(), 16);
  }

  /**
   * Full, untruncated SHA-256 of {@code data}, hex-encoded (64 characters).
   */
  public static String sha256Hex(byte[] data) {
    return hex(sha256Digest().digest(data), 32);
  }

  /**
   * Full, untruncated SHA-256 of {@code path}'s contents, hex-encoded (64 characters).
   */
  public static String sha256Hex(Path path) {
    try {
      return sha256Hex(Files.readAllBytes(path));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** A fresh SHA-256 digest for streaming callers. */
  public static MessageDigest sha256Digest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  /** Hex-encode every byte in {@code bytes}. */
  public static String hex(byte[] bytes) {
    return hex(bytes, bytes.length);
  }

  private static String hex(byte[] digest, int byteCount) {
    StringBuilder hex = new StringBuilder(byteCount * 2);
    for (int i = 0; i < byteCount; i++) {
      hex.append(Character.forDigit((digest[i] >> 4) & 0xF, 16));
      hex.append(Character.forDigit(digest[i] & 0xF, 16));
    }
    return hex.toString();
  }
}
