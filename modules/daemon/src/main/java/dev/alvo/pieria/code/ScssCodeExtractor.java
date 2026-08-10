package dev.alvo.pieria.code;

import dev.alvo.pieria.domain.code.CodeRelation;
import dev.alvo.pieria.domain.code.CodeSymbolKind;
import dev.alvo.pieria.domain.code.EdgeConfidence;
import io.github.treesitter.jtreesitter.Node;
import io.github.treesitter.jtreesitter.Query;
import io.github.treesitter.jtreesitter.QueryCursor;
import io.github.treesitter.jtreesitter.QueryMatch;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Deterministic SCSS symbols and Sass-specific relations. */
final class ScssCodeExtractor implements LanguagePack.Extractor {

  private record Definition(Node node, String name, CodeSymbolKind kind) {
  }

  @Override
  public CodeParser.ParseResult extract(CodeParser.ParseInput input, Node root, Query query) {
    try (QueryCursor cursor = new QueryCursor(query)) {
      return extractMatches(input, root, cursor.findMatches(root).toList());
    }
  }

  private static CodeParser.ParseResult extractMatches(CodeParser.ParseInput input, Node root,
                                                       List<QueryMatch> matches) {
    String module = ExtractionSupport.fileModule(input.repoRelPath());
    List<Definition> definitions = matches.stream().map(ScssCodeExtractor::definition)
      .flatMap(Optional::stream)
      .sorted(Comparator.comparingInt((Definition d) -> d.node().getStartByte())
        .thenComparing(Comparator.comparingInt((Definition d) -> d.node().getEndByte()).reversed()))
      .toList();

    List<CodeParser.ParsedSymbol> symbols = new ArrayList<>();
    symbols.add(new CodeParser.ParsedSymbol(CodeSymbolKind.MODULE, lastSegment(module), module,
      input.repoRelPath(), "module", 1, Math.max(1, root.getEndPoint().row() + 1), null));
    Map<String, CodeParser.ParsedSymbol> symbolsByNode = new HashMap<>();
    Map<String, Integer> qualifiedNameOccurrences = new HashMap<>();
    for (Definition definition : definitions) {
      CodeParser.ParsedSymbol parent = nearest(definition.node(), symbolsByNode).orElse(symbols.getFirst());
      int arity = definition.kind() == CodeSymbolKind.MIXIN || definition.kind() == CodeSymbolKind.FUNCTION
        ? parameterCount(definition.node()) : -1;
      String qualifiedName = ExtractionSupport.nextQualifiedName(qualifiedNameOccurrences,
        parent.qualifiedName() + "#" + definition.name() + (arity >= 0 ? "(" + arity + ")" : ""));
      CodeParser.ParsedSymbol symbol = new CodeParser.ParsedSymbol(definition.kind(), definition.name(),
        qualifiedName, ExtractionSupport.signature(definition.node()), "module",
        definition.node().getStartPoint().row() + 1, definition.node().getEndPoint().row() + 1,
        parent.qualifiedName());
      symbols.add(symbol);
      symbolsByNode.put(key(definition.node()), symbol);
    }

    Map<String, List<CodeParser.ParsedSymbol>> byName = new HashMap<>();
    for (CodeParser.ParsedSymbol symbol : symbols) {
      byName.computeIfAbsent(symbol.name(), _ -> new ArrayList<>()).add(symbol);
    }
    List<CodeParser.ParsedEdge> edges = new ArrayList<>();
    for (QueryMatch match : matches) {
      addEdge(match, module, symbolsByNode, byName, edges);
    }
    return new CodeParser.ParseResult(symbols, edges);
  }

  private static Optional<Definition> definition(QueryMatch match) {
    Node nameNode = first(match, "def.name").orElse(null);
    if (nameNode == null) return Optional.empty();
    String name = nameNode.getText();
    if (name == null || name.isBlank()) return Optional.empty();
    if (has(match, "def.variable")) {
      if (!name.contains("$")) return Optional.empty();
      return Optional.of(new Definition(first(match, "def.variable").orElseThrow(), name, CodeSymbolKind.VARIABLE));
    }
    if (has(match, "def.mixin")) {
      return Optional.of(new Definition(first(match, "def.mixin").orElseThrow(), name, CodeSymbolKind.MIXIN));
    }
    if (has(match, "def.function")) {
      return Optional.of(new Definition(first(match, "def.function").orElseThrow(), name, CodeSymbolKind.FUNCTION));
    }
    if (has(match, "def.selector")) {
      return Optional.of(new Definition(first(match, "def.selector").orElseThrow(), normalizeSelector(name),
        CodeSymbolKind.SELECTOR));
    }
    return Optional.empty();
  }

