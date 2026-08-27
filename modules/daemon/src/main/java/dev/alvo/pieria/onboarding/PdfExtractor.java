package dev.alvo.pieria.onboarding;

import java.nio.file.Path;

/**
 * Extracts the readable text of a PDF document. An injected seam so the PDF onboarding source is
 * testable without a heavyweight parser stack; {@link TikaPdfExtractor} is the production wiring.
 */
public interface PdfExtractor {

  /**
   * Extract {@code pdf} and return its text. Throws on any parse / IO failure; the caller decides
   * whether to skip the document or fail the run.
   */
  ExtractedPdf extract(Path pdf);

  /**
   * An extracted PDF: its document title (may be blank) and extracted text.
   */
  record ExtractedPdf(String title, String text) {
  }
}
