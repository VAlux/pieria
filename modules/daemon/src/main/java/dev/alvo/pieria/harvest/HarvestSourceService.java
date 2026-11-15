package dev.alvo.pieria.harvest;

import dev.alvo.pieria.api.response.MemoryOrigin;
import dev.alvo.pieria.config.PieriaProperties;
import dev.alvo.pieria.domain.error.ConflictException;
import dev.alvo.pieria.domain.error.NotFoundException;
import dev.alvo.pieria.domain.profile.Profile;
import dev.alvo.pieria.storage.MemoryStore;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Creates and manages harvest sources on behalf of the REST API. */
@Service
public class HarvestSourceService {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final long MIN_INTERVAL_SECONDS = 60;
  private static final Duration STOP_TIMEOUT = Duration.ofSeconds(30);

  private final HarvestStore store;
  private final MemoryStore memories;
  private final HarvesterRegistry registry;
  private final HarvestSecretStore secrets;
  private final HarvestItemIngestor ingestor;
  private final HarvestScheduler scheduler;
  private final HarvestRunService runs;
  private final HarvestAuthorizationService authorizations;
  private final PieriaProperties properties;

  public HarvestSourceService(HarvestStore store, MemoryStore memories, HarvesterRegistry registry, HarvestSecretStore secrets,
                              HarvestItemIngestor ingestor, HarvestScheduler scheduler, HarvestRunService runs,
                              HarvestAuthorizationService authorizations, PieriaProperties properties) {
    this.store = store;
    this.memories = memories;
    this.registry = registry;
    this.secrets = secrets;
    this.ingestor = ingestor;
    this.scheduler = scheduler;
    this.runs = runs;
    this.authorizations = authorizations;
    this.properties = properties;
  }

  /** Unless {@code start} is set, a new source waits to be resumed, so it can't begin an expensive backfill unasked. */
  public HarvestSource create(String profile, SourceDraft draft) {
    if (draft.id() == null || !draft.id().matches(MemoryOrigin.SOURCE_ID_REGEX)) {
      throw new IllegalArgumentException("invalid source id: " + draft.id());
    }
    RegisteredHarvester harvester = registry.find(draft.harvesterId())
      .orElseThrow(() -> new IllegalArgumentException("harvester not installed: " + draft.harvesterId()));
    Map<String, String> suppliedSecrets = draft.secrets() == null ? Map.of() : draft.secrets();
    ObjectNode config = draft.config() == null ? MAPPER.createObjectNode() : draft.config();
    SourceConfigValidator.validate(harvester.manifest(), config, suppliedSecrets.keySet(), true);
    requireSecretValues(suppliedSecrets);
    requireValidSchedule(draft.intervalSeconds(), draft.maxItemsPerRun(), draft.maxCharsPerRun());
    if (store.findSource(draft.id()).isPresent()) {
      throw new ConflictException("source already exists: " + draft.id());
    }
    String profileId = memories.getOrCreateProfile(profile).id();
    Instant now = Instant.now();
    HarvestSource source = new HarvestSource(draft.id(), profileId, draft.harvesterId(), config.toString(),
      draft.mode() == null ? SourceMode.FULL : draft.mode(), draft.intervalSeconds(), draft.maxItemsPerRun(), draft.maxCharsPerRun(),
      draft.start() ? SourceStatus.ACTIVE : SourceStatus.PAUSED, null, now, 0, null, now);
    store.createSource(source);
    suppliedSecrets.forEach((name, value) -> secrets.put(draft.id(), name, value));
    return source;
  }

  private static void requireSecretValues(Map<String, String> values) {
    values.forEach((name, value) -> {
      if (value == null || value.isBlank()) {
        throw new IllegalArgumentException("secret " + name + " must not be blank");
      }
    });
  }

  private static void requireValidSchedule(Long intervalSeconds, Integer maxItems, Long maxChars) {
    if (intervalSeconds != null && intervalSeconds < MIN_INTERVAL_SECONDS) {
      throw new IllegalArgumentException("the interval must be at least " + MIN_INTERVAL_SECONDS + " seconds");
    }
    if ((maxItems != null && maxItems < 1) || (maxChars != null && maxChars < 1)) {
      throw new IllegalArgumentException("limits must be positive");
    }
  }

  public HarvestSource update(String profile, String id, SourceUpdate update) {
    HarvestSource source = get(profile, id);
    if (scheduler.isRunning(id)) {
      throw new ConflictException("source " + id + " is running; try again when it finishes");
    }
    requireValidSchedule(update.intervalSeconds(), update.maxItemsPerRun(), update.maxCharsPerRun());
    ObjectNode config = config(source);
    if (update.config() != null) {
      config.setAll(update.config());
      RegisteredHarvester harvester = registry.find(source.harvesterId())
        .orElseThrow(() -> new IllegalArgumentException("harvester not installed: " + source.harvesterId()));
      SourceConfigValidator.validate(harvester.manifest(), config, secrets.secrets(id).keySet(), true);
    }
    store.updateSource(new HarvestSource(id, source.profileId(), source.harvesterId(), config.toString(),
      update.mode() == null ? source.mode() : update.mode(),
      update.intervalSeconds() == null ? source.intervalSeconds() : update.intervalSeconds(),
      update.maxItemsPerRun() == null ? source.maxItemsPerRun() : update.maxItemsPerRun(),
      update.maxCharsPerRun() == null ? source.maxCharsPerRun() : update.maxCharsPerRun(),
      source.status(), update.resetCursor() ? null : source.cursorJson(), source.nextRunAt(), source.consecutiveFailures(),
      source.sweepBlockedCount(), source.createdAt()));
    return store.findSource(id).orElseThrow();
  }

