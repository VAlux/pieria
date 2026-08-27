package dev.alvo.pieria.onboarding;

import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.pdf.PDFParser;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Component;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Production {@link PdfExtractor}: parses the document with Apache Tika's {@link PDFParser} and
 * returns its text. A {@link BodyContentHandler} with an unbounded write limit ({@code -1}) is used
 * so large PDFs are not silently truncated (Tika's convenience {@code parseToString} caps at 100K
 * characters). The document title is read from the parsed metadata when present.
 *
 * <p>Inputs are already known to be PDFs (selected by extension in {@code PdfDiscovery}), so we use
 * the PDF parser directly rather than {@code AutoDetectParser}. This also avoids Tika's MIME-type
 * registry and ServiceLoader-based parser discovery, which fail to initialize under the GraalVM
 * native image unless their classpath resources are explicitly bundled.
 */
@Component
public class TikaPdfExtractor implements PdfExtractor {

  private static final PDFParserConfig TEXT_ONLY_CONFIG = textOnlyConfig();

  private final PDFParser parser = new PDFParser();

  private static PDFParserConfig textOnlyConfig() {
    PDFParserConfig config = new PDFParserConfig();
    // Onboarding needs the PDF's existing text, not rendered pages or OCR. Keeping those paths
    // disabled also prevents PDFBox from reaching AWT image code, which GraalVM native-image does
    // not currently support on macOS.
    config.setOcrStrategy(PDFParserConfig.OCR_STRATEGY.NO_OCR);
    config.setImageStrategy(PDFParserConfig.IMAGE_STRATEGY.NONE);
    config.setExtractInlineImages(false);
    return config;
  }

  @Override
  public ExtractedPdf extract(Path pdf) {
    Metadata metadata = new Metadata();
    // Hint the resource name so Tika's type detection has the extension to work with.
    metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, pdf.getFileName().toString());
    BodyContentHandler handler = new BodyContentHandler(-1);
    ParseContext context = new ParseContext();
    context.set(PDFParserConfig.class, TEXT_ONLY_CONFIG);
    try (InputStream in = Files.newInputStream(pdf)) {
      parser.parse(in, handler, metadata, context);
    } catch (IOException | SAXException | TikaException e) {
      throw new PdfExtractException("failed to extract " + pdf.getFileName() + ": " + e.getMessage(), e);
    }
    String title = metadata.get(TikaCoreProperties.TITLE);
    return new ExtractedPdf(title == null ? "" : title.strip(), handler.toString());
  }

  /**
   * A PDF failed to parse; the caller decides whether to skip the document or fail the run.
   */
  static final class PdfExtractException extends RuntimeException {
    PdfExtractException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
