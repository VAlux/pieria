package dev.alvo.pieria.onboarding;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ContentIngestor}'s pure text-splitting: section detection, provenance
 * prefixing, and hard-splitting. The write path (calling the ingest pipeline) is exercised through
 * the onboarding service / controller tests.
 */
class ContentIngestorTests {

  @Test
  void splitsOnTopLevelHeadings() {
    List<String> sections = ContentIngestor.splitIntoSections(
      "# Title\nintro\n\n## One\nbody one\n\n## Two\nbody two\n");

    assertThat(sections).hasSize(3);
    assertThat(sections.get(0)).startsWith("# Title");
    assertThat(sections.get(1)).startsWith("## One");
    assertThat(sections.get(2)).startsWith("## Two");
  }

  @Test
  void keepsPreambleBeforeFirstHeading() {
    List<String> sections = ContentIngestor.splitIntoSections("preamble text\n\n# Heading\nbody");

    assertThat(sections.get(0)).isEqualTo("preamble text\n\n");
    assertThat(sections.get(1)).startsWith("# Heading");
  }

  @Test
  void wholeTextIsOneSectionWhenNoHeadings() {
    assertThat(ContentIngestor.splitIntoSections("just a paragraph\nno headings"))
      .containsExactly("just a paragraph\nno headings");
  }

  @Test
  void messagesCarryProvenancePrefix() {
    List<String> contents = ContentIngestor.toMessageContents(
      new ContentDocument("Project documentation — docs/SPEC.md", "# Title\nbody"));

    assertThat(contents).allSatisfy(c ->
      assertThat(c).startsWith("Project documentation — docs/SPEC.md:"));
  }

  @Test
  void whitespaceOnlyDocumentYieldsNoMessages() {
    assertThat(ContentIngestor.toMessageContents(new ContentDocument("Web page — https://x", "   \n\n  \n")))
      .isEmpty();
  }

  @Test
  void hardSplitKeepsPiecesUnderLimit() {
    String huge = "x".repeat(20_000);
    List<String> pieces = ContentIngestor.hardSplit(huge, 8_000);

    assertThat(pieces).hasSizeGreaterThan(1);
    assertThat(pieces).allSatisfy(p -> assertThat(p.length()).isLessThanOrEqualTo(8_000));
  }

  @Test
  void hardSplitPrefersParagraphBoundaries() {
    String section = "a".repeat(5_000) + "\n\n" + "b".repeat(5_000);
    List<String> pieces = ContentIngestor.hardSplit(section, 8_000);

    assertThat(pieces).hasSize(2);
    assertThat(pieces.get(0)).startsWith("a").doesNotContain("b");
    assertThat(pieces.get(1)).startsWith("b");
  }
}
