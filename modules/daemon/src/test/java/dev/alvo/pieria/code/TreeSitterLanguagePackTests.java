package dev.alvo.pieria.code;

import dev.alvo.pieria.config.TreeSitterLibraryResolver;
import dev.alvo.pieria.config.TreeSitterProperties;
import dev.alvo.pieria.domain.code.CodeRelation;
import dev.alvo.pieria.domain.code.CodeSymbolKind;
import dev.alvo.pieria.domain.code.EdgeConfidence;
import dev.alvo.pieria.tools.os.OsFamily;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Real grammar fixtures; each test skips independently when its native pack is not installed. */
class TreeSitterLanguagePackTests {

  @ParameterizedTest(name = "{0} pack parses its real grammar")
  @MethodSource("additionalLanguageFixtures")
  void extractsSymbolsFromEveryAdditionalDefaultLanguage(String language, String path, String source,
                                                          CodeSymbolKind expectedKind,
                                                          String expectedName) {
    try (TreeSitterEngine engine = engine(Set.of())) {
      assumeTrue(engine.supports(language), language + " grammar not bundled for this platform");
      CodeParser.ParseResult result = parser(engine).parse(
        new CodeParser.ParseInput(path, language, source));

      assertThat(result.symbols()).anySatisfy(symbol -> {
        assertThat(symbol.kind()).isEqualTo(expectedKind);
        assertThat(symbol.name()).isEqualTo(expectedName);
      });
    }
  }

  private static Stream<Arguments> additionalLanguageFixtures() {
    return Stream.of(
      Arguments.of("kotlin", "src/Box.kt", """
        package demo
        class Box { fun draw(value: Int): Int = value }
        """, CodeSymbolKind.CLASS, "Box"),
      Arguments.of("scala", "src/Box.scala", """
        package demo
        class Box { def draw(value: Int): Int = value }
        """, CodeSymbolKind.CLASS, "Box"),
      Arguments.of("python", "src/widget.py", """
        class Widget:
            def draw(self, value):
                return value
        """, CodeSymbolKind.CLASS, "Widget"),
      Arguments.of("go", "widget.go", """
        package demo
        type Widget struct{}
        func Draw(value int) int { return value }
        """, CodeSymbolKind.CLASS, "Widget"),
      Arguments.of("rust", "src/lib.rs", """
        pub struct Widget;
        pub fn draw(value: i32) -> i32 { value }
        """, CodeSymbolKind.CLASS, "Widget"),
      Arguments.of("ruby", "lib/widget.rb", """
        class Widget
          def draw(value)
            value
          end
        end
        """, CodeSymbolKind.CLASS, "Widget"),
      Arguments.of("php", "src/Widget.php", """
        <?php
        namespace Demo;
        class Widget { public function draw(int $value): int { return $value; } }
        """, CodeSymbolKind.CLASS, "Widget"),
      Arguments.of("csharp", "src/Widget.cs", """
        namespace Demo;
        public class Widget { public int Draw(int value) => value; }
        """, CodeSymbolKind.CLASS, "Widget"),
      Arguments.of("c", "src/widget.c", """
        struct Widget { int value; };
        int draw(int value) { return value; }
        """, CodeSymbolKind.CLASS, "Widget"),
      Arguments.of("cpp", "src/widget.cpp", """
        class Widget { public: int draw(int value) { return value; } };
        """, CodeSymbolKind.CLASS, "Widget"),
      Arguments.of("swift", "Sources/Widget.swift", """
        public struct Widget { func draw(_ value: Int) -> Int { value } }
        """, CodeSymbolKind.CLASS, "Widget")
    );
  }

