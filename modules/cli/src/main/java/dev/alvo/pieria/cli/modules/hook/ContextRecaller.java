package dev.alvo.pieria.cli.modules.hook;

import dev.alvo.pieria.api.request.RecallRequest;

import java.time.Duration;
import java.util.Optional;

/**
 * Fetches a ready-to-inject context block, including a health pre-flight: probing first keeps the
 * common "daemon down" case at ~2s instead of waiting out the recall timeout.
 *
 * <p>The daemon's {@code text/plain} recall always runs the EVIDENCE tier, so there is no mode to
 * pass — the old script's {@code fast: true} field had already been removed from
 * {@link RecallRequest} and was being silently ignored.
 */
public final class ContextRecaller {

  private static final Duration TIMEOUT = Duration.ofSeconds(8);

  private ContextRecaller() {
  }

  public static HookOutcome recall(HookContext ctx, String query, int limit) {
    if (query == null || query.isBlank()) {
      return new HookOutcome.Skipped("empty query");
    }
    if (!ctx.health().reachable()) {
      return new HookOutcome.Skipped("daemon not reachable at " + ctx.daemonUrl());
    }
    try {
      Optional<String> block =
        ctx.profiles().recallText(ctx.profile(), new RecallRequest(query, limit, null, null), TIMEOUT);
      return new HookOutcome.Ok(block.orElse(""));
    } catch (RuntimeException e) {
      return new HookOutcome.Failed("recall failed: " + e.getMessage());
    }
  }
}
