package dev.alvo.pieria.cli.init;

import dev.alvo.pieria.api.request.IngestRequest;
import dev.alvo.pieria.api.request.IngestRequest.MessageDto;
import dev.alvo.pieria.cli.init.MarkdownDiscovery.Doc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Turns discovered markdown docs into an {@link IngestRequest} the daemon's ingest pipeline can
 * consume. Each doc is split into section-sized messages (on top-level {@code #}/{@code ##}
 * headings), each prefixed with a provenance line so the extractor knows where the content came
 * from. Messages are kept under {@link #MAX_MESSAGE_CHARS} so the daemon's ~10K-char chunker never
 * has to re-split a single message.
 *
 * <p>The {@code sessionId} is fixed ({@value #SESSION_ID}): combined with the daemon's
 * content-addressed ids, re-running {@code pieria init} on unchanged docs produces no duplicate
 * memories.
 */
public final class TranscriptBuilder {

  /** Stable session id so re-ingesting unchanged docs is idempotent (content-addressed). */
  public static final String SESSION_ID = "pieria-init";

  /** Per-message ceiling, under the daemon's ~10K chunk boundary so it never re-splits a message. */
  static final int MAX_MESSAGE_CHARS = 8_000;

  /** Start of a top-level or second-level ATX heading line. */
  private static final Pattern SECTION_HEADING = Pattern.compile("(?m)^#{1,2}\\s");

  /**
   * Build the ingest request from the given docs. Docs that yield no non-blank content contribute
   * no messages; the result may therefore have fewer messages than docs (or be empty).
   */
  public IngestRequest build(List<Doc> docs) throws IOException {
    List<MessageDto> messages = new ArrayList<>();
    for (Doc doc : docs) {
      String content = Files.readString(doc.absolute());
      messages.addAll(toMessages(doc.relative(), content));
    }
    return new IngestRequest(SESSION_ID, messages);
  }

  /** Provenance prefix that tells the extractor which file a chunk of text came from. */
  static String provenance(Path relative) {
    return "Project documentation — " + relative + ":\n\n";
  }

  /**
   * Split one document's content into provenance-prefixed user messages. Blank sections are
   * dropped; oversize sections are hard-split on paragraph boundaries.
   */
  static List<MessageDto> toMessages(Path relative, String content) {
    String prefix = provenance(relative);
    List<MessageDto> messages = new ArrayList<>();
    for (String section : splitIntoSections(content)) {
      if (section.isBlank()) {
        continue;
      }
      for (String piece : hardSplit(section, MAX_MESSAGE_CHARS - prefix.length())) {
        if (!piece.isBlank()) {
          messages.add(new MessageDto("user", prefix + piece));
        }
      }
    }
    return messages;
  }

  /**
   * Split markdown on top-level/second-level headings, keeping each heading with the body that
   * follows it. A file with no such headings yields a single section (the whole file).
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
    if (starts.get(0) > 0) {
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
        if (current.length() > 0) {
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
    if (current.length() > 0) {
      pieces.add(current.toString());
    }
    return pieces;
  }
}