  @Test
  void extractsJavaScriptAndJsxSymbolsAndRelations() {
    try (TreeSitterEngine engine = engine(Set.of())) {
      assumeTrue(engine.supports("javascript"), "JavaScript grammar not bundled for this platform");
      CodeParser.ParseResult result = parser(engine).parse(new CodeParser.ParseInput(
        "src/widget.jsx", "javascript", """
          import { Base } from './base.js';
          const helper = (value) => value;
          export class Widget extends Base {
            render(value) { return <div>{helper(value)}</div>; }
          }
          """));

      assertThat(result.symbols()).anySatisfy(s -> {
        assertThat(s.kind()).isEqualTo(CodeSymbolKind.MODULE);
        assertThat(s.qualifiedName()).isEqualTo("src/widget");
      }).anySatisfy(s -> {
        assertThat(s.kind()).isEqualTo(CodeSymbolKind.FUNCTION);
        assertThat(s.qualifiedName()).isEqualTo("src/widget#helper(1)");
        assertThat(s.startLine()).isEqualTo(2);
      }).anySatisfy(s -> {
        assertThat(s.kind()).isEqualTo(CodeSymbolKind.METHOD);
        assertThat(s.name()).isEqualTo("render");
        assertThat(s.parentQualifiedName()).isEqualTo("src/widget.Widget");
      });
      assertResolved(result, CodeRelation.CALLS, "helper");
      assertHeuristic(result, CodeRelation.IMPORTS, "./base.js");
      assertHeuristic(result, CodeRelation.EXTENDS, "Base");
    }
  }

  @Test
  void extractsTypeScriptSpecificSymbolsAndRelations() {
    try (TreeSitterEngine engine = engine(Set.of())) {
      assumeTrue(engine.supports("typescript"), "TypeScript grammar not bundled for this platform");
      CodeParser.ParseResult result = parser(engine).parse(new CodeParser.ParseInput(
        "src/model.ts", "typescript", """
          import { Base } from './base';
          interface Shape { draw(value: number): number; }
          enum Color { Red, Blue }
          type Id = string;
          function helper(value: number): number { return value; }
          class Box extends Base implements Shape {
            draw(value: number): number { return helper(value); }
          }
          """));

      assertThat(result.symbols()).extracting(CodeParser.ParsedSymbol::kind)
        .contains(CodeSymbolKind.MODULE, CodeSymbolKind.INTERFACE, CodeSymbolKind.ENUM,
          CodeSymbolKind.TYPE_ALIAS, CodeSymbolKind.FUNCTION, CodeSymbolKind.CLASS,
          CodeSymbolKind.METHOD);
      assertThat(result.symbols()).filteredOn(s -> s.name().equals("draw"))
        .anySatisfy(s -> assertThat(s.parentQualifiedName()).isEqualTo("src/model.Shape"))
        .anySatisfy(s -> assertThat(s.parentQualifiedName()).isEqualTo("src/model.Box"));
      assertResolved(result, CodeRelation.CALLS, "helper");
      assertHeuristic(result, CodeRelation.IMPORTS, "./base");
      assertResolved(result, CodeRelation.IMPLEMENTS, "Shape");
    }
  }

  @Test
  void extractsTsxComponentAndResolvedHelperCall() {
    try (TreeSitterEngine engine = engine(Set.of())) {
      assumeTrue(engine.supports("tsx"), "TSX grammar not bundled for this platform");
      CodeParser.ParseResult result = parser(engine).parse(new CodeParser.ParseInput(
        "ui/App.tsx", "tsx", """
          type Props = { name: string };
          function helper(name: string) { return name.toUpperCase(); }
          export const App = (props: Props) => <div>{helper(props.name)}</div>;
          """));

      assertThat(result.symbols()).anySatisfy(s -> {
        assertThat(s.kind()).isEqualTo(CodeSymbolKind.FUNCTION);
        assertThat(s.qualifiedName()).isEqualTo("ui/App#App(1)");
      });
      assertResolved(result, CodeRelation.CALLS, "helper");
    }
  }

  @Test
  void extractsScssSymbolsAndSassRelations() {
    try (TreeSitterEngine engine = engine(Set.of())) {
      assumeTrue(engine.supports("scss"), "SCSS grammar not bundled for this platform");
      CodeParser.ParseResult result = parser(engine).parse(new CodeParser.ParseInput(
        "styles/cards.scss", "scss", """
          @use "theme";
          @import "legacy";
          $gap: 8px;
          @mixin padded($value) { padding: $value; }
          @function double($value) { @return $value * 2; }
          .base { display: block; }
          .card, .panel {
            @include padded($gap);
            width: double($gap);
            color: rgb(0, 0, 0);
            @extend .base;
          }
          """));

      assertThat(result.symbols()).extracting(CodeParser.ParsedSymbol::kind)
        .contains(CodeSymbolKind.MODULE, CodeSymbolKind.VARIABLE, CodeSymbolKind.MIXIN,
          CodeSymbolKind.FUNCTION, CodeSymbolKind.SELECTOR);
      assertThat(result.symbols())
        .filteredOn(s -> s.kind() == CodeSymbolKind.SELECTOR && s.startLine() == 7)
        .singleElement().satisfies(s -> {
          assertThat(s.name()).isEqualTo(".card,.panel");
          assertThat(s.parentQualifiedName()).isEqualTo("styles/cards");
        });
      assertResolved(result, CodeRelation.CALLS, "padded");
      assertResolved(result, CodeRelation.CALLS, "double");
      assertResolved(result, CodeRelation.EXTENDS, ".base");
      assertHeuristic(result, CodeRelation.IMPORTS, "theme");
      assertThat(result.edges()).noneMatch(e -> e.dstRef().equals("rgb"));
    }
  }

