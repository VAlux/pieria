package dev.alvo.pieria.ingestion.transcript;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Indexes the available {@link TranscriptParser} implementations by their {@link
 * TranscriptParser#harness() harness id} and dispatches an incoming transcript to the right one.
 *
 * <p>Spring injects every {@code TranscriptParser} bean, so registering a new harness parser is
 * enough to make it selectable via the {@code harness} request parameter — no change here is needed.
 */
@Component
public class TranscriptParserRegistry {

  private final Map<String, TranscriptParser> byHarness;

  public TranscriptParserRegistry(List<TranscriptParser> parsers) {
    Map<String, TranscriptParser> index = new LinkedHashMap<>();
    for (TranscriptParser parser : parsers) {
      TranscriptParser previous = index.put(parser.harness(), parser);

      if (previous != null) {
        throw new IllegalStateException(
          "Two TranscriptParser beans claim harness '" + parser.harness() + "': "
            + previous.getClass().getName() + " and " + parser.getClass().getName());
      }
    }

    this.byHarness = Map.copyOf(index);
  }

  /**
   * @param harness harness identifier from the ingest request
   * @return the parser for that harness
   * @throws IllegalArgumentException if no parser is registered for the harness (maps to HTTP 400)
   */
  public TranscriptParser forHarness(String harness) {
    TranscriptParser parser = byHarness.get(harness);

    if (parser == null) {
      throw new IllegalArgumentException("Unsupported harness '" + harness + "'. Supported harnesses: " + byHarness.keySet());
    }

    return parser;
  }

  /**
   * Harness ids with a registered parser.
   */
  public Set<String> supportedHarnesses() {
    return byHarness.keySet();
  }
}
