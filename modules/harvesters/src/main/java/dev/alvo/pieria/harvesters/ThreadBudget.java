package dev.alvo.pieria.harvesters;

import dev.alvo.pieria.harvester.protocol.ProtocolLimits;

/** The text a thread may still hold, so a long one is cut at the protocol's limit rather than refused. */
public final class ThreadBudget {

  private long remaining = ProtocolLimits.MAX_TEXT_CHARS;

  /** As much of {@code text} as still fits. */
  public String take(String text) {
    String kept = text.length() > remaining ? text.substring(0, (int) remaining) : text;
    remaining -= kept.length();
    return kept;
  }

  public boolean spent() {
    return remaining <= 0;
  }
}