  private static void addEdge(QueryMatch match, String module,
                              Map<String, CodeParser.ParsedSymbol> symbolsByNode,
                              Map<String, List<CodeParser.ParsedSymbol>> byName,
                              List<CodeParser.ParsedEdge> edges) {
    String capture;
    if (has(match, "ref.import")) capture = "ref.import";
    else if (has(match, "ref.include")) capture = "ref.include";
    else if (has(match, "ref.call")) capture = "ref.call";
    else if (has(match, "ref.extend")) capture = "ref.extend";
    else return;

    Node reference = first(match, capture).orElse(null);
    String target = first(match, "ref.name").map(Node::getText).orElse(null);
    if (reference == null || target == null || target.isBlank()) return;
    target = capture.equals("ref.extend") ? normalizeSelector(target) : cleanReference(target);
    String source = capture.equals("ref.import") ? module
      : nearest(reference, symbolsByNode).map(CodeParser.ParsedSymbol::qualifiedName).orElse(module);
    CodeSymbolKind targetKind = switch (capture) {
      case "ref.include" -> CodeSymbolKind.MIXIN;
      case "ref.call" -> CodeSymbolKind.FUNCTION;
      case "ref.extend" -> CodeSymbolKind.SELECTOR;
      default -> null;
    };
    List<CodeParser.ParsedSymbol> candidates = targetKind == null ? List.of()
      : byName.getOrDefault(target, List.of()).stream().filter(s -> s.kind() == targetKind).toList();
    String resolved = candidates.size() == 1 ? candidates.getFirst().qualifiedName() : null;
    if (capture.equals("ref.call") && resolved == null) {
      return; // unresolved Sass/CSS calls may be built-ins; do not create noisy heuristic edges
    }
    CodeRelation relation = capture.equals("ref.import") ? CodeRelation.IMPORTS
      : capture.equals("ref.extend") ? CodeRelation.EXTENDS : CodeRelation.CALLS;
    edges.add(new CodeParser.ParsedEdge(source, relation,
      resolved == null ? EdgeConfidence.HEURISTIC : EdgeConfidence.RESOLVED, resolved, target));
  }

  private static Optional<CodeParser.ParsedSymbol> nearest(
    Node node, Map<String, CodeParser.ParsedSymbol> symbolsByNode) {
    Node current = node.getParent().orElse(null);
    while (current != null) {
      CodeParser.ParsedSymbol symbol = symbolsByNode.get(key(current));
      if (symbol != null) return Optional.of(symbol);
      current = current.getParent().orElse(null);
    }
    return Optional.empty();
  }

  private static int parameterCount(Node definition) {
    return definition.getChildren().stream().filter(child -> child.getType().equals("parameters"))
      .findFirst().map(parameters -> (int) parameters.getChildren().stream()
        .filter(child -> child.getType().equals("parameter")).count()).orElse(0);
  }

  private static String normalizeSelector(String selector) {
    return selector.strip().replaceAll("\\s+", " ")
      .replaceAll("\\s*([>,+~])\\s*", "$1");
  }

  private static String cleanReference(String text) {
    String result = text.strip().replaceFirst(";$", "");
    if (result.length() >= 2 && ((result.startsWith("\"") && result.endsWith("\""))
      || (result.startsWith("'") && result.endsWith("'")))) {
      return result.substring(1, result.length() - 1);
    }
    return result;
  }

  private static String lastSegment(String module) {
    int slash = module.lastIndexOf('/');
    return slash >= 0 ? module.substring(slash + 1) : module;
  }

  private static String key(Node node) {
    return node.getStartByte() + ":" + node.getEndByte() + ":" + node.getType();
  }

  private static boolean has(QueryMatch match, String capture) {
    return !match.findNodes(capture).isEmpty();
  }

  private static Optional<Node> first(QueryMatch match, String capture) {
    List<Node> nodes = match.findNodes(capture);
    return nodes.isEmpty() ? Optional.empty() : Optional.of(nodes.getFirst());
  }
}
