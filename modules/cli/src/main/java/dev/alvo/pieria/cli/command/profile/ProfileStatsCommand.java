package dev.alvo.pieria.cli.command.profile;

import dev.alvo.pieria.api.response.ProfileStatsResponse;
import dev.alvo.pieria.api.response.ProfileStatsResponse.ProfileImpact;
import dev.alvo.pieria.api.response.ProfileStatsResponse.ProfileSpend;
import dev.alvo.pieria.api.response.ProfileStatsResponse.ProfileSpend.TierSpend;
import dev.alvo.pieria.client.ProfileClient;
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
  protected int run(ProfileClient client) {
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
    renderSpend(s.spend());
    return 0;
  }

  /**
   * The "Pieria impact" panel: a lifetime, relative (chars/4) estimate of the tokens saved by
   * answering from memory instead of re-reading the source material each answer was distilled from.
   * Omitted entirely for an older daemon that does not return the block.
   */
  private void renderImpact(ProfileImpact impact) {
    if (impact == null) {
      return;
    }
    log.info("");
    log.info("Pieria impact");
    line("Recalls served", Long.toString(impact.recalls()));
    line("Est. tokens saved",
      "~" + humanize(impact.tokensSaved()) + "   (vs. re-reading the source behind each answer)");

    if (impact.contextWindowTokens() > 0) {
      double windows = impact.tokensSaved() / (double) impact.contextWindowTokens();
      line("  ≈ context windows", String.format(Locale.ROOT, "%.1f  (%s)",
        windows, humanize(impact.contextWindowTokens())));
    }
    if (impact.pricePerMillionTokens() > 0.0) {
      double cost = impact.tokensSaved() / 1_000_000.0 * impact.pricePerMillionTokens();
      line("  ≈ cost saved", String.format(Locale.ROOT, "$%.2f", cost));
    }
    if (impact.tokensStored() > 0) {
      double ratio = impact.tokensIngested() / (double) impact.tokensStored();
      line("Ingest compression", String.format(Locale.ROOT, "%s → %s tokens  (%.1f×)",
        humanize(impact.tokensIngested()), humanize(impact.tokensStored()), ratio));
    }
  }

  /**
   * The "Inference spend" panel: the real provider tokens Pieria spent running the pipeline, broken
   * down by model tier, with a per-million input/output cost estimate. The cost line shows only when
   * at least one tier price is configured; the whole panel is omitted when nothing was spent or an
   * older daemon does not return the block.
   */
  private void renderSpend(ProfileSpend spend) {
    if (spend == null || spend.tiers() == null || spend.tiers().isEmpty()) {
      return;
    }
    log.info("");
    log.info("Inference spend");
    for (TierSpend t : spend.tiers()) {
      line("  " + t.tier(), String.format(Locale.ROOT, "prompt %s   out %s   (%s calls)",
        humanize(t.promptTokens()), humanize(t.completionTokens()), humanize(t.calls())));
    }
    long total = spend.totalPromptTokens() + spend.totalCompletionTokens();
    line("total", "~" + humanize(total) + " tokens");
    if (spend.costAvailable()) {
      line("  ≈ cost spent", String.format(Locale.ROOT, "$%.2f", spend.totalCostUsd()));
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
