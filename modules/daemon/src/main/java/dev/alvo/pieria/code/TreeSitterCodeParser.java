package dev.alvo.pieria.code;

import dev.alvo.pieria.domain.code.CodeRelation;
import dev.alvo.pieria.domain.code.CodeSymbolKind;
import dev.alvo.pieria.domain.code.EdgeConfidence;
import io.github.treesitter.jtreesitter.Node;
import io.github.treesitter.jtreesitter.Query;
import io.github.treesitter.jtreesitter.QueryCursor;
import io.github.treesitter.jtreesitter.QueryMatch;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Tree-sitter–backed {@link CodeParser} for Java. Runs the bundled {@code tags.scm} query through
 * {@link TreeSitterEngine} and maps captures + node context into id-free {@link ParsedSymbol}s and
 * {@link ParsedEdge}s; ids/{@code fileId} are assigned downstream by {@code CodeIndexingService}.
 *
 * <p>Edges: a {@code calls} to a method declared in the same file is {@code resolved} (with a
 * {@code dstQualifiedName}); a call to an unknown method, and {@code extends}/{@code implements}
 * targets, are {@code heuristic} (carrying only a {@code dstRef} simple name). Never throws on
 * malformed input — returns what it could extract (engine failures degrade to empty).
 */
@Component
public class TreeSitterCodeParser implements CodeParser {

  private static final int MAX_SIGNATURE = 200;

  private final TreeSitterEngine engine;

  public TreeSitterCodeParser(TreeSitterEngine engine) {
    this.engine = engine;
  }

  @Override
  public boolean supports(String language) {
    return engine.supports(language);
  }

  @Override
  public ParseResult parse(ParseInput input) {
    if (input == null || input.content() == null || !engine.supports(input.language())) {
      return ParseResult.empty();
    }
    return engine.parse(input.language(), input.content(), (root, tags, source) -> extract(root, tags))
      .orElse(ParseResult.empty());
  }

  private ParseResult extract(Node root, Query tags) {
    List<ParsedSymbol> symbols = new ArrayList<>();
    List<ParsedEdge> edges = new ArrayList<>();

    try (QueryCursor cursor = new QueryCursor(tags)) {
      List<QueryMatch> matches = cursor.findMatches(root).toList();

      String pkg = matches.stream()
        .filter(m -> !m.findNodes("def.package").isEmpty())
        .flatMap(m -> m.findNodes("def.name").stream())
        .map(Node::getText)
        .filter(s -> s != null && !s.isBlank())
        .findFirst()
        .orElse("");

      // First pass: symbols, and an in-file method-name → qualifiedName index for call resolution.
      Map<String, String> methodFqnByName = new HashMap<>();
      for (QueryMatch match : matches) {
        ParsedSymbol symbol = toSymbol(match, pkg);
        if (symbol == null) {
          continue;
        }
        symbols.add(symbol);
        if (symbol.kind() == CodeSymbolKind.METHOD) {
          methodFqnByName.putIfAbsent(symbol.name(), symbol.qualifiedName());
        }
      }

      // Second pass: edges.
      for (QueryMatch match : matches) {
        toEdges(match, pkg, methodFqnByName, edges);
      }
    }
    return new ParseResult(symbols, edges);
  }

  // ---- symbols ----

