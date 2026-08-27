package dev.alvo.pieria.code;

import dev.alvo.pieria.domain.code.CodeRelation;
import dev.alvo.pieria.domain.code.CodeSymbolKind;
import dev.alvo.pieria.domain.code.EdgeConfidence;
import io.github.treesitter.jtreesitter.Node;
import io.github.treesitter.jtreesitter.Query;
import io.github.treesitter.jtreesitter.QueryCursor;
import io.github.treesitter.jtreesitter.QueryMatch;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Java-specific interpretation of the Java language pack's query captures.
 */
final class JavaCodeExtractor implements LanguagePack.Extractor {

  private static CodeParser.ParsedSymbol toSymbol(QueryMatch match, String pkg) {
    String name = first(match, "def.name").map(Node::getText).orElse(null);
    if (name == null || name.isBlank()) {
      return null;
    }
    if (!match.findNodes("def.package").isEmpty()) {
      return new CodeParser.ParsedSymbol(CodeSymbolKind.PACKAGE, name, name, "package " + name,
        "public", 1, 1, null);
    }
    Node def = definitionNode(match);
    if (def == null) {
      return null;
    }
    boolean type = isTypeDecl(def.getType());
    boolean callable = def.getType().equals("method_declaration")
      || def.getType().equals("constructor_declaration");
    String parent = enclosingTypeFqn(def, pkg);
    String qualifiedName = type ? join(parent, name)
      : parent + "#" + name + (callable ? "(" + countParams(def) + ")" : "");
    return new CodeParser.ParsedSymbol(kindOf(def.getType()), name, qualifiedName,
      ExtractionSupport.signature(def), visibility(def), def.getStartPoint().row() + 1,
      def.getEndPoint().row() + 1, ExtractionSupport.blankToNull(parent));
  }

  private static Node definitionNode(QueryMatch match) {
    for (String capture : List.of("def.class", "def.interface", "def.enum", "def.record",
      "def.method", "def.constructor", "def.field")) {
      List<Node> nodes = match.findNodes(capture);
      if (!nodes.isEmpty()) {
        return nodes.getFirst();
      }
    }
    return null;
  }

  private static CodeSymbolKind kindOf(String type) {
    return switch (type) {
      case "interface_declaration" -> CodeSymbolKind.INTERFACE;
      case "enum_declaration" -> CodeSymbolKind.ENUM;
      case "method_declaration", "constructor_declaration" -> CodeSymbolKind.METHOD;
      case "field_declaration" -> CodeSymbolKind.FIELD;
      default -> CodeSymbolKind.CLASS;
    };
  }

  private static void toEdges(QueryMatch match, String pkg, Map<String, String> methods,
                              List<CodeParser.ParsedEdge> edges) {
    if (!match.findNodes("ref.call").isEmpty()) {
      Node call = match.findNodes("ref.call").getFirst();
      String name = first(match, "ref.name").map(Node::getText).orElse(null);
      String source = enclosingMethodFqn(call, pkg);
      if (source == null || name == null || name.isBlank()) {
        return;
      }
      String target = methods.get(name);
      edges.add(new CodeParser.ParsedEdge(source, CodeRelation.CALLS,
        target == null ? EdgeConfidence.HEURISTIC : EdgeConfidence.RESOLVED, target, name));
      return;
    }
    boolean extendsRef = !match.findNodes("ref.extends").isEmpty();
    boolean implementsRef = !match.findNodes("ref.implements").isEmpty();
    if (!extendsRef && !implementsRef) {
      return;
    }
    Node ref = match.findNodes(extendsRef ? "ref.extends" : "ref.implements").getFirst();
    String target = first(match, "ref.name").map(Node::getText).orElse(null);
    String source = enclosingTypeFqnOf(ref, pkg);
    if (source != null && target != null && !target.isBlank()) {
      edges.add(new CodeParser.ParsedEdge(source,
        extendsRef ? CodeRelation.EXTENDS : CodeRelation.IMPLEMENTS,
        EdgeConfidence.HEURISTIC, null, simpleTypeName(target)));
    }
  }

  private static String enclosingTypeFqn(Node node, String pkg) {
    return join(pkg, String.join(".", enclosingTypeNames(node)));
  }

