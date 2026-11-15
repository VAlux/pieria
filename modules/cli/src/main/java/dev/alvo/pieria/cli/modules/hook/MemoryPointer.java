package dev.alvo.pieria.cli.modules.hook;

import dev.alvo.pieria.api.response.ProfileStatsResponse;
import dev.alvo.pieria.client.exception.DaemonNotFoundException;
import dev.alvo.pieria.tools.RelativeTime;

import java.time.Duration;
import java.time.Instant;

/**
 * Renders the session-open pointer: how much the profile holds, how fresh it is, and what to call.
 */
public final class MemoryPointer {

  private static final Duration TIMEOUT = Duration.ofSeconds(5);

  private MemoryPointer() {
  }

  public static HookOutcome render(HookContext ctx) {
    if (!ctx.health().reachable()) {
      return new HookOutcome.Skipped("daemon not reachable at " + ctx.daemonUrl());
    }

    try {
      String profile = ctx.profile();
      ProfileStatsResponse stats = ctx.profiles().stats(profile, TIMEOUT);
      // An older daemon reports no subtree total; the profile's own count stands in for it.
      long inSubProfiles = Math.max(0, stats.subtreeActive() - stats.totalActive());
      if (stats.totalActive() + inSubProfiles <= 0) {
        return new HookOutcome.Skipped("no memories yet for profile " + profile);
      }

      return new HookOutcome.Ok(text(profile, stats.totalActive(), inSubProfiles, stats.subProfiles(),
        stats.lastMemoryAt(), Instant.now()));
    } catch (DaemonNotFoundException e) {
      // A profile the daemon has never seen holds nothing to point at — the same situation as an
      // empty one, and the normal state of a repository on its first session. Not worth a warning.
      return new HookOutcome.Skipped("no profile yet for " + ctx.profile());
    } catch (RuntimeException e) {
      return new HookOutcome.Failed("pointer failed: " + e.getMessage());
    }
  }

  /**
   * The injected line. Names both tools and says what memory holds that the repository's files do
   * not — steering recall toward decisions and gotchas is the point of having dropped the
   * architecture primer.
   */
  static String text(String profile, long total, Instant lastMemoryAt, Instant now) {
    return text(profile, total, 0, 0, lastMemoryAt, now);
  }

  /**
   * As above, also counting what the profile's sub-profiles hold, since a recall on the profile reads
   * those too.
   */
  static String text(String profile, long total, long inSubProfiles, int subProfiles, Instant lastMemoryAt,
                     Instant now) {
    String count = total + (total == 1 ? " memory" : " memories")
      + (inSubProfiles <= 0 ? "" : " (+" + inSubProfiles + " in " + subProfiles
        + (subProfiles == 1 ? " sub-profile)" : " sub-profiles)"));
    String freshness = lastMemoryAt == null ? "" : " (latest " + RelativeTime.since(lastMemoryAt, now) + ")";

    return """
      [pieria] %s for profile "%s"%s. \
      Call the pieria `recall` tool at the start of a task - it holds prior decisions, rejected approaches, \
      and gotchas that aren't in the repo's files. \
      Use `remember` to store durable findings."""
      .formatted(count, profile, freshness);
  }
}
