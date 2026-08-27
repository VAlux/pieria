package dev.alvo.pieria.code;

import dev.alvo.pieria.config.TreeSitterLibraryResolver;
import dev.alvo.pieria.config.TreeSitterProperties;
import io.github.treesitter.jtreesitter.InputEncoding;
import io.github.treesitter.jtreesitter.Language;
import io.github.treesitter.jtreesitter.Node;
import io.github.treesitter.jtreesitter.Parser;
import io.github.treesitter.jtreesitter.Query;
import io.github.treesitter.jtreesitter.Tree;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.SymbolLookup;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Owns the Tree-sitter native arena, loaded language/query map, bounded parser pool, and parser
 * executor. Each language pack is initialized independently: a missing grammar or invalid query
 * disables only that pack. TypeScript and TSX cache and share one native-library lookup.
 */
@Component
public class TreeSitterEngine implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(TreeSitterEngine.class);

  private static final int MAX_PARSER_THREADS = 4;
  private final TreeSitterLibraryResolver resolver;
  private final TreeSitterProperties properties;
  private final Map<String, LoadedLanguage> languages = new HashMap<>();
  private final List<Parser> parsers = new ArrayList<>();
  private Arena languageArena;
  private BlockingQueue<Parser> parserPool;
  private ExecutorService executor;
  private volatile boolean closing;
  public TreeSitterEngine(TreeSitterLibraryResolver resolver, TreeSitterProperties properties) {
    this.resolver = resolver;
    this.properties = properties;
  }

  private static String loadResource(String path) {
    try (InputStream in = TreeSitterEngine.class.getClassLoader().getResourceAsStream(path)) {
      if (in == null) throw new IllegalStateException("missing classpath resource: " + path);
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new IllegalStateException("could not read " + path, e);
    }
  }

  private static void closeQuery(Query query) {
    try {
      query.close();
    } catch (RuntimeException ignored) {
      // best effort during shutdown or partial pack initialization
    }
  }

  @PostConstruct
  void init() {
    if (!properties.enabled()) {
      log.info("Tree-sitter disabled (pieria.treesitter.enabled=false); code symbol parsing is off.");
      return;
    }
    Path core = resolver.resolveCore().orElse(null);
    if (core == null) {
      log.warn("Tree-sitter core libtree-sitter not found; code symbol parsing is off "
        + "(index degrades to file/module facts).");
      return;
    }
    System.setProperty(PieriaTreeSitterLibraryLookup.CORE_PATH_PROPERTY, core.toString());

    languageArena = Arena.ofShared();
    // Optional as a map value here memoizes a negative lookup too: computeIfAbsent recomputes on
    // every call for a function that returns null, so a plain Path cache would re-extract the
    // grammar for every language sharing a not-found native library.
    Map<String, Optional<Path>> paths = new HashMap<>();
    Map<Path, SymbolLookup> lookups = new HashMap<>();

    for (LanguagePack pack : LanguagePackRegistry.packs()) {
      Path grammarPath = paths.computeIfAbsent(pack.nativeLibrary(), resolver::resolveGrammar).orElse(null);
      if (grammarPath == null) {
        log.warn("Tree-sitter {} grammar not found; {} parsing is off.", pack.nativeLibrary(), pack.id());
        continue;
      }

      Query query = null;
      try {
        SymbolLookup lookup = lookups.computeIfAbsent(grammarPath,
          path -> SymbolLookup.libraryLookup(path, languageArena));
        Language language = Language.load(lookup, pack.grammarSymbol());
        query = new Query(language, loadResource(pack.queryResource()));
        languages.put(pack.id(), new LoadedLanguage(language, query));
        log.info("Tree-sitter pack ready: {} grammar abiVersion={}.", pack.id(), language.getAbiVersion());
      } catch (RuntimeException | LinkageError e) {
        if (query != null) closeQuery(query);
        log.warn("Tree-sitter {} pack disabled ({}).", pack.id(), e.toString());
      }
    }

    if (languages.isEmpty()) {
      closeQuietly();
      return;
    }

    int threads = Math.clamp(Runtime.getRuntime().availableProcessors(), 1, MAX_PARSER_THREADS);
    parserPool = new ArrayBlockingQueue<>(threads);
    for (int i = 0; i < threads; i++) {
      Parser parser = new Parser();
      parsers.add(parser);
      parserPool.add(parser);
    }

    executor = Executors.newFixedThreadPool(threads, runnable -> {
      Thread thread = new Thread(runnable, "ts-parser");
      thread.setDaemon(true);
      return thread;
    });

    log.info("Tree-sitter ready: packs={} parsers={}.", languages.keySet(), threads);
  }

  public boolean supports(String language) {
    return !closing && language != null && languages.containsKey(language.toLowerCase(Locale.ROOT));
  }

  public <R> Optional<R> parse(String language, String source, ParseHandler<R> handler) {
    if (!supports(language) || executor == null) {
      return Optional.empty();
    }

    LoadedLanguage loaded = languages.get(language.toLowerCase(Locale.ROOT));
    try {
      return executor.submit(() -> runParse(loaded, source, handler)).get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Optional.empty();
    } catch (ExecutionException | RuntimeException e) {
      Throwable cause = e instanceof ExecutionException ? e.getCause() : e;
      log.warn("Tree-sitter {} parse failed.", language, cause);
      return Optional.empty();
    }
  }

  private <R> Optional<R> runParse(LoadedLanguage loaded, String source, ParseHandler<R> handler)
    throws InterruptedException {
    Parser parser = parserPool.take();
    try {
      parser.setLanguage(loaded.language());
      Tree tree = parser.parse(source == null ? "" : source, InputEncoding.UTF_8).orElse(null);
      if (tree == null) return Optional.empty();
      try (tree) {
        String text = source == null ? "" : source;
        return Optional.ofNullable(handler.handle(tree.getRootNode(), loaded.query(), text));
      }
    } finally {
      if (!closing) parserPool.put(parser);
    }
  }

  @PreDestroy
  @Override
  public void close() {
    closing = true;
    closeQuietly();
  }

  private void closeQuietly() {
    closing = true;
    if (executor != null) {
      executor.shutdownNow();
      try {
        executor.awaitTermination(5, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      executor = null;
    }
    for (Parser parser : parsers) {
      try {
        parser.close();
      } catch (RuntimeException ignored) {
        // best effort during shutdown
      }
    }
    parsers.clear();
    parserPool = null;
    for (LoadedLanguage loaded : languages.values()) {
      closeQuery(loaded.query());
    }
    languages.clear();
    if (languageArena != null) {
      try {
        languageArena.close();
      } catch (RuntimeException ignored) {
        // best effort during shutdown
      }
      languageArena = null;
    }
  }

  @FunctionalInterface
  public interface ParseHandler<R> {
    R handle(Node root, Query tags, String source);
  }

  private record LoadedLanguage(Language language, Query query) {
  }
}
