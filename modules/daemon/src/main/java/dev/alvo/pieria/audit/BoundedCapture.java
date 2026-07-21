package dev.alvo.pieria.audit;

import dev.alvo.pieria.tools.Hash;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Captures a prefix while counting and hashing every byte that passes through it. */
final class BoundedCapture {
  private final int limit;
  private final ByteArrayOutputStream prefix;
  private final MessageDigest digest = Hash.sha256Digest();
  private long bytes;
  private CapturedPayload snapshot;

  BoundedCapture(int limit) {
    this.limit = Math.max(0, limit);
    this.prefix = new ByteArrayOutputStream(Math.min(this.limit, 8192));
  }

  void accept(int value) {
    byte b = (byte) value;
    digest.update(b);
    bytes++;
    if (prefix.size() < limit) {
      prefix.write(b);
    }
  }

  void accept(byte[] data, int offset, int length) {
    if (length <= 0) {
      return;
    }
    digest.update(data, offset, length);
    bytes += length;
    int remaining = limit - prefix.size();
    if (remaining > 0) {
      prefix.write(data, offset, Math.min(remaining, length));
    }
  }

  CapturedPayload snapshot() {
    if (snapshot == null) {
      snapshot = new CapturedPayload(prefix.toString(StandardCharsets.UTF_8), bytes,
        Hash.hex(digest.digest()), bytes > limit);
    }
    return snapshot;
  }
}
