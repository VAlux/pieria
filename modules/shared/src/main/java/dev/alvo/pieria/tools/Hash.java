package dev.alvo.pieria.tools;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class Hash {
  private Hash() {

  }

  public static String hash128(String... parts) {
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
}
