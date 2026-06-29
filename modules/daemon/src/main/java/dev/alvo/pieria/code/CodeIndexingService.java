package dev.alvo.pieria.code;

import dev.alvo.pieria.code.CodeParser.ParseResult;
import dev.alvo.pieria.code.CodeParser.ParsedEdge;
import dev.alvo.pieria.code.CodeParser.ParsedSymbol;
import dev.alvo.pieria.domain.ContentId;
import dev.alvo.pieria.domain.code.CodeEdge;
import dev.alvo.pieria.domain.code.CodeFile;
import dev.alvo.pieria.domain.code.CodeModule;
import dev.alvo.pieria.domain.code.CodeRelation;
import dev.alvo.pieria.domain.code.CodeSymbol;
import dev.alvo.pieria.domain.code.EdgeConfidence;
import dev.alvo.pieria.domain.graph.Edge;
import dev.alvo.pieria.domain.graph.Entity;
import dev.alvo.pieria.domain.memory.Memory;
import dev.alvo.pieria.domain.memory.MemoryType;
import dev.alvo.pieria.domain.profile.Profile;
import dev.alvo.pieria.ingestion.IngestProgressListener;
import dev.alvo.pieria.storage.CodeIndexStore;
import dev.alvo.pieria.storage.MemoryStore;
import dev.alvo.pieria.tools.Hash;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Phase 13 write path for source code: for each file, skip-if-unchanged by hash, parse via the
 * language pack (none ⇒ file/dependency facts only), store the symbol/edge substrate atomically,
 * derive a compact durable {@code fact} memory carrying its source symbol ids as provenance, and
 * project a curated subset of relations into the Phase 8 {@code Entity}/{@code Edge} graph — all in
 * one per-file transaction so a single bad file never corrupts the batch. No model I/O: derivation
 * is fully deterministic.
 */
@Service
public class CodeIndexingService {

  private static final Logger log = LoggerFactory.getLogger(CodeIndexingService.class);

  /**
   * Stable session id so unchanged code yields identical, content-addressed derived memories.
   */
  static final String CODE_SESSION = "pieria:code-index";

  /**
   * Build-file names used to locate module roots within a batch.
   */
  private static final Set<String> BUILD_MARKERS = Set.of(
    "build.gradle.kts", "build.gradle", "pom.xml", "package.json", "go.mod", "Cargo.toml");

  private static final int MAX_SYMBOLS_IN_FACT = 30;
  private static final int MAX_SYMBOL_IDS = 200;
  private static final Set<CodeRelation> CURATED_RELATIONS =
    Set.of(CodeRelation.DEPENDS_ON, CodeRelation.TESTS, CodeRelation.HANDLES_ROUTE);

  private final MemoryStore store;
  private final CodeIndexStore codeStore;
  private final List<CodeParser> parsers;
  private final TransactionTemplate tx;

  public CodeIndexingService(MemoryStore store,
                             CodeIndexStore codeStore,
                             List<CodeParser> parsers,
                             PlatformTransactionManager txManager) {
    this.store = store;
    this.codeStore = codeStore;
    this.parsers = parsers == null ? List.of() : List.copyOf(parsers);
    this.tx = new TransactionTemplate(txManager);
  }

  /**
   * One source file to index. {@code language}/{@code contentHash} may be blank (auto-derived).
   */
  public record SourceFile(String repoRelPath, String language, String contentHash, String content) {
  }

  /**
   * Per-run observability counts.
   */
  public record CodeIndexSummary(
    int filesReceived, int filesSkippedUnchanged, int filesParsed, int filesFailed,
    int symbols, int resolvedEdges, int heuristicEdges,
    int memoriesStored, int memoriesSuperseded, int graphEntities, int graphEdges) {
  }

  /**
   * Index a batch into the named profile. {@code treeHash} is accepted for status/freshness and is
   * not otherwise used here.
   */
  public CodeIndexSummary index(String profileName, String treeHash, List<SourceFile> files) {
    return index(profileName, treeHash, files, false, IngestProgressListener.noop());
  }

  /**
   * As {@link #index(String, String, List)}, reporting per-file progress through {@code progress} so
   * a long-running index can be observed while it runs.
   */
  public CodeIndexSummary index(String profileName, String treeHash, List<SourceFile> files,
                                IngestProgressListener progress) {
    return index(profileName, treeHash, files, false, progress);
  }

