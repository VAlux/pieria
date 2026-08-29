package dev.alvo.pieria.ingestion.trace;

import dev.alvo.pieria.domain.code.CodeSymbol;
import dev.alvo.pieria.storage.CodeIndexStore;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves the code a trace talks about against the Phase 13 index, so "why did this test fail"
 * retrieves the trace <em>and</em> the failing class.
 *
 * <p>The ids this returns are written to the memory payload under {@code symbolIds} — the key
 * {@code SqliteMemoryStore.findCodeMemoriesBySymbolIds} already queries. That is what makes the
 * join free: no schema change, no new channel.
 *
 * <p>Extraction is deterministic regex; resolution is qualified-name first (stack frames are
 * unambiguous), then bare name (test-failure lines can collide, so they are the fallback). A
 * profile that was never indexed resolves nothing rather than failing.
 */
public class TraceCodeLinker {

  /** A JVM stack frame: {@code at pkg.Class.method(Class.java:52)}. */
  private static final Pattern STACK_FRAME =
    Pattern.compile("\\bat\\s+([A-Za-z_][A-Za-z0-9_.$]*\\.[A-Za-z_][A-Za-z0-9_$]*)\\s*\\(");

  /** Gradle's failure line: {@code SomeTests > someCase FAILED}. */
  private static final Pattern GRADLE_TEST_FAILURE =
    Pattern.compile("([A-Za-z_][A-Za-z0-9_$]*)\\s*>\\s*[^\\n]*?\\bFAILED\\b");

  /** A source path; the file's base name is the symbol candidate. */
  private static final Pattern SOURCE_PATH =
    Pattern.compile("[\\w./-]*?([A-Za-z_][A-Za-z0-9_]*)\\.(?:java|kt|scala|js|ts|tsx|scss|py|go|rs)\\b");

  private final CodeIndexStore store;
  private final int maxLinkedSymbols;

  public TraceCodeLinker(CodeIndexStore store, int maxLinkedSymbols) {
    this.store = store;
    this.maxLinkedSymbols = Math.max(0, maxLinkedSymbols);
  }

  /** Symbol ids for the code this trace references, capped and deduped in resolution order. */
  public List<String> link(String profileId, TraceEvent event) {
    if (maxLinkedSymbols == 0) {
      return List.of();
    }
    String[] texts = {event.error(), event.output(), event.args()};

    List<String> qualified = extract(STACK_FRAME, texts);
    List<String> bare = new ArrayList<>(extract(GRADLE_TEST_FAILURE, texts));
    for (String name : extract(SOURCE_PATH, texts)) {
      if (!bare.contains(name)) {
        bare.add(name);
      }
    }
    if (qualified.isEmpty() && bare.isEmpty()) {
      return List.of();
    }

    Set<String> ids = new LinkedHashSet<>();
    if (!qualified.isEmpty()) {
      collect(ids, store.findSymbolsByQualifiedName(profileId, qualified, maxLinkedSymbols));
    }
    if (ids.size() < maxLinkedSymbols && !bare.isEmpty()) {
      collect(ids, store.findSymbolsByName(profileId, bare, maxLinkedSymbols));
    }
    return ids.stream().limit(maxLinkedSymbols).toList();
  }

  private static void collect(Set<String> ids, List<CodeSymbol> symbols) {
    if (symbols == null) {
      return;
    }
    for (CodeSymbol symbol : symbols) {
      if (symbol != null && symbol.id() != null && !symbol.id().isBlank()) {
        ids.add(symbol.id());
      }
    }
  }

  /** First capture group of every match across the given texts, deduped, in order. */
  private static List<String> extract(Pattern pattern, String... texts) {
    List<String> found = new ArrayList<>();
    for (String text : texts) {
      if (text == null || text.isBlank()) {
        continue;
      }
      Matcher matcher = pattern.matcher(text);
      while (matcher.find()) {
        String value = matcher.group(1);
        if (value != null && !value.isBlank() && !found.contains(value)) {
          found.add(value);
        }
      }
    }
    return found;
  }
}