  @Test
  void missingGrammarDisablesOnlyItsPackAndMissingTypescriptDisablesTsAndTsx() {
    try (TreeSitterEngine engine = engine(Set.of("javascript", "typescript"))) {
      assumeTrue(engine.supports("java"), "Java grammar not bundled for this platform");
      assertThat(engine.supports("java")).isTrue();
      assertThat(engine.supports("javascript")).isFalse();
      assertThat(engine.supports("typescript")).isFalse();
      assertThat(engine.supports("tsx")).isFalse();
    }
  }

  private static TreeSitterCodeParser parser(TreeSitterEngine engine) {
    return new TreeSitterCodeParser(engine);
  }

  private static void assertResolved(CodeParser.ParseResult result, CodeRelation relation, String ref) {
    assertThat(result.edges()).filteredOn(edge -> edge.relation() == relation && edge.dstRef().equals(ref))
      .anySatisfy(edge -> {
        assertThat(edge.confidence()).isEqualTo(EdgeConfidence.RESOLVED);
        assertThat(edge.dstQualifiedName()).isNotBlank();
      });
  }

  private static void assertHeuristic(CodeParser.ParseResult result, CodeRelation relation, String ref) {
    assertThat(result.edges()).filteredOn(edge -> edge.relation() == relation && edge.dstRef().equals(ref))
      .anySatisfy(edge -> {
        assertThat(edge.confidence()).isEqualTo(EdgeConfidence.HEURISTIC);
        assertThat(edge.dstQualifiedName()).isNull();
      });
  }

  private static TreeSitterEngine engine(Set<String> missingLibraries) {
    Path platform = nativePlatformDir();
    String suffix = librarySuffix();
    Path core = platform.resolve("libtree-sitter." + suffix);
    assumeTrue(Files.isRegularFile(core), "Tree-sitter core not bundled for this platform: " + platform);
    TreeSitterLibraryResolver resolver = new TreeSitterLibraryResolver(null, null) {
      @Override
      public Optional<Path> resolveCore() {
        return Optional.of(core);
      }

      @Override
      public Optional<Path> resolveGrammar(String language) {
        if (missingLibraries.contains(language)) return Optional.empty();
        Path grammar = platform.resolve("tree-sitter-" + language + "." + suffix);
        return Files.isRegularFile(grammar) ? Optional.of(grammar) : Optional.empty();
      }
    };
    TreeSitterEngine engine = new TreeSitterEngine(resolver,
      enabledProperties());
    engine.init();
    return engine;
  }

  private static Path nativePlatformDir() {
    String override = System.getenv().getOrDefault("PIERIA_TEST_NATIVE_DIR",
      System.getProperty("pieria.test.native-dir", ""));
    if (!override.isBlank()) return Path.of(override);
    Path root = Path.of("").toAbsolutePath();
    while (root != null && !Files.isDirectory(root.resolve("packaging/native"))) root = root.getParent();
    assumeTrue(root != null, "packaging/native not found from working dir");
    String os = OsFamily.osName().toLowerCase(Locale.ROOT);
    String arch = OsFamily.osArch().toLowerCase(Locale.ROOT);
    String osToken = os.contains("mac") || os.contains("darwin") ? "macos"
      : os.contains("win") ? "windows" : "linux";
    String archToken = arch.contains("aarch64") || arch.contains("arm64") ? "aarch64" : "x86_64";
    return root.resolve("packaging/native").resolve(osToken + "-" + archToken);
  }

  private static String librarySuffix() {
    String os = OsFamily.osName().toLowerCase(Locale.ROOT);
    return os.contains("mac") || os.contains("darwin") ? "dylib" : os.contains("win") ? "dll" : "so";
  }

  private static TreeSitterProperties enabledProperties() {
    return new TreeSitterProperties(true, "");
  }
}
