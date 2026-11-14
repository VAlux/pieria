package dev.alvo.pieria.harvesters.confluence;

import dev.alvo.pieria.harvesters.html.HtmlToMarkdown;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.parser.Parser;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns Confluence storage format (XHTML with {@code ac:} and {@code ri:} elements) into Markdown.
 * Macros keep the text a reader sees: a code block's code, a panel's body, a link's label. Macros that
 * only generate content from elsewhere, such as a table of contents, are dropped.
 */
public final class StorageFormat {

  private static final String DROPPED = "ac|parameter, ac|image, ac|emoticon, ac|placeholder, ac|task-id";

  private StorageFormat() {
  }

  public static String toMarkdown(String storage, String baseUrl) {
    if (storage == null || storage.isBlank()) {
      return "";
    }
    Document xml = Jsoup.parse("<storage-root>" + storage + "</storage-root>", baseUrl, Parser.xmlParser());
    xml.outputSettings().prettyPrint(false);
    Element root = xml.selectFirst("storage-root");
    if (root == null) {
      return "";
    }
    for (Element macro : reversed(root.select("ac|structured-macro"))) {
      macro(macro);
    }
    root.select(DROPPED).remove();
    for (Element link : reversed(root.select("ac|link"))) {
      link.replaceWith(new TextNode(linkText(link)));
    }
    for (Element task : reversed(root.select("ac|task"))) {
      task(task);
    }
    root.select("ac|task-list").tagName("ul");
    for (Element time : root.select("time[datetime]")) {
      time.replaceWith(new TextNode(time.attr("datetime")));
    }
    for (Element element : reversed(root.getAllElements())) {
      String name = element.tagName();
      if (element != root && (name.startsWith("ac:") || name.startsWith("ri:"))) {
        element.unwrap();
      }
    }
    return HtmlToMarkdown.convert(Jsoup.parseBodyFragment(root.html(), baseUrl));
  }

  private static List<Element> reversed(List<Element> elements) {
    List<Element> copy = new ArrayList<>(elements);
    java.util.Collections.reverse(copy);
    return copy;
  }

  private static void macro(Element macro) {
    String name = macro.attr("ac:name");
    Element plain = macro.selectFirst("> ac|plain-text-body");
    if (name.equals("code") || name.equals("noformat")) {
      Element pre = new Element("pre");
      pre.appendChild(new Element("code").appendChild(new TextNode(plain == null ? "" : plain.wholeText())));
      macro.replaceWith(pre);
      return;
    }
    Element rich = macro.selectFirst("> ac|rich-text-body");
    if (rich != null) {
      List<Node> children = new ArrayList<>(rich.childNodes());
      for (Node child : children) {
        macro.before(child);
      }
      macro.remove();
    } else if (plain != null && !plain.wholeText().isBlank()) {
      macro.replaceWith(new Element("p").appendChild(new TextNode(plain.wholeText())));
    } else {
      macro.remove();
    }
  }

  private static String linkText(Element link) {
    Element body = link.selectFirst("> ac|link-body, > ac|plain-text-link-body");
    if (body != null && !body.wholeText().isBlank()) {
      return body.wholeText().strip();
    }
    Element page = link.selectFirst("ri|page, ri|blog-post");
    if (page != null && !page.attr("ri:content-title").isBlank()) {
      return page.attr("ri:content-title");
    }
    Element attachment = link.selectFirst("ri|attachment");
    if (attachment != null && !attachment.attr("ri:filename").isBlank()) {
      return attachment.attr("ri:filename");
    }
    Element url = link.selectFirst("ri|url");
    if (url != null && !url.attr("ri:value").isBlank()) {
      return url.attr("ri:value");
    }
    Element user = link.selectFirst("ri|user");
    if (user != null && !user.attr("ri:username").isBlank()) {
      return "@" + user.attr("ri:username");
    }
    return "";
  }

  private static void task(Element task) {
    Element status = task.selectFirst("> ac|task-status");
    boolean complete = status != null && status.text().strip().equals("complete");
    Element body = task.selectFirst("> ac|task-body");
    Element item = new Element("li").appendText(complete ? "[x] " : "[ ] ");
    if (body != null) {
      for (Node child : new ArrayList<>(body.childNodes())) {
        item.appendChild(child);
      }
    }
    task.replaceWith(item);
  }
}
