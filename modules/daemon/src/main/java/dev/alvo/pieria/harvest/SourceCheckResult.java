package dev.alvo.pieria.harvest;

import dev.alvo.pieria.harvester.protocol.ErrorClass;

/** Whether a source's config and credentials work. The message never contains secret values. */
public record SourceCheckResult(boolean ok, ErrorClass errorClass, String message) {

  static SourceCheckResult passed() {
    return new SourceCheckResult(true, null, null);
  }
}
