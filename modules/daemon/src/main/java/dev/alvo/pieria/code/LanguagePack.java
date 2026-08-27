package dev.alvo.pieria.code;

import io.github.treesitter.jtreesitter.Node;
import io.github.treesitter.jtreesitter.Query;

import java.util.Set;

/**
 * Immutable description of one source-code language pack. Native loading is intentionally data
 * driven: packs sharing {@link #nativeLibrary()} also share the same {@code dlopen} lookup.
 */
public record LanguagePack(
  String id,
  Set<String> extensions,
  String nativeLibrary,
  String grammarSymbol,
  String queryResource,
  Extractor extractor) {

  public LanguagePack {
    extensions = Set.copyOf(extensions);
  }

  /**
   * Deterministic conversion from query captures to the parser's storage-neutral result.
   */
  @FunctionalInterface
  public interface Extractor {
    CodeParser.ParseResult extract(CodeParser.ParseInput input, Node root, Query query);
  }
}
