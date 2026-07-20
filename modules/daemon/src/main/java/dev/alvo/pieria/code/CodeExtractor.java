package dev.alvo.pieria.code;

import dev.alvo.pieria.code.CodeParser.ParseResult;
import dev.alvo.pieria.code.CodeParser.ParsedEdge;
import dev.alvo.pieria.code.CodeParser.ParsedSymbol;
import dev.alvo.pieria.code.LanguagePack.Extractor;
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

/**
 * Shared capture interpreter for language packs whose tag queries use Pieria's common
 * {@code def.*}/{@code ref.*} vocabulary. Language-specific syntax stays in the query; this class
 * supplies stable file qualification, nesting, arity, visibility, and local edge resolution.
 */
final class CodeExtractor implements Extractor {

  private record Definition(Node node, Node name, CodeSymbolKind kind) {
  }

  @Override
  public ParseResult extract(CodeParser.ParseInput input, Node root, Query query) {
    try (QueryCursor cursor = new QueryCursor(query)) {
      List<QueryMatch> matches = cursor.findMatches(root).toList();
      return extractMatches(input, root, matches);
    }
  }

  private static ParseResult extractMatches(CodeParser.ParseInput input, Node root, List<QueryMatch> matches) {
    String fileModule = ExtractionSupport.fileModule(input.repoRelPath());
    Map<String, Definition> definitionsByNode = new HashMap<>();
    matches.stream().map(CodeExtractor::definition).flatMap(Optional::stream)
      .forEach(definition -> definitionsByNode.merge(key(definition.node()), definition,
        (left, right) -> rank(right.kind()) > rank(left.kind()) ? right : left));
    List<Definition> definitions = definitionsByNode.values().stream()
      .sorted(Comparator.comparingInt((Definition d) -> d.node().getStartByte())
        .thenComparing(Comparator.comparingInt((Definition d) -> d.node().getEndByte()).reversed()))
      .toList();

    List<ParsedSymbol> symbols = new ArrayList<>();
    ParsedSymbol file = new ParsedSymbol(CodeSymbolKind.MODULE,
      lastSegment(fileModule), fileModule, input.repoRelPath(), "module", 1,
      Math.max(1, root.getEndPoint().row() + 1), null);
    symbols.add(file);

    Map<String, ParsedSymbol> symbolsByNode = new HashMap<>();
    Map<String, Integer> occurrences = new HashMap<>();
    ParsedSymbol packageSymbol = null;
    for (Definition definition : definitions) {
      String name = cleanName(definition.name().getText());
      if (name == null || name.isBlank()) continue;
      ParsedSymbol parent = nearest(definition.node(), symbolsByNode)
        .orElse(packageSymbol == null ? file : packageSymbol);
      int arity = callable(definition.kind()) ? parameterCount(definition.node()) : -1;
      String base = qualified(parent.qualifiedName(), name, definition.kind(), arity);
      if (definition.kind() == CodeSymbolKind.PACKAGE) base = name;
      String qualifiedName = ExtractionSupport.nextQualifiedName(occurrences, base);
      ParsedSymbol symbol = new ParsedSymbol(definition.kind(), name,
        qualifiedName, ExtractionSupport.signature(definition.node()), visibility(definition.node()),
        definition.node().getStartPoint().row() + 1, definition.node().getEndPoint().row() + 1,
        parent.qualifiedName());
      symbols.add(symbol);
      symbolsByNode.put(key(definition.node()), symbol);
      if (definition.kind() == CodeSymbolKind.PACKAGE && packageSymbol == null) {
        packageSymbol = symbol;
      }
    }

    Map<String, List<ParsedSymbol>> targets = new HashMap<>();
    for (ParsedSymbol symbol : symbols) {
      targets.computeIfAbsent(simpleName(symbol.name()), _ -> new ArrayList<>()).add(symbol);
    }
    List<ParsedEdge> edges = new ArrayList<>();
    for (QueryMatch match : matches) {
      addEdge(match, fileModule, symbolsByNode, targets, edges);
    }
    return new ParseResult(symbols, edges);
  }

  private static Optional<Definition> definition(QueryMatch match) {
    Node name = first(match, "def.name").orElse(null);
    if (name == null) return Optional.empty();
    for (Map.Entry<String, CodeSymbolKind> capture : List.of(
      Map.entry("def.package", CodeSymbolKind.PACKAGE),
      Map.entry("def.module", CodeSymbolKind.MODULE),
      Map.entry("def.class", CodeSymbolKind.CLASS),
      Map.entry("def.interface", CodeSymbolKind.INTERFACE),
      Map.entry("def.enum", CodeSymbolKind.ENUM),
      Map.entry("def.type_alias", CodeSymbolKind.TYPE_ALIAS),
      Map.entry("def.function", CodeSymbolKind.FUNCTION),
      Map.entry("def.method", CodeSymbolKind.METHOD),
      Map.entry("def.field", CodeSymbolKind.FIELD),
      Map.entry("def.variable", CodeSymbolKind.VARIABLE))) {
      Node node = first(match, capture.getKey()).orElse(null);
      if (node != null) return Optional.of(new Definition(node, name, capture.getValue()));
    }
    return Optional.empty();
  }

  private static int rank(CodeSymbolKind kind) {
    return switch (kind) {
      case INTERFACE, ENUM -> 4;
      case METHOD -> 3;
      case CLASS, FUNCTION -> 2;
      default -> 1;
    };
  }

