package dev.alvo.pieria.profile;

import dev.alvo.pieria.domain.memory.MemorySearchQuery;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Opaque wire form of a memory-list position: base64url of the stored timestamp and id. */
public final class MemoryCursor {

  private MemoryCursor() {
  }

  public static String encode(MemorySearchQuery.Cursor cursor) {
    if (cursor == null) return null;
    String raw = cursor.sortAt() + "\n" + cursor.id();
    return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }

  public static MemorySearchQuery.Cursor decode(String value) {
    if (value == null || value.isBlank()) return null;
    try {
      String raw = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
      int newline = raw.indexOf('\n');
      if (newline <= 0 || newline == raw.length() - 1) throw new IllegalArgumentException();
      return new MemorySearchQuery.Cursor(raw.substring(0, newline), raw.substring(newline + 1));
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("invalid memory cursor");
    }
  }
}
