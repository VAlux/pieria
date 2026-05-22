package dev.alvo.pieria.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Content-addressed identifiers (SPEC 6.1): {@code SHA-256(sessionId + role + content)} truncated
 * to 128 bits, hex-encoded. Re-ingesting the same conversation yields identical ids, making
 * inserts idempotent via {@code INSERT OR IGNORE}.
 */
public final class ContentId {

  private ContentId() {
  }

  /**
   * Id for a message: hashes session, role, and content.
   */
  public static String forMessage(String sessionId, String role, String content) {
    return hash128(nullToEmpty(sessionId), nullToEmpty(role), nullToEmpty(content));
  }

  /**
   * Id for a memory: hashes session, type, and canonical content. Distinct content or type
   * produces a distinct id; identical extracted memories collapse to one row.
   */
  public static String forMemory(String sessionId, MemoryType type, String content) {
    return hash128(nullToEmpty(sessionId), type.wire(), nullToEmpty(content));
  }

  private static String hash128(String... parts) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      for (String part : parts) {
        digest.update(part.getBytes(StandardCharsets.UTF_8));
      }
      byte[] full = digest.digest();
      // Truncate to the first 128 bits (16 bytes) and hex-encode.
      StringBuilder hex = new StringBuilder(32);
      for (int i = 0; i < 16; i++) {
        hex.append(Character.forDigit((full[i] >> 4) & 0xF, 16));
        hex.append(Character.forDigit(full[i] & 0xF, 16));
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }
}
