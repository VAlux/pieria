package dev.alvo.pieria.ingestion.trace;

import dev.alvo.pieria.api.request.TraceStatus;
import dev.alvo.pieria.domain.code.CodeSymbol;
import dev.alvo.pieria.domain.code.CodeSymbolKind;
import dev.alvo.pieria.storage.CodeIndexStore;
import dev.alvo.pieria.storage.NoOpCodeIndexStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TraceCodeLinkerTests {

  private static final Instant AT = Instant.parse("2026-08-29T10:00:00Z");

  /** Records what was asked for and answers from a fixed table; no model, no database. */
  private static final class RecordingStore extends NoOpCodeIndexStore {
    final List<String> qualifiedQueries = new ArrayList<>();
    final List<String> nameQueries = new ArrayList<>();
    List<CodeSymbol> qualifiedHits = List.of();
    List<CodeSymbol> nameHits = List.of();

    @Override
    public List<CodeSymbol> findSymbolsByQualifiedName(String profileId, List<String> names, int limit) {
      qualifiedQueries.addAll(names);
      return qualifiedHits;
    }

    @Override
    public List<CodeSymbol> findSymbolsByName(String profileId, List<String> names, int limit) {
      nameQueries.addAll(names);
      return nameHits;
    }
  }

  // CodeSymbol's canonical constructor, in order:
  // (id, profileId, fileId, kind, name, qualifiedName, signature, visibility,
  //  startLine, endLine, language, parentSymbolId, path)
  private static CodeSymbol symbol(String id, String qualifiedName) {
    return new CodeSymbol(id, "p1", "file1", CodeSymbolKind.CLASS, qualifiedName, qualifiedName,
      null, "public", 1, 2, "java", null, "src/Foo.java");
  }

  private static TraceEvent event(String error) {
    return new TraceEvent("tid", "s1", "Bash", "./gradlew test", "", TraceStatus.FAILURE, 1,
      error, AT, false, 0);
  }

  @Test
  void javaStackFramesResolveByQualifiedName() {
    RecordingStore store = new RecordingStore();
    store.qualifiedHits = List.of(symbol("sym1", "dev.alvo.Foo.bar"));

    List<String> ids = new TraceCodeLinker(store, 10)
      .link("p1", event("at dev.alvo.Foo.bar(Foo.java:52)"));

    assertThat(store.qualifiedQueries).contains("dev.alvo.Foo.bar");
    assertThat(ids).containsExactly("sym1");
  }

  @Test
  void gradleFailureLinesResolveByBareName() {
    RecordingStore store = new RecordingStore();
    store.nameHits = List.of(symbol("sym2", "dev.alvo.GroundingFilterTests"));

    List<String> ids = new TraceCodeLinker(store, 10)
      .link("p1", event("GroundingFilterTests > grounded FAILED"));

    assertThat(store.nameQueries).contains("GroundingFilterTests");
    assertThat(ids).containsExactly("sym2");
  }

  @Test
  void bareSourcePathsAreResolvedByFileName() {
    RecordingStore store = new RecordingStore();
    store.nameHits = List.of(symbol("sym3", "dev.alvo.Redaction"));

    new TraceCodeLinker(store, 10).link("p1", event("error in modules/shared/Redaction.java"));

    assertThat(store.nameQueries).contains("Redaction");
  }

  @Test
  void resultsAreDedupedAndCapped() {
    RecordingStore store = new RecordingStore();
    store.qualifiedHits = List.of(symbol("s1", "a"), symbol("s1", "a"), symbol("s2", "b"),
      symbol("s3", "c"));

    List<String> ids = new TraceCodeLinker(store, 2)
      .link("p1", event("at dev.alvo.Foo.bar(Foo.java:1)"));

    assertThat(ids).containsExactly("s1", "s2");
  }

  // A profile that was never code-indexed must degrade to "no links", never to an error.
  @Test
  void aProfileWithNoCodeIndexResolvesNothing() {
    List<String> ids = new TraceCodeLinker(new NoOpCodeIndexStore(), 10)
      .link("p1", event("at dev.alvo.Foo.bar(Foo.java:52)"));

    assertThat(ids).isEmpty();
  }

  @Test
  void aTraceWithNoCodeReferencesQueriesNothing() {
    RecordingStore store = new RecordingStore();

    List<String> ids = new TraceCodeLinker(store, 10).link("p1", event("connection refused"));

    assertThat(ids).isEmpty();
    assertThat(store.qualifiedQueries).isEmpty();
    assertThat(store.nameQueries).isEmpty();
  }

  @Test
  void aZeroCapDisablesLinking() {
    RecordingStore store = new RecordingStore();
    store.qualifiedHits = List.of(symbol("s1", "a"));

    assertThat(new TraceCodeLinker(store, 0).link("p1", event("at dev.alvo.Foo.bar(Foo.java:1)")))
      .isEmpty();
  }
}