  /**
   * As {@link #index(String, String, List, IngestProgressListener)}, but when {@code reindex} is true
   * every file is parsed even if its content hash is unchanged (the skip-if-unchanged optimization is
   * bypassed) — used after a parser/language-pack upgrade so unchanged source is re-indexed.
   */
  public CodeIndexSummary index(String profileName, String treeHash, List<SourceFile> files,
                                boolean reindex, IngestProgressListener progress) {
    Profile profile = store.getOrCreateProfile(profileName);
    String profileId = profile.id();
    List<SourceFile> batch = files == null ? List.of() : files;
    Set<String> markerDirs = markerDirs(batch);

    Acc acc = new Acc();
    acc.filesReceived = batch.size();
    int total = batch.size();
    int done = 0;
    for (SourceFile file : batch) {
      try {
        FileResult r = tx.execute(_ -> indexOne(profileId, file, markerDirs, reindex));
        acc.add(r);
      } catch (RuntimeException e) {
        acc.filesFailed++;
        log.warn("code index: failed to index {} ({}); continuing", file.repoRelPath(), e.toString());
      }
      progress.onPhase("index", ++done, total);
    }
    log.info("code index profile={} tree={} received={} skipped={} parsed={} failed={} symbols={} "
        + "edges(resolved/heuristic)={}/{} memories(stored/superseded)={}/{} graph(entities/edges)={}/{}",
      profileName, treeHash, acc.filesReceived, acc.filesSkippedUnchanged, acc.filesParsed,
      acc.filesFailed, acc.symbols, acc.resolvedEdges, acc.heuristicEdges, acc.memoriesStored,
      acc.memoriesSuperseded, acc.graphEntities, acc.graphEdges);
    return acc.toSummary();
  }

  private FileResult indexOne(String profileId, SourceFile file, Set<String> markerDirs, boolean reindex) {
    String path = file.repoRelPath();
    String contentHash = (file.contentHash() == null || file.contentHash().isBlank())
      ? Hash.hash128(file.content() == null ? "" : file.content())
      : file.contentHash();

    if (!reindex && codeStore.fileContentHash(profileId, path).filter(contentHash::equals).isPresent()) {
      return FileResult.skipped();
    }

    String language = (file.language() == null || file.language().isBlank())
      ? LanguageDetector.detect(path)
      : file.language();

    String moduleId = upsertModule(profileId, path, markerDirs);
    String fileId = ContentId.forCodeFile(profileId, path);

    ParseResult parsed = parse(language, path, file.content());

    // Build symbols with stable ids and a qualifiedName → id map for edge resolution.
    Map<String, CodeSymbol> byQname = new LinkedHashMap<>();
    List<CodeSymbol> symbols = new ArrayList<>();
    for (ParsedSymbol ps : parsed.symbols()) {
      String id = ContentId.forCodeSymbol(profileId, fileId, ps.kind().wire(), ps.qualifiedName(), ps.signature());
      CodeSymbol s = new CodeSymbol(id, profileId, fileId, ps.kind(), ps.name(), ps.qualifiedName(),
        ps.signature(), ps.visibility(), ps.startLine(), ps.endLine(), language, null, path);
      symbols.add(s);
      byQname.put(ps.qualifiedName(), s);
    }

    // Build edges; resolve src/dst within the file, then globally by qualified name.
    List<CodeEdge> edges = new ArrayList<>();
    for (ParsedEdge pe : parsed.edges()) {
      CodeSymbol src = byQname.get(pe.srcQualifiedName());
      if (src == null) {
        continue; // edge with no known source symbol in this file
      }
      String dstSymbolId = resolveDst(profileId, byQname, pe.dstQualifiedName());
      String dstRef = (pe.dstRef() != null && !pe.dstRef().isBlank())
        ? pe.dstRef()
        : lastSegment(pe.dstQualifiedName());
      edges.add(new CodeEdge(null, profileId, src.id(), pe.relation(), pe.confidence(),
        dstSymbolId, dstRef, fileId));
    }

    CodeFile codeFile = new CodeFile(fileId, profileId, language, path, contentHash,
      lineCount(file.content()), moduleId, null);
    codeStore.replaceFileIndex(profileId, codeFile, symbols, edges);

    FileResult r = new FileResult();
    r.parsed = 1;
    r.symbols = symbols.size();
    for (CodeEdge e : edges) {
      if (e.confidence() == EdgeConfidence.RESOLVED) {
        r.resolvedEdges++;
      } else {
        r.heuristicEdges++;
      }
    }

    deriveAndProject(profileId, path, language, contentHash, fileId, symbols, parsed.edges(), r);
    return r;
  }

