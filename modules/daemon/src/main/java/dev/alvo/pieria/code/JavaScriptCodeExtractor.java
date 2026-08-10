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

/** Shared deterministic extractor for JavaScript, TypeScript, and TSX. */
final class JavaScriptCodeExtractor implements LanguagePack.Extractor {

  private final boolean typed;

  JavaScriptCodeExtractor(boolean typed) {
    this.typed = typed;
  }

  private record Definition(Node node, Node name, CodeSymbolKind kind) {
  }

  @Override
  public CodeParser.ParseResult extract(CodeParser.ParseInput input, Node root, Query query) {
    try (QueryCursor cursor = new QueryCursor(query)) {
      return extractMatches(input, root, cursor.findMatches(root).toList());
    }
  }

  private CodeParser.ParseResult extractMatches(CodeParser.ParseInput input, Node root,
                                                List<QueryMatch> matches) {
    String module = ExtractionSupport.fileModule(input.repoRelPath());
    Map<String, Definition> definitionsByNode = new HashMap<>();
    matches.stream().map(this::definition).flatMap(Optional::stream)
      .forEach(definition -> definitionsByNode.merge(nodeKey(definition.node()), definition,
        (left, right) -> callable(right.kind()) ? right : left));
    List<Definition> definitions = definitionsByNode.values().stream()
      .sorted(Comparator.comparingInt((Definition d) -> d.node().getStartByte())
        .thenComparing(Comparator.comparingInt((Definition d) -> d.node().getEndByte()).reversed()))
      .toList();

    List<CodeParser.ParsedSymbol> symbols = new ArrayList<>();
    symbols.add(new CodeParser.ParsedSymbol(CodeSymbolKind.MODULE, moduleName(module), module,
      input.repoRelPath(), "module", 1, Math.max(1, root.getEndPoint().row() + 1), null));

    Map<String, CodeParser.ParsedSymbol> symbolsByNode = new HashMap<>();
    Map<String, Integer> qualifiedNameOccurrences = new HashMap<>();
    for (Definition definition : definitions) {
      String name = definition.name().getText();
      if (name == null || name.isBlank()) continue;
      CodeParser.ParsedSymbol parent = nearestDefinition(definition.node(), symbolsByNode).orElse(symbols.getFirst());
      int arity = callable(definition.kind()) ? countParameters(definition.node()) : -1;
      String qualifiedName = ExtractionSupport.nextQualifiedName(qualifiedNameOccurrences,
        qualified(parent.qualifiedName(), name, definition.kind(), arity));
      CodeParser.ParsedSymbol symbol = new CodeParser.ParsedSymbol(definition.kind(), name,
        qualifiedName, ExtractionSupport.signature(definition.node()), visibility(definition.node()),
        definition.node().getStartPoint().row() + 1, definition.node().getEndPoint().row() + 1,
        parent.qualifiedName());
      symbols.add(symbol);
      symbolsByNode.put(nodeKey(definition.node()), symbol);
    }

    Map<String, List<CodeParser.ParsedSymbol>> targetsByName = new HashMap<>();
    for (CodeParser.ParsedSymbol symbol : symbols) {
      targetsByName.computeIfAbsent(symbol.name(), _ -> new ArrayList<>()).add(symbol);
    }
    List<CodeParser.ParsedEdge> edges = new ArrayList<>();
    for (QueryMatch match : matches) {
      addEdge(match, module, symbolsByNode, targetsByName, edges);
    }
    return new CodeParser.ParseResult(symbols, edges);
  }

  private Optional<Definition> definition(QueryMatch match) {
    Node name = first(match, "def.name").orElse(null);
    if (name == null) return Optional.empty();
    List<Map.Entry<String, CodeSymbolKind>> captures = new ArrayList<>();
    captures.add(Map.entry("def.class", CodeSymbolKind.CLASS));
    if (typed) {
      captures.add(Map.entry("def.interface", CodeSymbolKind.INTERFACE));
      captures.add(Map.entry("def.enum", CodeSymbolKind.ENUM));
      captures.add(Map.entry("def.type_alias", CodeSymbolKind.TYPE_ALIAS));
    }
    captures.add(Map.entry("def.function", CodeSymbolKind.FUNCTION));
    captures.add(Map.entry("def.method", CodeSymbolKind.METHOD));
    captures.add(Map.entry("def.field", CodeSymbolKind.FIELD));
    captures.add(Map.entry("def.variable", CodeSymbolKind.VARIABLE));
    for (Map.Entry<String, CodeSymbolKind> entry : captures) {
      Node node = first(match, entry.getKey()).orElse(null);
      if (node != null) return Optional.of(new Definition(node, name, entry.getValue()));
    }
    return Optional.empty();
  }

