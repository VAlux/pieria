package dev.alvo.pieria.code;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Built-in Tree-sitter language packs, in deterministic load order. */
public final class LanguagePackRegistry {

  private static final List<LanguagePack> PACKS = List.of(
    new LanguagePack("java", Set.of("java"), "java", "tree_sitter_java",
      "code/langpack/java/tags.scm", new JavaCodeExtractor()),
    generic("kotlin", Set.of("kt", "kts"), "tree_sitter_kotlin"),
    generic("scala", Set.of("scala", "sc"), "tree_sitter_scala"),
    new LanguagePack("javascript", Set.of("js", "jsx", "mjs", "cjs"), "javascript",
      "tree_sitter_javascript", "code/langpack/javascript/tags.scm", new JavaScriptCodeExtractor(false)),
    new LanguagePack("typescript", Set.of("ts", "mts", "cts"), "typescript",
      "tree_sitter_typescript", "code/langpack/typescript/tags.scm", new JavaScriptCodeExtractor(true)),
    new LanguagePack("tsx", Set.of("tsx"), "typescript", "tree_sitter_tsx",
      "code/langpack/tsx/tags.scm", new JavaScriptCodeExtractor(true)),
    new LanguagePack("scss", Set.of("scss"), "scss", "tree_sitter_scss",
      "code/langpack/scss/tags.scm", new ScssCodeExtractor()),
    generic("python", Set.of("py"), "tree_sitter_python"),
    generic("go", Set.of("go"), "tree_sitter_go"),
    generic("rust", Set.of("rs"), "tree_sitter_rust"),
    generic("ruby", Set.of("rb"), "tree_sitter_ruby"),
    generic("php", Set.of("php"), "tree_sitter_php"),
    generic("csharp", Set.of("cs"), "c-sharp", "tree_sitter_c_sharp"),
    generic("c", Set.of("c", "h"), "tree_sitter_c"),
    generic("cpp", Set.of("cpp", "cc", "hpp"), "tree_sitter_cpp"),
    generic("swift", Set.of("swift"), "tree_sitter_swift")
  );

  private static final Map<String, LanguagePack> BY_ID;
  private static final Map<String, String> BY_EXTENSION;

  static {
    Map<String, LanguagePack> ids = new LinkedHashMap<>();
    Map<String, String> extensions = new LinkedHashMap<>();
    for (LanguagePack pack : PACKS) {
      ids.put(pack.id(), pack);
      for (String extension : pack.extensions()) {
        extensions.put(extension, pack.id());
      }
    }
    BY_ID = Map.copyOf(ids);
    BY_EXTENSION = Map.copyOf(extensions);
  }

  private LanguagePackRegistry() {
  }

  private static LanguagePack generic(String id, Set<String> extensions, String grammarSymbol) {
    return generic(id, extensions, id, grammarSymbol);
  }

  private static LanguagePack generic(String id, Set<String> extensions, String nativeLibrary,
                                      String grammarSymbol) {
    return new LanguagePack(id, extensions, nativeLibrary, grammarSymbol,
      "code/langpack/" + id + "/tags.scm", new CodeExtractor());
  }

  public static List<LanguagePack> packs() {
    return PACKS;
  }

  public static Optional<LanguagePack> find(String language) {
    if (language == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(BY_ID.get(language.toLowerCase(Locale.ROOT)));
  }

  public static Map<String, String> byExtension() {
    return BY_EXTENSION;
  }
}