  /**
   * Derive the per-file fact (with symbol-id provenance) and project curated relations.
   */
  private void deriveAndProject(String profileId, String path, String language, String contentHash,
                                String fileId, List<CodeSymbol> symbols, List<ParsedEdge> parsedEdges,
                                FileResult r) {
    List<ParsedEdge> curated = parsedEdges.stream()
      .filter(e -> CURATED_RELATIONS.contains(e.relation()))
      .toList();

    String content = factContent(path, language, symbols, curated);
    if (content == null) {
      return; // nothing durable to say about this file
    }

    List<String> symbolIds = symbols.stream().map(CodeSymbol::id).distinct().limit(MAX_SYMBOL_IDS).toList();
    String payload = codePayload(language, path, fileId, contentHash, symbolIds);
    Memory memory = Memory.of(MemoryType.FACT, content, CODE_SESSION, "code:file:" + path, payload);

    MemoryStore.StoreOutcome outcome = store.store(profileId, memory);
    r.memoriesStored++;
    if (outcome.supersededId() != null) {
      r.memoriesSuperseded++;
    }

    // Project curated relations into the Phase 8 graph, tagged with this fact's id as provenance.
    String memoryId = outcome.stored().id();
    String fileEntityType = "file";
    Entity fileEntity = store.upsertEntity(profileId, Entity.of(fileEntityType, path, "{}"));
    r.graphEntities++;
    for (ParsedEdge e : curated) {
      String targetType = targetEntityType(e.relation());
      String targetName = (e.dstRef() != null && !e.dstRef().isBlank()) ? e.dstRef() : lastSegment(e.dstQualifiedName());
      if (targetName == null || targetName.isBlank()) {
        continue;
      }
      Entity target = store.upsertEntity(profileId, Entity.of(targetType, targetName, "{}"));
      store.upsertEdge(profileId, new Edge(null, profileId, fileEntity.id(), target.id(),
        e.relation().wire(), memoryId, null));
      r.graphEntities++;
      r.graphEdges++;
    }
  }

  private String resolveDst(String profileId, Map<String, CodeSymbol> byQname, String dstQname) {
    if (dstQname == null || dstQname.isBlank()) {
      return null;
    }
    CodeSymbol inFile = byQname.get(dstQname);
    if (inFile != null) {
      return inFile.id();
    }
    List<CodeSymbol> global = codeStore.findSymbolsByQualifiedName(profileId, List.of(dstQname), 1);
    return global.isEmpty() ? null : global.getFirst().id();
  }

  private String upsertModule(String profileId, String path, Set<String> markerDirs) {
    Optional<String> dir = moduleDir(path, markerDirs);
    if (dir.isEmpty()) {
      return null;
    }
    String modulePath = dir.get();
    CodeModule module = codeStore.upsertCodeModule(profileId, CodeModule.of(lastSegment(modulePath), modulePath));
    return module.id();
  }

  private ParseResult parse(String language, String path, String content) {
    if (language == null || language.isBlank()) {
      return ParseResult.empty();
    }
    for (CodeParser p : parsers) {
      if (p.supports(language)) {
        try {
          return p.parse(new CodeParser.ParseInput(path, language, content == null ? "" : content));
        } catch (RuntimeException e) {
          log.warn("code index: parser {} failed on {} ({}); no symbols", language, path, e.toString());
          return ParseResult.empty();
        }
      }
    }
    return ParseResult.empty();
  }

  // ---- deterministic helpers ----

  private static String factContent(String path, String language, List<CodeSymbol> symbols, List<ParsedEdge> curated) {
    if (!symbols.isEmpty()) {
      List<String> parts = symbols.stream()
        .sorted(Comparator.comparing((CodeSymbol s) -> s.kind().wire()).thenComparing(CodeSymbol::name))
        .map(s -> s.kind().wire() + " " + s.name())
        .distinct()
        .limit(MAX_SYMBOLS_IN_FACT)
        .toList();
      return "Source file " + path + " (" + language + ") defines: " + String.join(", ", parts) + ".";
    }
    if (!curated.isEmpty()) {
      Set<String> targets = new TreeSet<>();
      for (ParsedEdge e : curated) {
        String t = (e.dstRef() != null && !e.dstRef().isBlank()) ? e.dstRef() : lastSegment(e.dstQualifiedName());
        if (t != null && !t.isBlank()) {
          targets.add(t);
        }
      }
      if (!targets.isEmpty()) {
        return "Source file " + path + " (" + language + ") depends on: " + String.join(", ", targets) + ".";
      }
    }
    return null;
  }

  private static String targetEntityType(CodeRelation relation) {
    return switch (relation) {
      case DEPENDS_ON -> "module";
      case TESTS -> "class";
      case HANDLES_ROUTE -> "endpoint";
      default -> "concept";
    };
  }