  private static void addEdge(QueryMatch match,
                              String fileModule,
                              Map<String, ParsedSymbol> symbolsByNode,
                              Map<String, List<ParsedSymbol>> targets,
                              List<ParsedEdge> edges) {
    String capture;
    CodeRelation relation;

    if (has(match, "ref.import")) {
      capture = "ref.import";
      relation = CodeRelation.IMPORTS;
    } else if (has(match, "ref.call")) {
      capture = "ref.call";
      relation = CodeRelation.CALLS;
    } else if (has(match, "ref.extends")) {
      capture = "ref.extends";
      relation = CodeRelation.EXTENDS;
    } else if (has(match, "ref.implements")) {
      capture = "ref.implements";
      relation = CodeRelation.IMPLEMENTS;
    } else {
      return;
    }

    Node reference = first(match, capture).orElse(null);
    String targetName = first(match, "ref.name").map(Node::getText).map(CodeExtractor::cleanName)
      .orElse(null);
    if (reference == null || targetName == null || targetName.isBlank()) return;
    String source = relation == CodeRelation.IMPORTS ? fileModule
      : nearest(reference, symbolsByNode).map(ParsedSymbol::qualifiedName).orElse(fileModule);
    List<ParsedSymbol> candidates = targets.getOrDefault(simpleName(targetName), List.of())
      .stream().filter(symbol -> validTarget(relation, symbol.kind())).toList();
    String resolved = candidates.size() == 1 ? candidates.getFirst().qualifiedName() : null;
    edges.add(new ParsedEdge(source, relation,
      resolved == null ? EdgeConfidence.HEURISTIC : EdgeConfidence.RESOLVED, resolved, targetName));
  }

  private static boolean validTarget(CodeRelation relation, CodeSymbolKind kind) {
    return switch (relation) {
      case CALLS -> kind == CodeSymbolKind.FUNCTION || kind == CodeSymbolKind.METHOD;
      case EXTENDS, IMPLEMENTS -> kind == CodeSymbolKind.CLASS || kind == CodeSymbolKind.INTERFACE;
      default -> false;
    };
  }

  private static Optional<ParsedSymbol> nearest(
    Node node, Map<String, ParsedSymbol> symbolsByNode) {
    Optional<Node> current = node.getParent();
    while (current.isPresent()) {
      ParsedSymbol symbol = symbolsByNode.get(key(current.get()));
      if (symbol != null) return Optional.of(symbol);
      current = current.get().getParent();
    }
    return Optional.empty();
  }

  private static int parameterCount(Node definition) {
    Node parameters = findParameterContainer(definition);
    if (parameters == null) return 0;
    return (int) parameters.getChildren().stream().filter(Node::isNamed)
      .filter(child -> child.getType().contains("parameter") || child.getType().equals("identifier"))
      .count();
  }

  private static Node findParameterContainer(Node node) {
    for (Node child : node.getChildren()) {
      String type = child.getType();
      if (type.equals("parameters") || type.equals("parameter_list")
        || type.equals("formal_parameters") || type.equals("function_value_parameters")
        || type.equals("method_parameters")) {
        return child;
      }
      if (!type.contains("body") && !type.equals("block") && !type.equals("declaration_list")) {
        Node found = findParameterContainer(child);
        if (found != null) return found;
      }
    }
    return null;
  }

  private static String visibility(Node definition) {
    String text = definition.getText() == null ? "" : definition.getText().stripLeading();
    if (text.startsWith("private ") || text.startsWith("private[") || text.startsWith("fileprivate ")) {
      return "private";
    }
    if (text.startsWith("protected ") || text.startsWith("protected[")) return "protected";
    if (text.startsWith("public ") || text.startsWith("export ") || text.startsWith("pub ")
      || text.startsWith("open ")) return "public";
    return "module";
  }

  private static String qualified(String parent, String name, CodeSymbolKind kind, int arity) {
    if (kind == CodeSymbolKind.CLASS || kind == CodeSymbolKind.INTERFACE
      || kind == CodeSymbolKind.ENUM || kind == CodeSymbolKind.TYPE_ALIAS
      || kind == CodeSymbolKind.MODULE || kind == CodeSymbolKind.PACKAGE) {
      return parent + "." + name;
    }
    return parent + "#" + name + (callable(kind) ? "(" + arity + ")" : "");
  }

  private static boolean callable(CodeSymbolKind kind) {
    return kind == CodeSymbolKind.FUNCTION || kind == CodeSymbolKind.METHOD;
  }

  private static String cleanName(String value) {
    if (value == null) return null;
    String result = value.strip().replaceFirst(";$", "");
    if (result.length() >= 2 && ((result.startsWith("\"") && result.endsWith("\""))
      || (result.startsWith("'") && result.endsWith("'"))
      || (result.startsWith("<") && result.endsWith(">")))) {
      return result.substring(1, result.length() - 1);
    }
    return result;
  }

  private static String simpleName(String value) {
    String clean = cleanName(value);
    int split = Math.max(Math.max(clean.lastIndexOf('.'), clean.lastIndexOf('/')),
      Math.max(clean.lastIndexOf(':'), clean.lastIndexOf('\\')));
    return split >= 0 ? clean.substring(split + 1) : clean;
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
