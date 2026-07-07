package dev.alvo.pieria.onboarding;

import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Component;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Production {@link PdfExtractor}: parses the document with Apache Tika and returns its text.
 * A {@link BodyContentHandler} with an unbounded write limit ({@code -1}) is used so large PDFs are
 * not silently truncated (Tika's convenience {@code parseToString} caps at 100K characters). The
 * document title is read from the parsed metadata when present.
 */
@Component
public class TikaPdfExtractor implements PdfExtractor {

  private final AutoDetectParser parser = new AutoDetectParser();

  @Override
  public ExtractedPdf extract(Path pdf) {
    Metadata metadata = new Metadata();
    // Hint the resource name so Tika's type detection has the extension to work with.
    metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, pdf.getFileName().toString());
    BodyContentHandler handler = new BodyContentHandler(-1);
    try (InputStream in = Files.newInputStream(pdf)) {
      parser.parse(in, handler, metadata, new ParseContext());
    } catch (IOException | SAXException | TikaException e) {
      throw new PdfExtractException("failed to extract " + pdf.getFileName() + ": " + e.getMessage(), e);
    }
    String title = metadata.get(TikaCoreProperties.TITLE);
    return new ExtractedPdf(title == null ? "" : title.strip(), handler.toString());
  }

  /** A PDF failed to parse; the caller decides whether to skip the document or fail the run. */
  static final class PdfExtractException extends RuntimeException {
    PdfExtractException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
