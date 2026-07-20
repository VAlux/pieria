package dev.alvo.pieria.code;

import io.github.treesitter.jtreesitter.Node;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Small deterministic helpers shared by the language-specific extractors.
 */
final class ExtractionSupport {

  private static final int MAX_SIGNATURE = 200;

  private ExtractionSupport() {
  }

  static String fileModule(String path) {
    if (path == null || path.isBlank()) {
      return "<file>";
    }
    String normalized = path.replace('\\', '/');
    int dot = normalized.lastIndexOf('.');
    int slash = normalized.lastIndexOf('/');
    return dot > slash ? normalized.substring(0, dot) : normalized;
  }

  static String signature(Node definition) {
    String text = definition.getText();
    if (text == null) return "";
    int cut = text.length();
    for (char separator : new char[]{'{', '=', ';'}) {
      int index = text.indexOf(separator);
      if (index >= 0) cut = Math.min(cut, index);
    }
    String header = text.substring(0, cut).strip().replaceAll("\\s+", " ");
    return header.length() <= MAX_SIGNATURE ? header : header.substring(0, MAX_SIGNATURE);
  }

  static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  static String nextQualifiedName(Map<String, Integer> occurrences, String base) {
    int occurrence = occurrences.merge(base, 1, Integer::sum);
    return occurrence == 1 ? base : base + "~" + occurrence;
  }

  /**
   * Add stable {@code ~2}, {@code ~3}, ... suffixes only where a file contains duplicate FQNs.
   */
  static CodeParser.ParseResult withOccurrenceSuffixes(List<CodeParser.ParsedSymbol> symbols,
                                                       List<CodeParser.ParsedEdge> edges) {
    Map<String, Integer> occurrences = new HashMap<>();
    List<CodeParser.ParsedSymbol> unique = new ArrayList<>(symbols.size());
    for (CodeParser.ParsedSymbol symbol : symbols) {
      int occurrence = occurrences.merge(symbol.qualifiedName(), 1, Integer::sum);
      if (occurrence == 1) {
        unique.add(symbol);
      } else {
        unique.add(new CodeParser.ParsedSymbol(symbol.kind(), symbol.name(),
          symbol.qualifiedName() + "~" + occurrence, symbol.signature(), symbol.visibility(),
          symbol.startLine(), symbol.endLine(), symbol.parentQualifiedName()));
      }
    }
    return new CodeParser.ParseResult(unique, edges);
  }
}
