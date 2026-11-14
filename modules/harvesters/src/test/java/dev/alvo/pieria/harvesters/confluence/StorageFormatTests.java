package dev.alvo.pieria.harvesters.confluence;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StorageFormatTests {

  private static final String BASE = "https://acme.atlassian.net/wiki";

  private static String convert(String storage) {
    return StorageFormat.toMarkdown(storage, BASE);
  }

  @Test
  void headingsAndParagraphsSurvive() {
    String markdown = convert("<h1>Runbook</h1><p>Restart the <strong>worker</strong> first.</p><h2>Rollback</h2><p>Then this.</p>");

    assertThat(markdown).contains("# Runbook").contains("Restart the **worker** first.").contains("## Rollback");
  }

  @Test
  void codeMacroBecomesFencedCode() {
    String markdown = convert("<p>Run:</p><ac:structured-macro ac:name=\"code\" ac:schema-version=\"1\">"
      + "<ac:parameter ac:name=\"language\">bash</ac:parameter>"
      + "<ac:plain-text-body><![CDATA[if [ $a < 3 ]; then\n  echo \"ok\" && exit\nfi]]></ac:plain-text-body></ac:structured-macro>");

    assertThat(markdown).contains("```").contains("if [ $a < 3 ]; then\n  echo \"ok\" && exit\nfi");
    assertThat(markdown).doesNotContain("bash").doesNotContain("CDATA");
  }

  @Test
  void infoPanelKeepsItsBody() {
    String markdown = convert("<ac:structured-macro ac:name=\"info\"><ac:parameter ac:name=\"title\">Heads up</ac:parameter>"
      + "<ac:rich-text-body><p>Deploys freeze on Fridays.</p></ac:rich-text-body></ac:structured-macro>");

    assertThat(markdown).contains("Deploys freeze on Fridays.").doesNotContain("Heads up");
  }

  @Test
  void tocMacroIsDropped() {
    String markdown = convert("<ac:structured-macro ac:name=\"toc\"><ac:parameter ac:name=\"maxLevel\">3</ac:parameter>"
      + "</ac:structured-macro><p>Body</p>");

    assertThat(markdown).isEqualTo("Body");
  }

  @Test
  void plainTextMacroBecomesAParagraph() {
    assertThat(convert("<ac:structured-macro ac:name=\"excerpt-text\"><ac:plain-text-body><![CDATA[short note]]></ac:plain-text-body>"
      + "</ac:structured-macro>")).isEqualTo("short note");
  }

  @Test
  void pageLinkUsesItsTitle() {
    assertThat(convert("<p>See <ac:link><ri:page ri:content-title=\"Deploy guide\" /></ac:link>.</p>")).isEqualTo("See Deploy guide.");
    assertThat(convert("<p>See <ac:link><ri:page ri:content-title=\"Deploy guide\" /><ac:plain-text-link-body><![CDATA[the guide]]>"
      + "</ac:plain-text-link-body></ac:link>.</p>")).isEqualTo("See the guide.");
    assertThat(convert("<p>Get <ac:link><ri:attachment ri:filename=\"plan.pdf\" /></ac:link></p>")).isEqualTo("Get plan.pdf");
  }

  @Test
  void userLinkUsesTheUsername() {
    assertThat(convert("<p>Ask <ac:link><ri:user ri:username=\"jdoe\" /></ac:link></p>")).isEqualTo("Ask @jdoe");
    assertThat(convert("<p>Ask <ac:link><ri:user ri:account-id=\"5b10a\" /></ac:link> now</p>")).isEqualTo("Ask now");
  }

  @Test
  void taskListShowsStatus() {
    String markdown = convert("<ac:task-list><ac:task><ac:task-id>1</ac:task-id><ac:task-status>complete</ac:task-status>"
      + "<ac:task-body>Write runbook</ac:task-body></ac:task><ac:task><ac:task-id>2</ac:task-id>"
      + "<ac:task-status>incomplete</ac:task-status><ac:task-body>Review it</ac:task-body></ac:task></ac:task-list>");

    assertThat(markdown).contains("[x] Write runbook").contains("[ ] Review it");
  }

  @Test
  void imagesAndEmoticonsAreDropped() {
    assertThat(convert("<p>Look <ac:image><ri:attachment ri:filename=\"a.png\" /></ac:image><ac:emoticon ac:name=\"smile\" /> here</p>"))
      .isEqualTo("Look here");
  }

  @Test
  void timeShowsItsDate() {
    assertThat(convert("<p>Due <time datetime=\"2026-12-01\" /></p>")).isEqualTo("Due 2026-12-01");
  }

  @Test
  void nbspEntityParses() {
    String markdown = convert("<p>a&nbsp;b &amp; c&hellip;</p>");

    assertThat(markdown).doesNotContain("&nbsp;").doesNotContain("&hellip;").contains("&").contains("b");
  }

  @Test
  void tablesStillConvert() {
    String markdown = convert("<table><tbody><tr><th>Env</th><th>Host</th></tr><tr><td>prod</td><td>p1</td></tr></tbody></table>");

    assertThat(markdown).contains("Env").contains("prod").contains("|");
  }

  @Test
  void layoutsAreUnwrapped() {
    assertThat(convert("<ac:layout><ac:layout-section ac:type=\"single\"><ac:layout-cell><p>Inside</p></ac:layout-cell>"
      + "</ac:layout-section></ac:layout>")).isEqualTo("Inside");
  }

  @Test
  void emptyStorageIsBlank() {
    assertThat(convert("")).isEmpty();
  }
}
