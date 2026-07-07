package dev.alvo.pieria.onboarding;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests {@link TikaPdfExtractor} against a real PDF generated in-test with PDFBox (bundled
 * transitively by Tika), so no binary fixture is checked in: it returns the document text and title,
 * and fails on a file that presents as a PDF but cannot be parsed.
 */
class TikaPdfExtractorTests {

  private final TikaPdfExtractor extractor = new TikaPdfExtractor();

  private static Path writePdf(Path dir, String title, String body) throws IOException {
    Path file = dir.resolve("doc.pdf");
    try (PDDocument doc = new PDDocument()) {
      PDPage page = new PDPage();
      doc.addPage(page);
      try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
        content.beginText();
        content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
        content.newLineAtOffset(72, 720);
        content.showText(body);
        content.endText();
      }
      if (title != null) {
        doc.getDocumentInformation().setTitle(title);
      }
      doc.save(file.toFile());
    }
    return file;
  }

  @Test
  void extractsTextAndTitle(@TempDir Path dir) throws IOException {
    Path pdf = writePdf(dir, "My PDF", "The durable knowledge lives here.");

    PdfExtractor.ExtractedPdf extracted = extractor.extract(pdf);

    assertThat(extracted.title()).isEqualTo("My PDF");
    assertThat(extracted.text()).contains("The durable knowledge lives here.");
  }

  @Test
  void titleIsBlankWhenAbsent(@TempDir Path dir) throws IOException {
    Path pdf = writePdf(dir, null, "Body without a title.");

    assertThat(extractor.extract(pdf).title()).isBlank();
  }

  @Test
  void unparseablePdfThrows(@TempDir Path dir) throws IOException {
    // Presents as a PDF (%PDF header ⇒ Tika routes it to the PDF parser) but has no valid structure.
    Path broken = dir.resolve("broken.pdf");
    Files.writeString(broken, "%PDF-1.7\nthis is not a valid pdf body\n%%EOF");

    assertThatThrownBy(() -> extractor.extract(broken))
      .isInstanceOf(RuntimeException.class);
  }
}
