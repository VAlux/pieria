package dev.alvo.pieria.harvesters.html;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class HtmlToMarkdownTests {

  private static String convert(String html) {
    return HtmlToMarkdown.convert(Jsoup.parse(html, "https://x.test/a/"));
  }

  @Test
  void keepsHeadingLevels() {
    assertThat(convert("<h1>A</h1><h3>B</h3>")).isEqualTo("# A\n\n### B");
  }

  @Test
  void separatesParagraphs() {
    assertThat(convert("<p>one  two</p><p>three</p>")).isEqualTo("one two\n\nthree");
  }

  @Test
  void prefersMainContent() {
    assertThat(convert("<nav>menu</nav><div>outside</div><main><p>inside</p></main>")).isEqualTo("inside");
  }

  @Test
  void fallsBackToArticle() {
    assertThat(convert("<div>outside</div><article><p>inside</p></article>")).isEqualTo("inside");
  }

  @Test
  void convertingLeavesTheDocumentIntact() {
    Document document = Jsoup.parse("<nav><a href='/n'>menu</a></nav><main>x</main>", "https://x.test/");

    HtmlToMarkdown.convert(document);

    assertThat(document.select("nav a")).hasSize(1);
  }

  @Test
  void convertsNestedLists() {
    assertThat(convert("<ul><li>a<ul><li>b</li></ul></li><li>c</li></ul>")).isEqualTo("- a\n  - b\n- c");
  }

  @Test
  void numbersOrderedLists() {
    assertThat(convert("<ol><li>a</li><li>b</li></ol>")).isEqualTo("1. a\n2. b");
  }

  @Test
  void fencesPreformattedText() {
    assertThat(convert("<pre>  a\n    b</pre>")).isEqualTo("```\n  a\n    b\n```");
  }

  @Test
  void aFenceInsideCodeUsesALongerFence() {
    assertThat(convert("<pre>```\nx</pre>")).isEqualTo("````\n```\nx\n````");
  }

  @Test
  void quotesBlockquotes() {
    assertThat(convert("<blockquote><p>a</p><p>b</p></blockquote>")).isEqualTo("> a\n>\n> b");
  }

  @Test
  void formatsInlineText() {
    assertThat(convert("<p><strong>a</strong> <em>b</em> <code>c</code></p>")).isEqualTo("**a** *b* `c`");
  }

  @Test
  void makesLinksAbsolute() {
    assertThat(convert("<p><a href='b'>go</a></p>")).isEqualTo("[go](https://x.test/a/b)");
  }

  @Test
  void dropsJavascriptLinksToPlainText() {
    assertThat(convert("<p><a href='javascript:x()'>go</a></p>")).isEqualTo("go");
  }

  @Test
  void convertsTables() {
    assertThat(convert("<table><tr><th>A</th><th>B</th></tr><tr><td>1</td><td>2|3</td></tr></table>"))
      .isEqualTo("| A | B |\n| --- | --- |\n| 1 | 2\\|3 |");
  }

  @Test
  void keepsLineBreaksAndRules() {
    assertThat(convert("<p>a<br>b</p><hr><p>c</p>")).isEqualTo("a\nb\n\n---\n\nc");
  }

  @Test
  void dropsImages() {
    assertThat(convert("<p><img src='a.png' alt='x'>text</p>")).isEqualTo("text");
  }

  @ParameterizedTest
  @ValueSource(strings = {"<p hidden>x</p>", "<p aria-hidden=\"true\">x</p>", "<p style=\"DISPLAY: none\">x</p>",
    "<p style=\"visibility:hidden\">x</p>", "<script>x</script>", "<template>x</template>", "<!-- x -->"})
  void dropsHiddenText(String html) {
    assertThat(convert(html)).isBlank();
  }

  @Test
  void aPageWithNoTextIsBlank() {
    assertThat(convert("<html><body></body></html>")).isBlank();
  }

  @Test
  void survivesVeryDeepNesting() {
    assertThat(convert("<div>".repeat(5000) + "deep" + "</div>".repeat(5000))).contains("deep");
  }
}