  private ParsedSymbol toSymbol(QueryMatch match, String pkg) {
    String name = first(match, "def.name").map(Node::getText).orElse(null);
    if (name == null || name.isBlank()) {
      return null;
    }
    if (!match.findNodes("def.package").isEmpty()) {
      return new ParsedSymbol(CodeSymbolKind.PACKAGE, name, name, "package " + name, "public",
        1, 1, null);
    }
    Node def = typeOrMemberNode(match);
    if (def == null) {
      return null;
    }
    boolean isType = isTypeDecl(def.getType());
    boolean isMethod = def.getType().equals("method_declaration") || def.getType().equals("constructor_declaration");

    CodeSymbolKind kind = kindOf(def.getType());
    String enclosingType = enclosingTypeFqn(def, pkg); // FQN of the nearest enclosing type (or pkg)
    String qualifiedName;
    String parentQualifiedName;
    if (isType) {
      qualifiedName = join(enclosingType, name);
      parentQualifiedName = blankToNull(enclosingType);
    } else if (isMethod) {
      qualifiedName = enclosingType + "#" + name + "(" + countParams(def) + ")";
      parentQualifiedName = blankToNull(enclosingType);
    } else { // field
      qualifiedName = enclosingType + "#" + name;
      parentQualifiedName = blankToNull(enclosingType);
    }

    return new ParsedSymbol(kind, name, qualifiedName, signature(def), visibility(def),
      def.getStartPoint().row() + 1, def.getEndPoint().row() + 1, parentQualifiedName);
  }

  private static Node typeOrMemberNode(QueryMatch match) {
    for (String capture : List.of("def.class", "def.interface", "def.enum", "def.record",
      "def.method", "def.constructor", "def.field")) {
      List<Node> nodes = match.findNodes(capture);
      if (!nodes.isEmpty()) {
        return nodes.getFirst();
      }
    }
    return null;
  }

  private static CodeSymbolKind kindOf(String nodeType) {
    return switch (nodeType) {
      case "interface_declaration" -> CodeSymbolKind.INTERFACE;
      case "method_declaration", "constructor_declaration" -> CodeSymbolKind.METHOD;
      case "field_declaration" -> CodeSymbolKind.FIELD;
      // class / enum / record collapse to CLASS (the index has no enum/record kind).
      default -> CodeSymbolKind.CLASS;
    };
  }

  // ---- edges ----

  private void toEdges(QueryMatch match, String pkg, Map<String, String> methodFqnByName, List<ParsedEdge> edges) {
    if (!match.findNodes("ref.call").isEmpty()) {
      Node call = match.findNodes("ref.call").getFirst();
      String calleeName = first(match, "ref.name").map(Node::getText).orElse(null);
      String srcMethod = enclosingMethodFqn(call, pkg);
      if (srcMethod == null || calleeName == null || calleeName.isBlank()) {
        return;
      }
      String resolvedFqn = methodFqnByName.get(calleeName);
      if (resolvedFqn != null) {
        edges.add(new ParsedEdge(srcMethod, CodeRelation.CALLS, EdgeConfidence.RESOLVED, resolvedFqn, calleeName));
      } else {
        edges.add(new ParsedEdge(srcMethod, CodeRelation.CALLS, EdgeConfidence.HEURISTIC, null, calleeName));
      }
      return;
    }

    boolean isExtends = !match.findNodes("ref.extends").isEmpty();
    boolean isImplements = !match.findNodes("ref.implements").isEmpty();
    if (isExtends || isImplements) {
      Node ref = match.findNodes(isExtends ? "ref.extends" : "ref.implements").getFirst();
      String targetText = first(match, "ref.name").map(Node::getText).orElse(null);
      String srcType = enclosingTypeFqnOf(ref, pkg);
      if (srcType == null || srcType.isBlank() || targetText == null || targetText.isBlank()) {
        return;
      }
      edges.add(new ParsedEdge(srcType, isExtends ? CodeRelation.EXTENDS : CodeRelation.IMPLEMENTS,
        EdgeConfidence.HEURISTIC, null, simpleTypeName(targetText)));
    }
  }

  // ---- context / FQN helpers ----

  /** FQN of the nearest enclosing type of {@code node}; falls back to the package for top-level decls. */
  private static String enclosingTypeFqn(Node node, String pkg) {
    List<String> names = enclosingTypeNames(node);
    return join(pkg, String.join(".", names));
  }

  /** FQN of the nearest enclosing type, where {@code node} is a reference inside it. */
  private static String enclosingTypeFqnOf(Node ref, String pkg) {
    Node type = nearestEnclosing(ref, TreeSitterCodeParser::isTypeDecl);
    if (type == null) {
      return null;
    }
    String name = type.getChildByFieldName("name").map(Node::getText).orElse(null);
    if (name == null) {
      return null;
    }
    List<String> outer = enclosingTypeNames(type);
    return join(join(pkg, String.join(".", outer)), name);
  }

