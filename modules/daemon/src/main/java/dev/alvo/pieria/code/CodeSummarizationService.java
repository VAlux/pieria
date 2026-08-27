package dev.alvo.pieria.code;

import dev.alvo.pieria.code.CodeIndexingService.SourceFile;
import dev.alvo.pieria.config.CodeSummarizationProperties;
import dev.alvo.pieria.domain.memory.Memory;
import dev.alvo.pieria.domain.memory.MemoryType;
import dev.alvo.pieria.ingestion.IngestProgressListener;
import dev.alvo.pieria.model.ModelGateway;
import dev.alvo.pieria.model.ModelGateway.CodeSummaryInput;
import dev.alvo.pieria.model.ModelGateway.CodeSummaryLevel;
import dev.alvo.pieria.storage.MemoryStore;
import dev.alvo.pieria.tools.Hash;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The LLM-synthesized narrative layer over the deterministic code index: per-file purpose
 * summaries, per-module roll-ups, and one repo architecture overview (cumulative, per the
 * configured granularity), written by the synthesis (large) model as normal keyed {@code fact}
 * memories so they flow through FTS/vectorization/retrieval unchanged.
 *
 * <p>Deliberately separate from {@link CodeIndexingService#index}, which is model-free: this stage
 * runs after it, best-effort — a summary failure never affects the index, and {@link #summarize}
 * never throws. Model calls happen outside any transaction ({@code store.store} is itself
 * transactional).
 *
 * <p><b>Content-addressed skipping</b>: each summary memory's payload carries the hash of the code
 * it summarizes (file contentHash; module = hash over member (path, contentHash) pairs;
 * architecture = hash over module hashes — all salted with {@link #PROMPT_VERSION} so prompt
 * changes force regeneration). An unchanged hash on the active memory for the topic key skips the
 * model call entirely. When the code changed, storing the new summary supersedes the old one via
 * the normal keyed-fact machinery. Edge case, accepted: if the model regenerates byte-identical
 * prose for changed code, the content-addressed memory id collides with the active row and the
 * payload hash is not refreshed, so the next run re-summarizes that target — idempotent and
 * harmless.
 */
@Service
public class CodeSummarizationService {

  public static final String TOPIC_FILE_PREFIX = "code:summary:file:";
  public static final String TOPIC_MODULE_PREFIX = "code:summary:module:";
  public static final String TOPIC_ARCHITECTURE = "code:summary:architecture";
  /**
   * Salt for all summary hashes; bump after prompt changes to regenerate every summary.
   */
  static final String PROMPT_VERSION = "v1";
  /**
   * Pseudo-module for files with no build-marker ancestor and no top-level directory.
   */
  static final String ROOT_MODULE = "(root)";
  private static final Logger log = LoggerFactory.getLogger(CodeSummarizationService.class);
  private final MemoryStore store;
  private final ModelGateway gateway;
  private final CodeSummarizationProperties properties;

  public CodeSummarizationService(MemoryStore store,
                                  ModelGateway gateway,
                                  CodeSummarizationProperties properties) {
    this.store = store;
    this.gateway = gateway;
    this.properties = properties;
  }

  private static @NonNull String getContentHash(SourceFile file) {
    if (file.contentHash() == null || file.contentHash().isBlank()) {
      return Hash.hash128(file.content() == null ? "" : file.content());
    }

    return file.contentHash();
  }

  /**
   * Summarize the batch into the named profile per the configured granularity. Files must already
   * be indexed (the prompts draw on the derived {@code code:file:} facts). Reports one
   * {@code "summarize"} progress tick per target. Never throws.
   */
  public SummarizationResult summarize(String profileName, List<SourceFile> files,
                                       IngestProgressListener progress) {
    try {
      return doSummarize(profileName, files == null ? List.of() : files, progress);
    } catch (RuntimeException e) {
      log.warn("code summarization aborted ({}); index result unaffected", e.toString());
      return SummarizationResult.empty();
    }
  }

  private SummarizationResult doSummarize(String profileName, List<SourceFile> files,
                                          IngestProgressListener progress) {
    if (files.isEmpty()) {
      return SummarizationResult.empty();
    }

    String profileId = store.getOrCreateProfile(profileName).id();

    // Group by module and normalize per-file hashes exactly like the indexer does, so the
    // file-level keys agree with the index.
    var markerDirs = ModulePaths.markerDirs(files);
    Map<String, List<SourceFile>> byModule = new TreeMap<>();
    Map<String, String> fileHashes = new TreeMap<>();
    for (SourceFile file : files) {
      String module = ModulePaths.moduleDir(file.repoRelPath(), markerDirs).orElse(ROOT_MODULE);
      byModule.computeIfAbsent(module, _ -> new ArrayList<>()).add(file);
      String contentHash = getContentHash(file);
      fileHashes.put(file.repoRelPath(), contentHash);
    }

    byModule.values().forEach(list -> list.sort(Comparator.comparing(SourceFile::repoRelPath)));

    Map<String, String> moduleHashes = new TreeMap<>();
    for (var entry : byModule.entrySet()) {
      String members = entry.getValue().stream()
        .map(file -> file.repoRelPath() + "=" + fileHashes.get(file.repoRelPath()))
        .reduce("", (a, b) -> a + "\n" + b);

      moduleHashes.put(entry.getKey(), Hash.hash128(PROMPT_VERSION, entry.getKey(), members));
    }
    String archHash = Hash.hash128(PROMPT_VERSION, moduleHashes.entrySet().stream()
      .map(e -> e.getKey() + "=" + e.getValue())
      .reduce("", (a, b) -> a + "\n" + b));

    // Children before parents: file summaries feed module prompts, module summaries feed the
    // architecture prompt.
    int total = (properties.fileLevel() ? files.size() : 0)
      + (properties.moduleLevel() ? byModule.size() : 0)
      + 1;

    int done = 0;
    int stored = 0;
    int skipped = 0;
    int failed = 0;

    if (properties.fileLevel()) {
      for (SourceFile file : files) {
        String hash = PROMPT_VERSION + ":" + fileHashes.get(file.repoRelPath());
        switch (summarizeTarget(profileId, TOPIC_FILE_PREFIX + file.repoRelPath(), "file",
          file.repoRelPath(), hash, () -> fileInput(profileId, file))) {
          case STORED -> stored++;
          case SKIPPED -> skipped++;
          case FAILED -> failed++;
        }

        progress.onPhase("summarize", ++done, total);
      }
    }

    if (properties.moduleLevel()) {
      for (var entry : byModule.entrySet()) {
        String module = entry.getKey();
        switch (summarizeTarget(profileId, TOPIC_MODULE_PREFIX + module, "module",
          module, moduleHashes.get(module), () -> moduleInput(profileId, module, entry.getValue()))) {
          case STORED -> stored++;
          case SKIPPED -> skipped++;
          case FAILED -> failed++;
        }
        progress.onPhase("summarize", ++done, total);
      }
    }

    switch (summarizeTarget(profileId, TOPIC_ARCHITECTURE, "architecture",
      null, archHash, () -> architectureInput(profileId, profileName, byModule))) {
      case STORED -> stored++;
      case SKIPPED -> skipped++;
      case FAILED -> failed++;
    }
    progress.onPhase("summarize", ++done, total);

    log.info("code summaries profile={} granularity={} stored={} skippedUnchanged={} failed={}",
      profileName, properties.granularity(), stored, skipped, failed);
    return new SummarizationResult(stored, skipped, failed);
  }

  /**
   * Summarize one target best-effort: skip when the active summary already covers this hash,
   * otherwise call the model and store the keyed fact (superseding the stale summary).
   */
  private Outcome summarizeTarget(String profileId, String topicKey, String level, String path,
                                  String hash, java.util.function.Supplier<CodeSummaryInput> input) {
    try {
      List<Memory> active = store.findActiveByTopicKey(profileId, MemoryType.FACT, topicKey);
      if (!active.isEmpty()
        && CodeSummaryPayload.hash(active.getFirst().payload()).filter(hash::equals).isPresent()) {
        return Outcome.SKIPPED;
      }

      String text = gateway.summarizeCode(input.get());
      if (text == null || text.isBlank()) {
        log.warn("code summary for {} came back blank; skipping store", topicKey);
        return Outcome.FAILED;
      }

      Memory memory = Memory.of(MemoryType.FACT, text.strip(), CodeIndexingService.CODE_SESSION,
        topicKey, CodeSummaryPayload.write(level, path, hash));
      store.store(profileId, memory);
      return Outcome.STORED;
    } catch (RuntimeException e) {
      log.warn("code summary for {} failed ({}); continuing", topicKey, e.toString());
      return Outcome.FAILED;
    }
  }

  private CodeSummaryInput fileInput(String profileId, SourceFile file) {
    String outline = activeContent(profileId, "code:file:" + file.repoRelPath())
      .orElse("(no symbol outline)");
    String source = file.content() == null ? "" : file.content();
    int cap = properties.maxSourceCharsPerFile();
    if (source.length() > cap) {
      source = source.substring(0, cap) + "\n… [truncated]";
    }
    return new CodeSummaryInput(CodeSummaryLevel.FILE, file.repoRelPath(), file.language(),
      List.of(outline), List.of(), source);
  }

  private CodeSummaryInput moduleInput(String profileId, String module, List<SourceFile> members) {
    List<String> outlines = members.stream()
      .limit(properties.maxFilesPerModulePrompt())
      .map(f -> activeContent(profileId, "code:file:" + f.repoRelPath()).orElse(f.repoRelPath()))
      .toList();

    List<String> fileSummaries = members.stream()
      .map(f -> activeContent(profileId, TOPIC_FILE_PREFIX + f.repoRelPath()))
      .flatMap(java.util.Optional::stream)
      .limit(properties.maxFilesPerModulePrompt())
      .toList();

    return new CodeSummaryInput(CodeSummaryLevel.MODULE, module, null, outlines, fileSummaries, null);
  }

  private CodeSummaryInput architectureInput(String profileId, String profileName,
                                             Map<String, List<SourceFile>> byModule) {
    int cap = properties.maxModulesInArchitecturePrompt();
    List<String> moduleSummaries = byModule.keySet().stream()
      .map(m -> activeContent(profileId, TOPIC_MODULE_PREFIX + m))
      .flatMap(java.util.Optional::stream)
      .limit(cap)
      .toList();

    // Fallback evidence when module summaries are not generated (granularity=architecture).
    List<String> listings = byModule.entrySet().stream()
      .limit(cap)
      .map(e -> e.getKey() + ": " + e.getValue().stream()
        .map(SourceFile::repoRelPath)
        .limit(15)
        .reduce((a, b) -> a + ", " + b)
        .orElse("(empty)"))
      .toList();

    return new CodeSummaryInput(CodeSummaryLevel.ARCHITECTURE, profileName, null, listings, moduleSummaries, null);
  }

  /**
   * Content of the active memory for {@code topicKey}, if any.
   */
  private java.util.Optional<String> activeContent(String profileId, String topicKey) {
    List<Memory> active = store.findActiveByTopicKey(profileId, MemoryType.FACT, topicKey);
    return active.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(active.getFirst().content());
  }

  private enum Outcome {STORED, SKIPPED, FAILED}

  /**
   * Per-run observability counts.
   */
  public record SummarizationResult(int stored, int skippedUnchanged, int failed) {
    public static SummarizationResult empty() {
      return new SummarizationResult(0, 0, 0);
    }
  }
}
