package dev.alvo.pieria.profile;

import dev.alvo.pieria.config.PieriaProperties;
import dev.alvo.pieria.domain.error.NotFoundException;
import dev.alvo.pieria.domain.profile.Profile;
import dev.alvo.pieria.domain.profile.ProfileCount;
import dev.alvo.pieria.domain.profile.ProfileStats;
import dev.alvo.pieria.domain.profile.ProfileUsage;
import dev.alvo.pieria.model.usage.InferenceTier;
import dev.alvo.pieria.model.usage.TierUsage;
import dev.alvo.pieria.storage.MemoryStore;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Aggregates a profile's stats, lifetime impact counters, and per-tier inference spend, costing
 * spend with the configured tier prices. Owns the pricing math the {@code /stats} endpoint used to
 * do inline.
 */
@Service
public class ProfileStatsService {

  private final MemoryStore store;
  private final PieriaProperties properties;

  public ProfileStatsService(MemoryStore store, PieriaProperties properties) {
    this.store = store;
    this.properties = properties;
  }

  public ProfileStatsView stats(String profileName) {
    Profile profile = store.findProfile(profileName).orElseThrow(() -> NotFoundException.profile(profileName));

    ProfileStats stats = store.profileStats(profile.id());
    Long backlog = store.vectorizationOutboxDepth().stream().boxed().findFirst().orElse(null);
    Tree tree = treeOf(profile, stats.totalActive());

    return new ProfileStatsView(
      profile.name(),
      profile.createdAt(),
      stats.totalActive(),
      stats.byType(),
      stats.superseded(),
      stats.sessions(),
      stats.firstMemoryAt(),
      stats.lastMemoryAt(),
      backlog,
      impactOf(store.usageStats(profile.id())),
      spendOf(store.inferenceUsage(profile.id())),
      tree.parent(), tree.subProfiles(), tree.subtreeActive());
  }

  private record Tree(String parent, int subProfiles, long subtreeActive) {
  }

  /**
   * Where the profile sits and what a recall on it reads: its own active memories plus those of
   * every profile below it. The listing is only read when there is a parent or a sub-profile.
   */
  private Tree treeOf(Profile profile, long ownActive) {
    List<String> subtree = store.profileSubtree(profile.id());
    if (subtree.size() == 1 && profile.parentId() == null) {
      return new Tree(null, 0, ownActive);
    }
    Map<String, ProfileCount> byId = new java.util.HashMap<>();
    store.listProfiles().forEach(count -> byId.put(count.profile().id(), count));
    long descendantsActive = subtree.stream().skip(1)
      .map(byId::get).filter(java.util.Objects::nonNull)
      .mapToLong(ProfileCount::activeCount).sum();
    ProfileCount own = byId.get(profile.id());
    return new Tree(own == null ? null : own.parentName(), subtree.size() - 1, ownActive + descendantsActive);
  }

  /**
   * Map the stored lifetime counters to the impact view, stamping the display knobs.
   */
  private ImpactView impactOf(ProfileUsage usage) {
    // Stats binds with @DefaultValue in production; guard null for tests that construct properties directly.
    PieriaProperties.Stats cfg = properties == null ? null : properties.stats();
    int window = cfg == null ? 200_000 : cfg.contextWindowTokens();
    double price = cfg == null ? 0.0 : cfg.pricePerMillionTokens();
    return new ImpactView(
      usage.recallCount(),
      usage.tokensSaved(),
      usage.tokensIngested(),
      usage.tokensStored(),
      window,
      price);
  }

  /**
   * Map the stored per-tier inference-spend counters to the spend view, costing each tier with its
   * configured input/output prices. Returns {@code null} when nothing has been spent yet so the
   * client renders no panel.
   */
  private SpendView spendOf(Map<InferenceTier, TierUsage> usage) {
    if (usage == null || usage.isEmpty()) {
      return null;
    }
    // Stats binds with @DefaultValue in production; guard null for tests that construct properties directly.
    Map<String, PieriaProperties.Stats.TierPrice> prices =
      (properties == null || properties.stats() == null) ? Map.of() : properties.stats().spend();

    List<TierSpendView> tiers = new ArrayList<>();
    long totalPrompt = 0;
    long totalCompletion = 0;
    double totalCost = 0.0;
    boolean costAvailable = false;

    for (Map.Entry<InferenceTier, TierUsage> entry : usage.entrySet()) {
      String tierName = entry.getKey().name().toLowerCase(Locale.ROOT);
      TierUsage u = entry.getValue();
      PieriaProperties.Stats.TierPrice price = prices.get(tierName);

      double cost = 0.0;
      if (price != null) {
        cost = u.promptTokens() / 1_000_000.0 * price.inputPrice()
          + u.completionTokens() / 1_000_000.0 * price.outputPrice();
        if (price.inputPrice() > 0.0 || price.outputPrice() > 0.0) {
          costAvailable = true;
        }
      }

      tiers.add(new TierSpendView(tierName, u.calls(), u.promptTokens(), u.completionTokens(), cost));
      totalPrompt += u.promptTokens();
      totalCompletion += u.completionTokens();
      totalCost += cost;
    }

    return new SpendView(tiers, totalPrompt, totalCompletion, totalCost, costAvailable);
  }

  public record ImpactView(long recallCount, long tokensSaved,
                           long tokensIngested, long tokensStored,
                           int contextWindowTokens, double pricePerMillionTokens) {
  }

  public record TierSpendView(String tier, long calls, long promptTokens, long completionTokens, double cost) {
  }

  public record SpendView(List<TierSpendView> tiers, long totalPrompt, long totalCompletion,
                          double totalCost, boolean costAvailable) {
  }

  public record ProfileStatsView(String name, Instant createdAt, long totalActive,
                                 Map<String, Long> byType, long superseded, long sessions,
                                 Instant firstMemoryAt, Instant lastMemoryAt, Long backlog,
                                 ImpactView impact, SpendView spend,
                                 String parent, int subProfiles, long subtreeActive) {
  }
}
