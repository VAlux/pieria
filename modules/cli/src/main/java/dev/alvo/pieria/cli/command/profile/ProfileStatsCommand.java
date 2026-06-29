package dev.alvo.pieria.cli.command.profile;

import dev.alvo.pieria.api.response.ProfileStatsResponse;
import dev.alvo.pieria.api.response.ProfileStatsResponse.ProfileImpact;
import dev.alvo.pieria.cli.modules.daemon.ProfileApiClient;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.util.Locale;
import java.util.Map;

/**
 * {@code pieria profile stats <name>} — print a per-profile snapshot: active memory totals, a
 * by-type breakdown, superseded count, distinct sessions, the createdAt range, and the
 * vectorization backlog.
 */
@Command(
  name = "stats",
  description = "Show statistics for a profile.",
  mixinStandardHelpOptions = true
)
public final class ProfileStatsCommand extends AbstractProfileCommand {

  @Parameters(index = "0", paramLabel = "<name>", description = "Profile name.")
  String name;

  private void line(String label, String value) {
    log.info(String.format("  %-22s %s", label + ":", value));
  }

  @Override
  protected int run(ProfileApiClient client) {
    ProfileStatsResponse s = client.stats(name);

    log.info("Profile: {}", s.name());
    line("Created", s.createdAt() == null ? "—" : s.createdAt().toString());
    line("Active memories", Long.toString(s.totalActive()));
    Map<String, Long> byType = s.byType();
    if (byType != null) {
      byType.forEach((type, count) -> line("  " + type, Long.toString(count)));
    }
    line("Superseded", Long.toString(s.superseded()));
    line("Sessions", Long.toString(s.sessions()));
    line("First memory", s.firstMemoryAt() == null ? "—" : s.firstMemoryAt().toString());
    line("Last memory", s.lastMemoryAt() == null ? "—" : s.lastMemoryAt().toString());
    line("Vectorization backlog",
      s.vectorizationBacklog() == null ? "unknown" : s.vectorizationBacklog().toString());
    renderImpact(s.impact());
    return 0;
  }

  /**
   * The "Pieria impact" panel: a lifetime, relative (chars/4) estimate of tokens saved by answering
   * from memory instead of re-feeding context. The evidence-only figure is the headline; the
   * naive-dump figure is shown as an explicit upper bound. Omitted entirely for an older daemon that
   * does not return the block.
   */
  private void renderImpact(ProfileImpact impact) {
    if (impact == null) {
      return;
    }
    log.info("");
    log.info("Pieria impact");
    line("Recalls served", Long.toString(impact.recalls()));
    line("Est. tokens saved", "~" + humanize(impact.tokensSavedEvidence()) + "   (evidence-only)");
    line("  upper bound", "~" + humanize(impact.tokensSavedNaive()) + "   (naive dump-everything baseline)");

    if (impact.contextWindowTokens() > 0) {
      double windows = impact.tokensSavedEvidence() / (double) impact.contextWindowTokens();
      line("  ≈ context windows", String.format(Locale.ROOT, "%.1f  (%s)",
        windows, humanize(impact.contextWindowTokens())));
    }
    if (impact.pricePerMillionTokens() > 0.0) {
      double cost = impact.tokensSavedEvidence() / 1_000_000.0 * impact.pricePerMillionTokens();
      line("  ≈ cost saved", String.format(Locale.ROOT, "$%.2f", cost));
    }
    if (impact.tokensStored() > 0) {
      double ratio = impact.tokensIngested() / (double) impact.tokensStored();
      line("Ingest compression", String.format(Locale.ROOT, "%s → %s tokens  (%.1f×)",
        humanize(impact.tokensIngested()), humanize(impact.tokensStored()), ratio));
    }
  }

  /** Compact human-readable token counts: 1_840_000 → "1.84M", 612_000 → "612K", 940 → "940". */
  private static String humanize(long value) {
    if (value < 1_000) {
      return Long.toString(value);
    }
    if (value < 1_000_000) {
      return String.format(Locale.ROOT, "%.0fK", value / 1_000.0);
    }
    return String.format(Locale.ROOT, "%.2fM", value / 1_000_000.0);
  }
}