  private void addEdge(QueryMatch match, String module,
                              Map<String, CodeParser.ParsedSymbol> symbolsByNode,
                              Map<String, List<CodeParser.ParsedSymbol>> targetsByName,
                              List<CodeParser.ParsedEdge> edges) {
    String capture;
    CodeRelation relation;
    if (has(match, "ref.import") || has(match, "ref.export")) {
      capture = has(match, "ref.import") ? "ref.import" : "ref.export";
      relation = CodeRelation.IMPORTS;
    } else if (has(match, "ref.call")) {
      capture = "ref.call";
      relation = CodeRelation.CALLS;
    } else if (has(match, "ref.extends")) {
      capture = "ref.extends";
      relation = CodeRelation.EXTENDS;
    } else if (typed && has(match, "ref.implements")) {
      capture = "ref.implements";
      relation = CodeRelation.IMPLEMENTS;
    } else {
      return;
    }

    Node reference = first(match, capture).orElse(null);
    String targetName = first(match, "ref.name").map(Node::getText).orElse(null);
    if (reference == null || targetName == null || targetName.isBlank()) return;
    targetName = unquote(targetName);
    String source = relation == CodeRelation.IMPORTS ? module
      : nearestDefinition(reference, symbolsByNode).map(CodeParser.ParsedSymbol::qualifiedName).orElse(module);
    List<CodeParser.ParsedSymbol> candidates = targetsByName.getOrDefault(simpleName(targetName), List.of())
      .stream().filter(symbol -> validTarget(relation, symbol.kind())).toList();
    String resolved = candidates.size() == 1 ? candidates.getFirst().qualifiedName() : null;
    edges.add(new CodeParser.ParsedEdge(source, relation,
      resolved == null ? EdgeConfidence.HEURISTIC : EdgeConfidence.RESOLVED,
      resolved, targetName));
  }

  private static boolean validTarget(CodeRelation relation, CodeSymbolKind kind) {
    return switch (relation) {
      case CALLS -> kind == CodeSymbolKind.FUNCTION || kind == CodeSymbolKind.METHOD;
      case EXTENDS, IMPLEMENTS -> kind == CodeSymbolKind.CLASS || kind == CodeSymbolKind.INTERFACE;
      default -> false;
    };
  }

  private static Optional<CodeParser.ParsedSymbol> nearestDefinition(
    Node node, Map<String, CodeParser.ParsedSymbol> symbolsByNode) {
    Node current = node.getParent().orElse(null);
    while (current != null) {
      CodeParser.ParsedSymbol symbol = symbolsByNode.get(nodeKey(current));
      if (symbol != null) return Optional.of(symbol);
      current = current.getParent().orElse(null);
    }
    return Optional.empty();
  }

  private static int countParameters(Node definition) {
    Node parameters = findDescendant(definition, "formal_parameters", "parameters");
    if (parameters != null) {
      return (int) parameters.getChildren().stream().filter(child -> switch (child.getType()) {
        case "identifier", "required_parameter", "optional_parameter", "rest_pattern",
             "assignment_pattern", "object_pattern", "array_pattern" -> true;
        default -> false;
      }).count();
    }
    return definition.getChildByFieldName("parameter").map(n -> 1).orElse(0);
  }

  private static Node findDescendant(Node node, String... types) {
    for (Node child : node.getChildren()) {
      for (String type : types) {
        if (child.getType().equals(type)) return child;
      }
      if (!child.getType().equals("statement_block") && !child.getType().equals("class_body")) {
        Node found = findDescendant(child, types);
        if (found != null) return found;
      }
    }
    return null;
  }

  private static String visibility(Node definition) {
    String text = definition.getText() == null ? "" : definition.getText().stripLeading();
    if (text.startsWith("private ") || text.startsWith("#")) return "private";
    if (text.startsWith("protected ")) return "protected";
    Node current = definition;
    while (current != null) {
      if (current.getType().equals("export_statement")) return "public";
      current = current.getParent().orElse(null);
    }
    return "module";
  }

  private static boolean callable(CodeSymbolKind kind) {
    return kind == CodeSymbolKind.FUNCTION || kind == CodeSymbolKind.METHOD;
  }

  private static String qualified(String parent, String name, CodeSymbolKind kind, int arity) {
    if (kind == CodeSymbolKind.CLASS || kind == CodeSymbolKind.INTERFACE
      || kind == CodeSymbolKind.ENUM || kind == CodeSymbolKind.TYPE_ALIAS) {
      return parent + "." + name;
    }
    return parent + "#" + name + (callable(kind) ? "(" + arity + ")" : "");
  }

  private static String simpleName(String target) {
    int dot = Math.max(target.lastIndexOf('.'), target.lastIndexOf('/'));
    return dot >= 0 ? target.substring(dot + 1) : target;
  }

  private static String unquote(String text) {
    String result = text.strip();
    if (result.length() >= 2 && ((result.startsWith("\"") && result.endsWith("\""))
      || (result.startsWith("'") && result.endsWith("'")))) {
      return result.substring(1, result.length() - 1);
    }
    return result;
  }

  private static String moduleName(String module) {
    int slash = module.lastIndexOf('/');
    return slash >= 0 ? module.substring(slash + 1) : module;
  }

  private static String nodeKey(Node node) {
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
