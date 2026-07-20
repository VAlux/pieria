package dev.alvo.pieria.code;

import dev.alvo.pieria.code.CodeParser.ParsedEdge;
import dev.alvo.pieria.code.CodeParser.ParsedSymbol;
import dev.alvo.pieria.config.TreeSitterLibraryResolver;
import dev.alvo.pieria.config.TreeSitterProperties;
import dev.alvo.pieria.domain.code.CodeRelation;
import dev.alvo.pieria.domain.code.CodeSymbolKind;
import dev.alvo.pieria.domain.code.EdgeConfidence;
import dev.alvo.pieria.tools.os.OsFamily;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Exercises the real Tree-sitter Java parser against the bundled grammar (no network). Skips
 * gracefully on platforms where the libraries are not bundled under {@code packaging/native/}.
 */
class TreeSitterJavaParserTests {

  private static final String FIXTURE = """
    package com.example;

    import java.util.List;

    public class Foo extends Bar implements Runnable {
      private int count;

      public Foo(int count) {
        this.count = count;
      }

      public void run() {
        helper();
        System.out.println(count);
      }

      private int helper() {
        return count;
      }
    }
    """;

  private static TreeSitterLibraryResolver resolverPointingAtBundledLibs() {
    Path root = Path.of("").toAbsolutePath();
    while (root != null && !Files.isDirectory(root.resolve("packaging/native"))) {
      root = root.getParent();
    }
    assumeTrue(root != null, "packaging/native not found from working dir");

    String os = OsFamily.osName().toLowerCase(Locale.ROOT);
    String arch = OsFamily.osArch().toLowerCase(Locale.ROOT);
    String osTok = os.contains("mac") || os.contains("darwin") ? "macos" : os.contains("win") ? "windows" : "linux";
    String archTok = arch.contains("aarch64") || arch.contains("arm64") ? "aarch64" : "x86_64";
    String suffix = "macos".equals(osTok) ? "dylib" : "windows".equals(osTok) ? "dll" : "so";

    Path platform = root.resolve("packaging/native").resolve(osTok + "-" + archTok);
    Path core = platform.resolve("libtree-sitter." + suffix);
    Path grammar = platform.resolve("tree-sitter-java." + suffix);
    assumeTrue(Files.isRegularFile(core) && Files.isRegularFile(grammar),
      "Tree-sitter libraries not bundled for this platform: " + platform);

    return new TreeSitterLibraryResolver(null, null) {
      @Override
      public Optional<Path> resolveCore() {
        return Optional.of(core);
      }

      @Override
      public Optional<Path> resolveGrammar(String language) {
        return "java".equals(language) ? Optional.of(grammar) : Optional.empty();
      }
    };
  }

  private static TreeSitterEngine engine(boolean enabled) {
    TreeSitterEngine engine = new TreeSitterEngine(
      resolverPointingAtBundledLibs(), new TreeSitterProperties(enabled, ""));
    engine.init();
    return engine;
  }

  @Test
  void extractsSymbolsAndResolvedAndHeuristicEdges() {
    TreeSitterEngine engine = engine(true);
    try {
      assumeTrue(engine.supports("java"), "Tree-sitter engine unavailable");
      TreeSitterCodeParser parser = new TreeSitterCodeParser(engine);

      CodeParser.ParseResult result = parser.parse(
        new CodeParser.ParseInput("com/example/Foo.java", "java", FIXTURE));

      // Symbols: the class, its members, and the package.
      assertThat(result.symbols())
        .anySatisfy(s -> assertThat(s).extracting(ParsedSymbol::kind, ParsedSymbol::name, ParsedSymbol::qualifiedName)
          .containsExactly(CodeSymbolKind.CLASS, "Foo", "com.example.Foo"))
        .anySatisfy(s -> assertThat(s).extracting(ParsedSymbol::kind, ParsedSymbol::name)
          .containsExactly(CodeSymbolKind.METHOD, "helper"))
        .anySatisfy(s -> assertThat(s).extracting(ParsedSymbol::kind, ParsedSymbol::name)
          .containsExactly(CodeSymbolKind.FIELD, "count"));

      // A within-file call (run -> helper) is a RESOLVED CALLS edge pointing at helper's FQN.
      assertThat(result.edges()).anySatisfy(e -> {
        assertThat(e.relation()).isEqualTo(CodeRelation.CALLS);
        assertThat(e.confidence()).isEqualTo(EdgeConfidence.RESOLVED);
        assertThat(e.dstQualifiedName()).isNotNull().contains("helper");
      });

      // A cross-file relation is HEURISTIC (extends Bar) carrying only a dstRef.
      assertThat(result.edges()).anySatisfy(e -> {
        assertThat(e.relation()).isEqualTo(CodeRelation.EXTENDS);
        assertThat(e.confidence()).isEqualTo(EdgeConfidence.HEURISTIC);
        assertThat(e.dstRef()).isEqualTo("Bar");
      });
    } finally {
      engine.close();
    }
  }

  @Test
  void disabledEngineDegradesToEmptyWithoutThrowing() {
    TreeSitterEngine engine = engine(false);
    try {
      TreeSitterCodeParser parser = new TreeSitterCodeParser(engine);
      assertThat(parser.supports("java")).isFalse();
      assertThat(parser.parse(new CodeParser.ParseInput("X.java", "java", FIXTURE)).symbols()).isEmpty();
    } finally {
      engine.close();
    }
  }
}
