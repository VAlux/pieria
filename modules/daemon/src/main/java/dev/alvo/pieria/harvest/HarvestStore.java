package dev.alvo.pieria.harvest;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Persistence for harvest sources, runs, items and their links to memories. */
public interface HarvestStore {

  void createSource(HarvestSource source);

  Optional<HarvestSource> findSource(String sourceId);

  List<HarvestSource> listSources(String profileId);

  /** Active sources whose next run time has passed, soonest first. */
  List<HarvestSource> dueSources(Instant now, int limit);

  void saveCursor(String sourceId, String cursorJson);

  void scheduleNext(String sourceId, Instant nextRunAt, int consecutiveFailures);

  void setStatus(String sourceId, SourceStatus status);

  void setSweepBlocked(String sourceId, Long count);

  void deleteSource(String sourceId);

  /** Updates the source's config, mode, interval, limits and cursor. */
  void updateSource(HarvestSource source);

  void setAcceptNextSweep(String sourceId, boolean accept);

  boolean acceptNextSweep(String sourceId);

  boolean anySourceUses(String harvesterId);

  /** Records a new {@link RunStatus#RUNNING} run and returns its id. */
  String startRun(String sourceId, boolean dryRun);

  /** Records how a run ended. {@code startedAt} on the argument is ignored. */
  void finishRun(HarvestRun run);

  /** Marks runs left over from a previous process as interrupted, and makes their sources due. */
  int interruptRunningRuns(Instant nextRunAt);

  /** The source's runs, newest first. */
  List<HarvestRun> listRuns(String sourceId, int limit);

  Optional<HarvestItem> findItem(String sourceId, String externalId);

  void upsertItem(HarvestItem item);

  void markSeen(String sourceId, Collection<String> externalIds, String runId);

  /** Items that were not reported in {@code runId}. */
  List<HarvestItem> unseenItems(String sourceId, String runId);

  long liveItemCount(String sourceId);

  /** Removes the item and its links. */
  void deleteItem(String sourceId, String externalId);

  void addLinks(String sourceId, String externalId, List<MemoryLink> links);

  List<MemoryLink> links(String sourceId, String externalId);

  void removeLinks(String sourceId, String externalId, Collection<String> memoryIds);

  void deleteProfile(String profileId);

  /** The name of the profile with this id, which the ingest pipeline addresses profiles by. */
  String profileName(String profileId);
}