  /** FQN of the method/constructor enclosing {@code node}, or null when not inside one. */
  private static String enclosingMethodFqn(Node node, String pkg) {
    Node method = nearestEnclosing(node, t -> t.equals("method_declaration") || t.equals("constructor_declaration"));
    if (method == null) {
      return null;
    }
    String name = method.getChildByFieldName("name").map(Node::getText).orElse(null);
    if (name == null) {
      return null;
    }
    return enclosingTypeFqn(method, pkg) + "#" + name + "(" + countParams(method) + ")";
  }

  /** Simple names of the type declarations enclosing {@code node}, outermost first (excludes node). */
  private static List<String> enclosingTypeNames(Node node) {
    Deque<String> names = new ArrayDeque<>();
    Optional<Node> cur = node.getParent();
    while (cur.isPresent()) {
      Node n = cur.get();
      if (isTypeDecl(n.getType())) {
        n.getChildByFieldName("name").map(Node::getText).ifPresent(names::addFirst);
      }
      cur = n.getParent();
    }
    return new ArrayList<>(names);
  }

  private static Node nearestEnclosing(Node node, java.util.function.Predicate<String> typeMatch) {
    Optional<Node> cur = node.getParent();
    while (cur.isPresent()) {
      Node n = cur.get();
      if (typeMatch.test(n.getType())) {
        return n;
      }
      cur = n.getParent();
    }
    return null;
  }

  private static boolean isTypeDecl(String nodeType) {
    return switch (nodeType) {
      case "class_declaration", "interface_declaration", "enum_declaration", "record_declaration" -> true;
      default -> false;
    };
  }

  private static int countParams(Node decl) {
    return decl.getChildByFieldName("parameters")
      .map(p -> (int) p.getChildren().stream()
        .filter(c -> c.getType().equals("formal_parameter") || c.getType().equals("spread_parameter"))
        .count())
      .orElse(0);
  }

  private static String signature(Node def) {
    String text = def.getText();
    if (text == null) {
      return "";
    }
    int cut = text.length();
    for (char c : new char[] {'{', '=', ';'}) {
      int i = text.indexOf(c);
      if (i >= 0) {
        cut = Math.min(cut, i);
      }
    }
    String header = text.substring(0, cut).strip().replaceAll("\\s+", " ");
    return header.length() <= MAX_SIGNATURE ? header : header.substring(0, MAX_SIGNATURE);
  }

  private static String visibility(Node def) {
    String modifiers = def.getChildren().stream()
      .filter(c -> c.getType().equals("modifiers"))
      .map(Node::getText)
      .findFirst()
      .orElse("")
      .toLowerCase(Locale.ROOT);
    if (modifiers.contains("public")) {
      return "public";
    }
    if (modifiers.contains("private")) {
      return "private";
    }
    if (modifiers.contains("protected")) {
      return "protected";
    }
    return "package";
  }

  private static String simpleTypeName(String typeText) {
    String t = typeText.strip();
    int lt = t.indexOf('<');
    if (lt >= 0) {
      t = t.substring(0, lt);
    }
    int dot = t.lastIndexOf('.');
    return dot >= 0 ? t.substring(dot + 1) : t;
  }

  private static String join(String left, String right) {
    if (left == null || left.isBlank()) {
      return right == null ? "" : right;
    }
    if (right == null || right.isBlank()) {
      return left;
    }
    return left + "." + right;
  }

  private static String blankToNull(String value) {
    return (value == null || value.isBlank()) ? null : value;
  }

  private static Optional<Node> first(QueryMatch match, String capture) {
    List<Node> nodes = match.findNodes(capture);
    return nodes.isEmpty() ? Optional.empty() : Optional.of(nodes.getFirst());
  }
}
