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
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Owns the long-lived Tree-sitter runtime: the shared {@link Arena} backing the loaded grammar
 * {@link Language} and compiled {@link Query}, a small pool of {@link Parser}s, and a bounded
 * platform-thread executor that parsing runs on.
 *
 * <p>Why a platform-thread executor: a {@code Parser} is not thread-safe (so it is pooled), and FFM
 * native work must not pin a virtual thread — callers may be on virtual threads, so {@link #parse}
 * dispatches to this executor and blocks on the result. Each parse closes its {@link Tree}
 * (try-with-resources) so off-heap memory is freed and large repos do not leak.
 *
 * <p>Degradable by construction: if {@code pieria.treesitter.enabled=false} or the native libraries
 * are missing, the engine reports {@link #supports} {@code false} and {@link #parse} returns empty,
 * so {@link TreeSitterCodeParser} extracts nothing and the code index degrades exactly as before.
 */
@Component
public class TreeSitterEngine implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(TreeSitterEngine.class);
  private static final int MAX_PARSERS = 4;

  private final TreeSitterLibraryResolver resolver;
  private final TreeSitterProperties properties;

  private Arena arena;
  private Language javaLanguage;
  private Query javaTags;
  private BlockingQueue<Parser> parserPool;
  private ExecutorService executor;
  private volatile boolean available;

  public TreeSitterEngine(TreeSitterLibraryResolver resolver, TreeSitterProperties properties) {
    this.resolver = resolver;
    this.properties = properties;
  }

  /** A handler invoked with the parsed tree's root while it is still alive; must return plain data. */
  @FunctionalInterface
  public interface ParseHandler<R> {
    R handle(Node root, Query tags, String source);
  }

  @PostConstruct
  void init() {
    if (!properties.enabled()) {
      log.info("Tree-sitter disabled (pieria.treesitter.enabled=false); code symbol parsing is off.");
      return;
    }
    Optional<Path> core = resolver.resolveCore();
    if (core.isEmpty()) {
      log.warn("Tree-sitter core libtree-sitter not found; code symbol parsing is off "
        + "(index degrades to file/module facts).");
      return;
    }
    // Hand the core path to the jtreesitter ServiceLoader lookup BEFORE the first jtreesitter call.
    System.setProperty(PieriaTreeSitterLibraryLookup.CORE_PATH_PROPERTY, core.get().toString());

    Optional<Path> javaGrammar = resolver.resolveGrammar("java");
    if (javaGrammar.isEmpty()) {
      log.warn("Tree-sitter java grammar not found; Java symbol parsing is off.");
      return;
    }
    try {
      arena = Arena.ofShared();
      SymbolLookup grammar = SymbolLookup.libraryLookup(javaGrammar.get(), arena);
      javaLanguage = Language.load(grammar, "tree_sitter_java");
      javaTags = new Query(javaLanguage, loadResource("code/langpack/java/tags.scm"));

      int threads = Math.max(1, Math.min(Runtime.getRuntime().availableProcessors(), MAX_PARSERS));
      parserPool = new ArrayBlockingQueue<>(threads);
      for (int i = 0; i < threads; i++) {
        Parser parser = new Parser();
        parser.setLanguage(javaLanguage);
        parserPool.add(parser);
      }
      executor = Executors.newFixedThreadPool(threads, runnable -> {
        Thread thread = new Thread(runnable, "ts-parser");
        thread.setDaemon(true);
        return thread;
      });
      available = true;
      log.info("Tree-sitter ready: java grammar abiVersion={} parsers={}.",
        javaLanguage.getAbiVersion(), threads);
    } catch (RuntimeException e) {
      log.warn("Tree-sitter init failed ({}); code symbol parsing is off.", e.toString());
      closeQuietly();
      available = false;
    }
  }

  /** Whether this engine can parse {@code language} (currently only {@code java}). */
  public boolean supports(String language) {
    return available && "java".equals(language) && javaLanguage != null;
  }

  /**
   * Parse {@code source} for {@code language} and run {@code handler} against the live tree on the
   * parser executor, returning the handler's result. Empty when unsupported or parsing fails — never
   * throws (the code-index pipeline treats absence as "no symbols").
   */
  public <R> Optional<R> parse(String language, String source, ParseHandler<R> handler) {
    if (!supports(language)) {
      return Optional.empty();
    }
    try {
      return executor.submit(() -> runParse(source, handler)).get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Optional.empty();
    } catch (ExecutionException e) {
      log.warn("Tree-sitter parse failed: {}", String.valueOf(e.getCause()));
      return Optional.empty();
    }
  }

  private <R> Optional<R> runParse(String source, ParseHandler<R> handler) throws InterruptedException {
    String text = source == null ? "" : source;
    Parser parser = parserPool.take();
    try {
      Optional<Tree> parsed = parser.parse(text, InputEncoding.UTF_8);
      if (parsed.isEmpty()) {
        return Optional.empty();
      }
      try (Tree tree = parsed.get()) {
        return Optional.ofNullable(handler.handle(tree.getRootNode(), javaTags, text));
      }
    } finally {
      parserPool.put(parser);
    }
  }

  private static String loadResource(String path) {
    try (InputStream in = TreeSitterEngine.class.getClassLoader().getResourceAsStream(path)) {
      if (in == null) {
        throw new IllegalStateException("missing classpath resource: " + path);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new IllegalStateException("could not read " + path, e);
    }
  }

  @PreDestroy
  @Override
  public void close() {
    available = false;
    closeQuietly();
  }

  private void closeQuietly() {
    if (executor != null) {
      executor.shutdownNow();
    }
    if (parserPool != null) {
      Parser parser;
      while ((parser = parserPool.poll()) != null) {
        try {
          parser.close();
        } catch (RuntimeException ignored) {
          // best-effort on shutdown
        }
      }
    }
    if (javaTags != null) {
      try {
        javaTags.close();
      } catch (RuntimeException ignored) {
        // best-effort
      }
    }
    if (arena != null) {
      try {
        arena.close();
      } catch (RuntimeException ignored) {
        // best-effort
      }
    }
  }
}