  private static String enclosingTypeFqnOf(Node ref, String pkg) {
    Node type = nearestEnclosing(ref, JavaCodeExtractor::isTypeDecl);
    if (type == null) {
      return null;
    }
    String name = type.getChildByFieldName("name").map(Node::getText).orElse(null);
    return name == null ? null : join(join(pkg, String.join(".", enclosingTypeNames(type))), name);
  }

  private static String enclosingMethodFqn(Node node, String pkg) {
    Node method = nearestEnclosing(node,
      t -> t.equals("method_declaration") || t.equals("constructor_declaration"));
    if (method == null) {
      return null;
    }
    String name = method.getChildByFieldName("name").map(Node::getText).orElse(null);
    return name == null ? null : enclosingTypeFqn(method, pkg) + "#" + name
      + "(" + countParams(method) + ")";
  }

  private static List<String> enclosingTypeNames(Node node) {
    Deque<String> names = new ArrayDeque<>();
    Node current = node.getParent().orElse(null);
    while (current != null) {
      if (isTypeDecl(current.getType())) {
        current.getChildByFieldName("name").map(Node::getText).ifPresent(names::addFirst);
      }
      current = current.getParent().orElse(null);
    }
    return new ArrayList<>(names);
  }

  private static Node nearestEnclosing(Node node, java.util.function.Predicate<String> types) {
    Node current = node.getParent().orElse(null);
    while (current != null) {
      if (types.test(current.getType())) {
        return current;
      }
      current = current.getParent().orElse(null);
    }
    return null;
  }

  private static boolean isTypeDecl(String type) {
    return switch (type) {
      case "class_declaration", "interface_declaration", "enum_declaration", "record_declaration" -> true;
      default -> false;
    };
  }

  private static int countParams(Node declaration) {
    return declaration.getChildByFieldName("parameters")
      .map(p -> (int) p.getChildren().stream()
        .filter(c -> c.getType().equals("formal_parameter") || c.getType().equals("spread_parameter"))
        .count()).orElse(0);
  }

  private static String visibility(Node definition) {
    String modifiers = definition.getChildren().stream().filter(c -> c.getType().equals("modifiers"))
      .map(Node::getText).findFirst().orElse("").toLowerCase(Locale.ROOT);
    if (modifiers.contains("public")) return "public";
    if (modifiers.contains("private")) return "private";
    if (modifiers.contains("protected")) return "protected";
    return "package";
  }

  private static String simpleTypeName(String text) {
    String result = text.strip();
    int generic = result.indexOf('<');
    if (generic >= 0) result = result.substring(0, generic);
    int dot = result.lastIndexOf('.');
    return dot >= 0 ? result.substring(dot + 1) : result;
  }

  private static String join(String left, String right) {
    if (left == null || left.isBlank()) return right == null ? "" : right;
    if (right == null || right.isBlank()) return left;
    return left + "." + right;
  }

  private static Optional<Node> first(QueryMatch match, String capture) {
    List<Node> nodes = match.findNodes(capture);
    return nodes.isEmpty() ? Optional.empty() : Optional.of(nodes.getFirst());
  }

  @Override
  public CodeParser.ParseResult extract(CodeParser.ParseInput input, Node root, Query tags) {
    List<CodeParser.ParsedSymbol> symbols = new ArrayList<>();
    List<CodeParser.ParsedEdge> edges = new ArrayList<>();
    try (QueryCursor cursor = new QueryCursor(tags)) {
      List<QueryMatch> matches = cursor.findMatches(root).toList();
      String pkg = matches.stream()
        .filter(m -> !m.findNodes("def.package").isEmpty())
        .flatMap(m -> m.findNodes("def.name").stream())
        .map(Node::getText).filter(s -> s != null && !s.isBlank()).findFirst().orElse("");

      Map<String, String> methodFqnByName = new HashMap<>();
      for (QueryMatch match : matches) {
        CodeParser.ParsedSymbol symbol = toSymbol(match, pkg);
        if (symbol != null) {
          symbols.add(symbol);
          if (symbol.kind() == CodeSymbolKind.METHOD) {
            methodFqnByName.putIfAbsent(symbol.name(), symbol.qualifiedName());
          }
        }
      }
      for (QueryMatch match : matches) {
        toEdges(match, pkg, methodFqnByName, edges);
      }
    }
    return ExtractionSupport.withOccurrenceSuffixes(symbols, edges);
  }
}
