package dev.alvo.pieria.cli.init;

import dev.alvo.pieria.api.request.IngestRequest;
import dev.alvo.pieria.api.request.IngestRequest.MessageDto;
import dev.alvo.pieria.cli.modules.init.MarkdownDiscovery;
import dev.alvo.pieria.cli.modules.init.TranscriptBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TranscriptBuilderTests {

  private final TranscriptBuilder builder = new TranscriptBuilder();

  @Test
  void splitsOnTopLevelHeadings() {
    List<String> sections = TranscriptBuilder.splitIntoSections(
      "# Title\nintro\n\n## One\nbody one\n\n## Two\nbody two\n");

    assertThat(sections).hasSize(3);
    assertThat(sections.get(0)).startsWith("# Title");
    assertThat(sections.get(1)).startsWith("## One");
    assertThat(sections.get(2)).startsWith("## Two");
  }

  @Test
  void keepsPreambleBeforeFirstHeading() {
    List<String> sections = TranscriptBuilder.splitIntoSections("preamble text\n\n# Heading\nbody");

    assertThat(sections.get(0)).isEqualTo("preamble text\n\n");
    assertThat(sections.get(1)).startsWith("# Heading");
  }

  @Test
  void wholeFileIsOneSectionWhenNoHeadings() {
    assertThat(TranscriptBuilder.splitIntoSections("just a paragraph\nno headings"))
      .containsExactly("just a paragraph\nno headings");
  }

  @Test
  void messagesCarryProvenancePrefixAndUserRole() {
    List<MessageDto> messages = TranscriptBuilder.toMessages(Path.of("docs/SPEC.md"), "# Title\nbody");

    assertThat(messages).allSatisfy(m -> {
      assertThat(m.role()).isEqualTo("user");
      assertThat(m.content()).startsWith("Project documentation — docs/SPEC.md:");
    });
  }

  @Test
  void whitespaceOnlyFileYieldsNoMessages() {
    assertThat(TranscriptBuilder.toMessages(Path.of("EMPTY.md"), "   \n\n  \n")).isEmpty();
  }

  @Test
  void hardSplitKeepsPiecesUnderLimit() {
    String huge = "x".repeat(20_000);
    List<String> pieces = TranscriptBuilder.hardSplit(huge, 8_000);

    assertThat(pieces).hasSizeGreaterThan(1);
    assertThat(pieces).allSatisfy(p -> assertThat(p.length()).isLessThanOrEqualTo(8_000));
  }

  @Test
  void hardSplitPrefersParagraphBoundaries() {
    String section = "a".repeat(5_000) + "\n\n" + "b".repeat(5_000);
    List<String> pieces = TranscriptBuilder.hardSplit(section, 8_000);

    assertThat(pieces).hasSize(2);
    assertThat(pieces.get(0)).startsWith("a").doesNotContain("b");
    assertThat(pieces.get(1)).startsWith("b");
  }

  @Test
  void buildUsesFixedSessionIdAndIsDeterministic(@TempDir Path proj) throws IOException {
    Files.writeString(proj.resolve("README.md"), "# Readme\nhello world");
    List<MarkdownDiscovery.Doc> docs = List.of(
      new MarkdownDiscovery.Doc(Path.of("README.md"), proj.resolve("README.md")));

    IngestRequest first = builder.build(docs);
    IngestRequest second = builder.build(docs);

    assertThat(first.sessionId()).isEqualTo("pieria-init");
    assertThat(first).isEqualTo(second); // records → structural equality, idempotency proxy
    assertThat(first.messages()).isNotEmpty();
  }
}