  private static ObjectNode config(HarvestSource source) {
    try {
      if (MAPPER.readTree(source.configJson()) instanceof ObjectNode config) {
        return config;
      }
    } catch (JacksonException e) {
      // Reported below.
    }
    throw new IllegalStateException("the stored config of source " + source.id() + " is not a JSON object");
  }

  public HarvestSource get(String profile, String id) {
    String profileId = profileId(profile);
    return store.findSource(id)
      .filter(source -> source.profileId().equals(profileId))
      .orElseThrow(() -> new NotFoundException("source not found: " + id));
  }

  private String profileId(String profile) {
    return memories.findProfile(profile).map(Profile::id).orElseThrow(() -> NotFoundException.profile(profile));
  }

  public List<HarvestSource> list(String profile) {
    return store.listSources(profileId(profile));
  }

  /** Stops any run and keeps new ones from starting, so nothing can write to a source being taken apart. */
  public void remove(String profile, String id, boolean forgetMemories) {
    HarvestSource source = get(profile, id);
    authorizations.cancel(id);
    scheduler.hold(id);
    try {
      if (!scheduler.stopAndWait(id, STOP_TIMEOUT)) {
        throw new ConflictException("source " + id + " is still running; try again shortly");
      }
      if (forgetMemories) {
        store.items(id).forEach(item -> ingestor.delete(source, item.externalId()));
      }
      store.deleteSource(id);
      secrets.remove(id);
    } finally {
      scheduler.release(id);
    }
  }

  public SourceCheckResult check(String profile, String id) {
    get(profile, id);
    return runs.check(id);
  }

  /** Starts the harvester's sign-in and returns its code for the user, or why it failed. */
  public AuthorizationState authorize(String profile, String id) {
    get(profile, id);
    return authorizations.start(id);
  }

  public Optional<AuthorizationState> authorization(String profile, String id) {
    get(profile, id);
    return authorizations.status(id);
  }

  public void pause(String profile, String id) {
    get(profile, id);
    store.setStatus(id, SourceStatus.PAUSED);
  }

  /** Also how a source recovers from {@link SourceStatus#NEEDS_AUTH} once its credentials are fixed. */
  public void resume(String profile, String id) {
    get(profile, id);
    store.setStatus(id, SourceStatus.ACTIVE);
    store.scheduleNext(id, Instant.now(), 0);
  }

  /** Lets the source's next complete sweep delete as many items as the mass-delete guard held back. */
  public void acceptNextSweep(String profile, String id) {
    Long heldBack = get(profile, id).sweepBlockedCount();
    if (heldBack == null) {
      throw new ConflictException("source " + id + " has no held-back sweep to accept");
    }
    store.setAcceptedSweepCount(id, heldBack);
  }

  public void setSecrets(String profile, String id, Map<String, String> values) {
    HarvestSource source = get(profile, id);
    RegisteredHarvester harvester = registry.find(source.harvesterId())
      .orElseThrow(() -> new IllegalArgumentException("harvester not installed: " + source.harvesterId()));
    SourceConfigValidator.validate(harvester.manifest(), MAPPER.createObjectNode(), values.keySet(), false);
    requireSecretValues(values);
    values.forEach((name, value) -> secrets.put(id, name, value));
  }

  public UUID run(String profile, String id, boolean dryRun) {
    get(profile, id);
    return scheduler.launch(id, dryRun)
      .orElseThrow(() -> new ConflictException("source " + id + " is already running, or no run slot is free"));
  }

  public List<HarvestRun> runs(String profile, String id, int limit) {
    get(profile, id);
    return store.listRuns(id, limit);
  }

  public Set<String> secretNames(String id) {
    return Set.copyOf(secrets.secrets(id).keySet());
  }

  public boolean isRunning(String id) {
    return scheduler.isRunning(id);
  }

  public boolean acceptNextSweepPending(String id) {
    return store.acceptedSweepCount(id) != null;
  }

  /** Whether source text will leave this machine for extraction. */
  public boolean usesRemoteModelProvider() {
    String baseUrl = properties.provider() == null ? null : properties.provider().baseUrl();
    if (baseUrl == null || baseUrl.isBlank()) {
      return false;
    }
    String host = URI.create(baseUrl).getHost();
    if (host == null) {
      return true;
    }
    if (host.equalsIgnoreCase("localhost")) {
      return false;
    }
    // Only a literal address is checked; resolving a name here would mean a network call for a warning.
    if (!host.matches("^[0-9.]+$") && !host.contains(":")) {
      return true;
    }
    try {
      return !InetAddress.getByName(host).isLoopbackAddress();
    } catch (UnknownHostException e) {
      return true;
    }
  }

  /**
   * Runs {@code deleteProfile} with the profile's sources stopped and unable to start, then removes
   * their credentials. A run that won't stop refuses the whole delete, so nothing writes to a deleted
   * profile and credentials are never lost for a profile that still exists.
   */
  public void removeAllFor(String profileId, Runnable deleteProfile) {
    List<String> ids = store.listSources(profileId).stream().map(HarvestSource::id).toList();
    ids.forEach(authorizations::cancel);
    ids.forEach(scheduler::hold);
    try {
      for (String id : ids) {
        if (!scheduler.stopAndWait(id, STOP_TIMEOUT)) {
          throw new ConflictException("source " + id + " is still running; try again shortly");
        }
      }
      deleteProfile.run();
      ids.forEach(secrets::remove);
    } finally {
      ids.forEach(scheduler::release);
    }
  }
}
