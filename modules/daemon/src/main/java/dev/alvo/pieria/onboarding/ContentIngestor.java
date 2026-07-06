package dev.alvo.pieria.onboarding;

import dev.alvo.pieria.domain.memory.Memory;
import dev.alvo.pieria.domain.memory.Message;
import dev.alvo.pieria.ingestion.IngestProgressListener;
import dev.alvo.pieria.ingestion.IngestionService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Shared write path for content sources (markdown, web): turns {@link ContentDocument}s into
 * conversation messages and runs them through the memory-extraction pipeline. Each document is split
 * into section-sized messages (on top-level {@code #}/{@code ##} headings), each prefixed with the
 * document's provenance line so the extractor knows where the content came from, and kept under
 * {@link #MAX_MESSAGE_CHARS} so the ingest chunker never has to re-split a single message.
 *
 * <p>The {@code sessionId} is fixed ({@value #SESSION_ID}): combined with the pipeline's
 * content-addressed ids, re-onboarding unchanged content produces no duplicate memories.
 */
@Component
public class ContentIngestor {

  /**
   * Stable session id so re-ingesting unchanged content is idempotent (content-addressed).
   */
  public static final String SESSION_ID = "pieria-init";

  /**
   * Per-message ceiling, under the pipeline's ~10K chunk boundary so it never re-splits a message.
   */
  static final int MAX_MESSAGE_CHARS = 8_000;

  /**
   * Start of a top-level or second-level ATX heading line.
   */
  private static final Pattern SECTION_HEADING = Pattern.compile("(?m)^#{1,2}\\s");

  private final IngestionService ingestionService;

  public ContentIngestor(IngestionService ingestionService) {
    this.ingestionService = ingestionService;
  }

  /**
   * Ingest the given documents into {@code profile}. Documents that yield no non-blank content
   * contribute no messages; when nothing is extractable the run stores nothing and reports zero.
   *
   * @param sourceType        label for the result (e.g. {@code "markdown"}, {@code "web"})
   * @param documents         the discovered content units
   * @param extractionSamples independent extract passes per chunk (null ⇒ profile default)
   */
  public OnboardResult ingest(String profile,
                              String sourceType,
                              List<ContentDocument> documents,
                              Integer extractionSamples,
                              IngestProgressListener progress) {

    List<Message> messages = new ArrayList<>();
    for (ContentDocument doc : documents) {
      for (String content : toMessageContents(doc)) {
        messages.add(Message.of(SESSION_ID, "user", content));
      }
    }
    if (messages.isEmpty()) {
      return OnboardResult.content(sourceType, documents.size(), 0);
    }
    List<Memory> stored =
      ingestionService.ingest(profile, SESSION_ID, messages, extractionSamples, progress);
    return OnboardResult.content(sourceType, documents.size(), stored.size());
  }

  /**
   * Split one document into provenance-prefixed message bodies. Blank sections are dropped; oversize
   * sections are hard-split on paragraph boundaries.
   */
  static List<String> toMessageContents(ContentDocument doc) {
    String prefix = doc.provenance() + ":\n\n";
    List<String> out = new ArrayList<>();
    for (String section : splitIntoSections(doc.text())) {
      if (section.isBlank()) {
        continue;
      }
      for (String piece : hardSplit(section, MAX_MESSAGE_CHARS - prefix.length())) {
        if (!piece.isBlank()) {
          out.add(prefix + piece);
        }
      }
    }
    return out;
  }

  /**
   * Split markdown on top-level/second-level headings, keeping each heading with the body that
   * follows it. Text with no such headings yields a single section (the whole document).
   */
  static List<String> splitIntoSections(String markdown) {
    var matcher = SECTION_HEADING.matcher(markdown);
    List<Integer> starts = new ArrayList<>();
    while (matcher.find()) {
      starts.add(matcher.start());
    }
    if (starts.isEmpty()) {
      return List.of(markdown);
    }
    List<String> sections = new ArrayList<>();
    // Preamble before the first heading, if any.
    if (starts.getFirst() > 0) {
      sections.add(markdown.substring(0, starts.get(0)));
    }
    for (int i = 0; i < starts.size(); i++) {
      int from = starts.get(i);
      int to = (i + 1 < starts.size()) ? starts.get(i + 1) : markdown.length();
      sections.add(markdown.substring(from, to));
    }
    return sections;
  }

  /**
   * Split a section into pieces no longer than {@code maxChars}, preferring paragraph
   * ({@code "\n\n"}) boundaries and falling back to a hard character cut for a single huge
   * paragraph. Sections already within the limit are returned as-is.
   */
  static List<String> hardSplit(String section, int maxChars) {
    int limit = Math.max(1, maxChars);
    if (section.length() <= limit) {
      return List.of(section);
    }
    List<String> pieces = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    for (String paragraph : section.split("(?<=\n\n)")) {
      if (paragraph.length() > limit) {
        // Flush whatever is buffered, then chop the oversize paragraph into fixed-width slices.
        if (!current.isEmpty()) {
          pieces.add(current.toString());
          current.setLength(0);
        }
        for (int i = 0; i < paragraph.length(); i += limit) {
          pieces.add(paragraph.substring(i, Math.min(paragraph.length(), i + limit)));
        }
      } else if (current.length() + paragraph.length() > limit) {
        pieces.add(current.toString());
        current.setLength(0);
        current.append(paragraph);
      } else {
        current.append(paragraph);
      }
    }
    if (!current.isEmpty()) {
      pieces.add(current.toString());
    }
    return pieces;
  }
}