  /**
   * Stable, deterministic JSON payload (fixed key order helps content-addressing).
   */
  private static String codePayload(String language, String path, String fileId, String contentHash,
                                    List<String> symbolIds) {
    StringBuilder sb = new StringBuilder();
    sb.append("{\"source\":\"code\"");
    sb.append(",\"language\":\"").append(esc(language)).append('"');
    sb.append(",\"path\":\"").append(esc(path)).append('"');
    sb.append(",\"fileId\":\"").append(esc(fileId)).append('"');
    sb.append(",\"contentHash\":\"").append(esc(contentHash)).append('"');
    sb.append(",\"symbolIds\":[");
    for (int i = 0; i < symbolIds.size(); i++) {
      if (i > 0) {
        sb.append(',');
      }
      sb.append('"').append(esc(symbolIds.get(i))).append('"');
    }
    sb.append("]}");
    return sb.toString();
  }

  private static String esc(String s) {
    if (s == null) {
      return "";
    }
    StringBuilder b = new StringBuilder(s.length());
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> b.append("\\\"");
        case '\\' -> b.append("\\\\");
        case '\n' -> b.append("\\n");
        case '\r' -> b.append("\\r");
        case '\t' -> b.append("\\t");
        default -> {
          if (c < 0x20) {
            b.append(String.format("\\u%04x", (int) c));
          } else {
            b.append(c);
          }
        }
      }
    }
    return b.toString();
  }

  private static Set<String> markerDirs(List<SourceFile> files) {
    Set<String> dirs = new TreeSet<>();
    for (SourceFile f : files) {
      String path = f.repoRelPath();
      String name = lastSegment(path);
      if (name != null && BUILD_MARKERS.contains(name)) {
        dirs.add(parentDir(path));
      }
    }
    return dirs;
  }

  /**
   * The module root for a path: the longest marker dir that is its ancestor, else its top dir.
   */
  private static Optional<String> moduleDir(String path, Set<String> markerDirs) {
    String best = null;
    for (String d : markerDirs) {
      String prefix = d.isEmpty() ? "" : d + "/";
      if ((d.isEmpty() || path.startsWith(prefix)) && (best == null || d.length() > best.length())) {
        best = d;
      }
    }
    if (best != null) {
      return Optional.of(best);
    }
    int slash = path.indexOf('/');
    return slash > 0 ? Optional.of(path.substring(0, slash)) : Optional.empty();
  }

  private static String parentDir(String path) {
    int slash = path.lastIndexOf('/');
    return slash <= 0 ? "" : path.substring(0, slash);
  }

  private static String lastSegment(String path) {
    if (path == null || path.isBlank()) {
      return null;
    }
    int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
    return slash < 0 ? path : path.substring(slash + 1);
  }

  private static int lineCount(String content) {
    if (content == null || content.isEmpty()) {
      return 0;
    }
    int lines = 1;
    for (int i = 0; i < content.length(); i++) {
      if (content.charAt(i) == '\n') {
        lines++;
      }
    }
    return lines;
  }

  // ---- mutable accumulators ----

  private static final class FileResult {
    boolean skipped;
    int parsed;
    int symbols;
    int resolvedEdges;
    int heuristicEdges;
    int memoriesStored;
    int memoriesSuperseded;
    int graphEntities;
    int graphEdges;

    static FileResult skipped() {
      FileResult r = new FileResult();
      r.skipped = true;
      return r;
    }
  }

  private static final class Acc {
    int filesReceived;
    int filesSkippedUnchanged;
    int filesParsed;
    int filesFailed;
    int symbols;
    int resolvedEdges;
    int heuristicEdges;
    int memoriesStored;
    int memoriesSuperseded;
    int graphEntities;
    int graphEdges;

    void add(FileResult r) {
      if (r == null) {
        return;
      }
      if (r.skipped) {
        filesSkippedUnchanged++;
        return;
      }
      filesParsed += r.parsed;
      symbols += r.symbols;
      resolvedEdges += r.resolvedEdges;
      heuristicEdges += r.heuristicEdges;
      memoriesStored += r.memoriesStored;
      memoriesSuperseded += r.memoriesSuperseded;
      graphEntities += r.graphEntities;
      graphEdges += r.graphEdges;
    }

    CodeIndexSummary toSummary() {
      return new CodeIndexSummary(filesReceived, filesSkippedUnchanged, filesParsed, filesFailed,
        symbols, resolvedEdges, heuristicEdges, memoriesStored, memoriesSuperseded, graphEntities, graphEdges);
    }
  }
}
