package dev.alvo.pieria.harvesters.html;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/** Turns a page into Markdown, keeping the headings that section splitting relies on. */
public final class HtmlToMarkdown {

  private static final int MAX_DEPTH = 200;
  private static final String REMOVED = "script, style, noscript, template, nav, header, footer, aside, form, svg, iframe, "
    + "[hidden], [aria-hidden=true]";
  private static final List<String> MAIN_CONTENT = List.of("main", "article", "[role=main]");

  private HtmlToMarkdown() {
  }

  /** The page's text as Markdown, blank when it has none. {@code document} is left unchanged. */
  public static String convert(Document document) {
    Document copy = document.clone();
    copy.select(REMOVED).remove();
    // Harvested text is untrusted, and text a reader can't see has no business in a memory.
    copy.select("[style]").stream().filter(HtmlToMarkdown::styledHidden).toList().forEach(Node::remove);
    Element root = mainContent(copy);
    return root == null ? "" : blocks(root, 0).strip();
  }

  private static boolean styledHidden(Element element) {
    String style = element.attr("style").replaceAll("\\s", "").toLowerCase(Locale.ROOT);
    return style.contains("display:none") || style.contains("visibility:hidden");
  }

  private static Element mainContent(Document document) {
    for (String selector : MAIN_CONTENT) {
      Element found = document.selectFirst(selector);
      if (found != null) {
        return found;
      }
    }
    return document.body();
  }

  private static String blocks(Element parent, int depth) {
    List<String> blocks = new ArrayList<>();
    StringBuilder inline = new StringBuilder();
    for (Node node : parent.childNodes()) {
      if (node instanceof Element element && element.isBlock()) {
        flush(inline, blocks);
        String block = block(element, depth + 1);
        if (!block.isBlank()) {
          blocks.add(block);
        }
      } else {
        inline.append(inline(node, depth + 1));
      }
    }
    flush(inline, blocks);
    return String.join("\n\n", blocks);
  }

  private static void flush(StringBuilder inline, List<String> blocks) {
    String text = tidy(inline.toString());
    if (!text.isBlank()) {
      blocks.add(text);
    }
    inline.setLength(0);
  }

  private static String tidy(String text) {
    return Arrays.stream(text.split("\n", -1))
      .map(line -> line.replaceAll("[ \\t]+", " ").strip())
      .collect(Collectors.joining("\n"))
      .strip();
  }

  private static String block(Element element, int depth) {
    if (depth > MAX_DEPTH) {
      return element.text();
    }
    String name = element.normalName();
    return switch (name) {
      case "h1", "h2", "h3", "h4", "h5", "h6" -> heading(element, name.charAt(1) - '0', depth);
      case "pre" -> fenced(element.wholeText());
      case "blockquote" -> quoted(blocks(element, depth));
      case "ul", "ol" -> list(element, depth, 0);
      case "table" -> table(element);
      case "hr" -> "---";
      default -> blocks(element, depth);
    };
  }

  private static String heading(Element element, int level, int depth) {
    String text = tidy(children(element, depth)).replace('\n', ' ');
    return text.isBlank() ? "" : "#".repeat(level) + " " + text;
  }

  private static String fenced(String code) {
    String text = code.stripTrailing();
    if (text.isBlank()) {
      return "";
    }
    String fence = "```";
    while (text.contains(fence)) {
      fence += "`";
    }
    return fence + "\n" + text + "\n" + fence;
  }

  private static String quoted(String text) {
    return text.isBlank() ? "" : text.lines().map(line -> line.isEmpty() ? ">" : "> " + line).collect(Collectors.joining("\n"));
  }

  private static String list(Element list, int depth, int indent) {
    List<String> lines = new ArrayList<>();
    boolean ordered = list.normalName().equals("ol");
    int number = 1;
    for (Element item : list.children()) {
      if (!item.normalName().equals("li")) {
        continue;
      }
      StringBuilder text = new StringBuilder();
      List<String> nested = new ArrayList<>();
      for (Node node : item.childNodes()) {
        if (depth < MAX_DEPTH && node instanceof Element element && (element.normalName().equals("ul") || element.normalName().equals("ol"))) {
          nested.add(list(element, depth + 1, indent + 2));
        } else {
          text.append(inline(node, depth + 1)).append(node instanceof Element element && element.isBlock() ? " " : "");
        }
      }
      lines.add(" ".repeat(indent) + (ordered ? number++ + ". " : "- ") + tidy(text.toString()).replace('\n', ' '));
      nested.stream().filter(sublist -> !sublist.isBlank()).forEach(lines::add);
    }
    return String.join("\n", lines);
  }

  private static String table(Element table) {
    List<String> rows = new ArrayList<>();
    for (Element row : table.select("tr")) {
      List<String> cells = row.children().stream()
        .filter(cell -> cell.normalName().equals("th") || cell.normalName().equals("td"))
        .map(cell -> cell.text().replace("|", "\\|"))
        .toList();
      if (cells.isEmpty()) {
        continue;
      }
      rows.add("| " + String.join(" | ", cells) + " |");
      if (rows.size() == 1) {
        rows.add("|" + " --- |".repeat(cells.size()));
      }
    }
    return String.join("\n", rows);
  }

  private static String children(Element element, int depth) {
    StringBuilder text = new StringBuilder();
    for (Node node : element.childNodes()) {
      text.append(inline(node, depth + 1));
    }
    return text.toString();
  }

  private static String inline(Node node, int depth) {
    if (node instanceof TextNode text) {
      return text.text();
    }
    if (!(node instanceof Element element)) {
      return "";
    }
    if (depth > MAX_DEPTH) {
      return element.text();
    }
    return switch (element.normalName()) {
      case "br" -> "\n";
      case "img" -> "";
      case "a" -> link(element, depth);
      case "strong", "b" -> wrapped(children(element, depth), "**");
      case "em", "i" -> wrapped(children(element, depth), "*");
      case "code", "kbd", "samp" -> code(element.text());
      default -> children(element, depth);
    };
  }

  private static String wrapped(String text, String marker) {
    String inner = text.strip();
    return inner.isEmpty() ? "" : marker + inner + marker;
  }

  private static String code(String text) {
    String inner = text.strip();
    if (inner.isEmpty()) {
      return "";
    }
    String fence = inner.contains("`") ? "``" : "`";
    return fence + inner + fence;
  }

  private static String link(Element element, int depth) {
    String text = tidy(children(element, depth)).replace('\n', ' ');
    String target = element.absUrl("href");
    boolean web = target.toLowerCase(Locale.ROOT).startsWith("http://") || target.toLowerCase(Locale.ROOT).startsWith("https://");
    return web && !text.isEmpty() ? "[" + text + "](" + target.replace(" ", "%20") + ")" : text;
  }
}
