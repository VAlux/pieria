package dev.alvo.pieria.storage;

import dev.alvo.pieria.domain.temporal.ConflictDecision;

import dev.alvo.pieria.api.request.RevisionRequest;
import dev.alvo.pieria.domain.graph.Entity;
import dev.alvo.pieria.domain.graph.GraphFragment;
import dev.alvo.pieria.domain.memory.Memory;
import dev.alvo.pieria.domain.memory.MemoryType;
import dev.alvo.pieria.domain.memory.Message;
import dev.alvo.pieria.domain.temporal.TemporalSnapshot;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class TemporalStorageTests {
  @TempDir Path dir;
  private SqliteMemoryStore store;
  private JdbcClient jdbc;
  private String profile;
  private DriverManagerDataSource dataSource;

  private void setup() {
    var ds = new DriverManagerDataSource(); ds.setUrl("jdbc:sqlite:" + dir.resolve("temporal.db")
      + "?busy_timeout=10000&transaction_mode=IMMEDIATE");
    dataSource = ds;
    Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
    jdbc = JdbcClient.create(ds); store = new SqliteMemoryStore(jdbc);
    profile = store.getOrCreateProfile("temporal").id();
  }
  private static Instant at(String month) { return Instant.parse("2026-" + month + "-01T00:00:00Z"); }
  private Memory put(String content, String from, String to) {
    return store.store(profile, Memory.of(MemoryType.FACT, content, "s", "owner", "{}")
      .withValidity(at(from), to == null ? null : at(to), content)).stored();
  }
  private List<String> recall(String month, TemporalSnapshot known) {
    var snapshot = new TemporalSnapshot(at(month), known.knownAsOf(), known.sequence(), true);
    return store.atSnapshot(snapshot, () -> store.exactKeyLookup(profile, List.of("owner"), 10)).stream().map(Memory::content).toList();
  }

  @Test void overrideRestorationRetroactiveCorrectionAndReplay() {
    setup();
    Memory alice = put("Alice owns the service", "01", null);
    var beforeBob = store.temporalSnapshot(null, null);
    Memory bob = put("Bob owns the service", "03", "04");
    var beforeCorrection = store.temporalSnapshot(null, null);
    assertEquals(List.of(alice.content()), recall("03", beforeBob));
    assertEquals(List.of(bob.content()), recall("03", beforeCorrection));
    assertEquals(List.of(alice.content()), recall("04", beforeCorrection));
    long sequence = beforeCorrection.sequence();
    put("Bob owns the service", "03", "04");
    assertEquals(sequence, store.temporalSnapshot(null, null).sequence());
    var revision = store.revisions(profile, bob.id()).getLast();
    var request = new RevisionRequest(revision.assertionId(), revision.revisionId(), "Carol owns the service", null, null, null);
    store.revise(profile, bob.id(), request);
    var after = store.temporalSnapshot(null, null);
    assertEquals(List.of(bob.content()), recall("03", beforeCorrection));
    assertEquals(List.of("Carol owns the service"), recall("03", after));
    assertEquals(List.of(alice.content()), recall("04", after));
    assertThrows(TemporalConflictException.class, () -> store.revise(profile, bob.id(), request));
    assertEquals(2, store.revisions(profile, bob.id()).size());
  }

  @Test void factHistoryReturnsResolvedGroupRowsAndDoesNotResurrectForgottenWinners() {
    setup();
    Memory alice = put("Alice owns the service", "01", null);
    Memory bob = put("Bob owns the service", "03", "04");
    var march = new TemporalSnapshot(at("03"), store.temporalSnapshot(null, null).knownAsOf(),
      store.temporalSnapshot(null, null).sequence(), true);
    var history = store.factHistory(profile, alice.id(), march).orElseThrow();
    assertEquals("fact:owner", history.factGroup());
    assertEquals(2, history.revisions().size());
    assertEquals(bob.id(), history.revisions().stream()
      .filter(r -> r.revisionId().equals(history.selected().getFirst().revisionId()))
      .findFirst().orElseThrow().memoryId());

    assertTrue(store.forgetMemory(profile, bob.id()));
    var afterForget = store.factHistory(profile, alice.id(), march).orElseThrow();
    assertTrue(afterForget.revisions().stream().noneMatch(r -> r.memoryId().equals(bob.id())));
    assertTrue(afterForget.selected().isEmpty(), "forgetting the winner must leave a gap, not restore Alice");
  }

  @Test void forgettingHidesEveryCutoffAndDoesNotRestoreCompetitorsOrEmbeddings() {
    setup(); put("Alice owns the service", "01", null);
    Memory bob = put("Bob owns the service", "03", null);
    var beforeForget = store.temporalSnapshot(null, null);
    assertTrue(store.forgetMemory(profile, bob.id()));
    assertEquals(List.of(), recall("03", beforeForget));
    store.completeVectorization(bob.id(), new float[]{1,2});
    assertTrue(store.embeddingsFor(profile, List.of(bob.id())).isEmpty());
    assertEquals(List.of(), recall("03", store.temporalSnapshot(null, null)));
  }

  @Test void bothAxesAreHalfOpenAndProfilesAreIsolated() {
    setup(); Memory bob = put("Bob owns the service", "03", "04");
    var revision = store.revisions(profile, bob.id()).getLast();
    long seq = store.temporalSnapshot(null, null).sequence();
    var before = new TemporalSnapshot(at("03"), revision.recordedFrom().minusNanos(1), seq, true);
    assertTrue(recall("03", before).isEmpty());
    var inclusive = new TemporalSnapshot(at("03"), revision.recordedFrom(), seq, true);
    assertEquals(List.of(bob.content()), recall("03", inclusive));
    assertTrue(recall("04", inclusive).isEmpty());
    profile = store.getOrCreateProfile("other").id();
    assertTrue(recall("03", inclusive).isEmpty());
  }
  @Test void everyMemoryChannelAndGraphHopUsesTheSameKnowledgeSnapshot() {
    setup();
    Memory alice = put("Alice owns the service", "01", null);
    var beforeAttachment = store.temporalSnapshot(null, null);
    var graph = new GraphFragment(
      List.of(Entity.of("person", "alice", "{}"), Entity.of("service", "service", "{}")),
      List.of(new GraphFragment.EdgeTriple("alice", "person", "owns", "service", "service")));
    store.attachGraph(profile, alice.id(), graph);
    store.insertMessages(profile, "s", List.of(new Message(null, "s", "user", "secretkeyword", at("01"))));
    var afterAttachment = store.temporalSnapshot(null, null);
    assertTrue(store.atSnapshot(beforeAttachment, () -> store.searchMemoriesByMessageFts(profile, "secretkeyword", 10)).isEmpty());
    assertEquals(1, store.atSnapshot(afterAttachment, () -> store.searchMemoriesByMessageFts(profile, "secretkeyword", 10)).size());
    assertTrue(store.atSnapshot(beforeAttachment, () -> store.entitiesForMemories(profile, List.of(alice.id()), 10)).isEmpty());
    var entities = store.atSnapshot(afterAttachment, () -> store.entitiesForMemories(profile, List.of(alice.id()), 10));
    assertEquals(2, entities.size());
    String seed = entities.getFirst().id();
    assertEquals(List.of(seed), store.atSnapshot(beforeAttachment, () -> store.neighborhood(profile, List.of(seed), 2, 10)));
    assertEquals(2, store.atSnapshot(afterAttachment, () -> store.neighborhood(profile, List.of(seed), 2, 10)).size());
    Memory bob = put("Bob owns the service", "03", null);
    var before = new TemporalSnapshot(at("03"), beforeAttachment.knownAsOf(), beforeAttachment.sequence(), true);
    assertEquals(List.of(alice.id()), store.atSnapshot(before, () -> store.searchMemoriesFts(profile, "service", 10)).stream().map(Memory::id).toList());
    assertFalse(store.atSnapshot(before, () -> store.searchMemoriesFts(profile, "service", 10)).stream().anyMatch(m -> m.id().equals(bob.id())));
  }

  @Test void concurrentCorrectionsHaveOneWinnerAndOneConflict() throws Exception {
    setup(); Memory alice = put("Alice owns the service", "01", null);
    var revision = store.revisions(profile, alice.id()).getLast();
    var barrier = new CyclicBarrier(2);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var tasks = List.of("Bob", "Carol").stream().map(owner -> executor.submit(() -> {
        barrier.await();
        var transaction = new org.springframework.transaction.support.TransactionTemplate(
          new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource));
        try {
          transaction.execute(_ -> store.revise(profile, alice.id(), new RevisionRequest(revision.assertionId(), revision.revisionId(),
            owner + " owns the service", null, null, null)));
          return "updated";
        } catch (TemporalConflictException e) { return "conflict"; }
      })).toList();
      var outcomes = List.of(tasks.get(0).get(), tasks.get(1).get());
      assertEquals(1, outcomes.stream().filter("updated"::equals).count());
      assertEquals(1, outcomes.stream().filter("conflict"::equals).count());
    }
    assertEquals(2, store.revisions(profile, alice.id()).size());
  }

  @Test void repeatedContentInDistinctTurnsKeepsSeparateAssertions() {
    setup();
    Memory content = Memory.of(MemoryType.FACT, "Alice owns the service", "s", "owner", "{}");
    Memory first = store.store(profile, content.withValidity(at("01"), at("02"), "turn-1")).stored();
    Memory second = store.store(profile, content.withValidity(at("03"), null, "turn-2")).stored();
    assertEquals(first.id(), second.id());
    assertEquals(2, store.revisions(profile, first.id()).size());
    var snapshot = store.temporalSnapshot(null, null);
    assertTrue(recall("02", snapshot).isEmpty());
    assertEquals(List.of(first.content()), recall("03", snapshot));
  }

  @Test void boundedCorrectionCanReopenAndInvalidationHasAnEffectiveBoundary() {
    setup(); Memory alice = put("Alice owns the service", "01", null);
    Memory bob = put("Bob owns the service", "03", "04");
    var revision = store.revisions(profile, bob.id()).getLast();
    var reopened = store.revise(profile, bob.id(), new RevisionRequest(revision.assertionId(), revision.revisionId(), null, null, null, null, true));
    assertEquals(List.of(bob.content()), recall("05", store.temporalSnapshot(null, null)));
    store.revise(profile, bob.id(), new RevisionRequest(reopened.assertionId(), reopened.revisionId(), null, null, null, "2026-05-01T00:00:00Z"));
    assertEquals(List.of(alice.content()), recall("05", store.temporalSnapshot(null, null)));
  }

  @Test void directStoreEntryRollsBackContentAndLineageOnInvalidDefaultBounds() {
    setup(); Memory alice = put("Alice owns the service", "01", null);
    var interceptor = new org.springframework.transaction.interceptor.TransactionInterceptor();
    interceptor.setTransactionManager(new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource));
    interceptor.setTransactionAttributeSource(new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource());
    var factory = new org.springframework.aop.framework.ProxyFactory(store);
    factory.addAdvice(interceptor);
    MemoryStore transactional = (MemoryStore) factory.getProxy();
    long rows = jdbc.sql("SELECT COUNT(*) FROM memories").query(Long.class).single();
    assertThrows(IllegalArgumentException.class, () -> transactional.store(profile,
      Memory.of(MemoryType.FACT, "Invalid owner", "s", "owner", "{}").withValidity(null, at("01"), null)));
    assertEquals(rows, jdbc.sql("SELECT COUNT(*) FROM memories").query(Long.class).single());
    assertEquals(List.of(alice.content()), recall("03", store.temporalSnapshot(null, null)));
  }

  @Test void semanticGroupReplacementAndStaleDecisionRevalidation() {
    setup(); Memory alice = put("Alice owns the service", "01", null);
    var previous = store.revisions(profile, alice.id()).getLast();
    var decision = new ConflictDecision(previous.revisionId(), "replacement", "service", "owns",
      "Bob owns the service", "Alice owns the service");
    Memory bob = store.store(profile, Memory.of(MemoryType.FACT, "Bob owns the service", "s", "different.key", "{}")
      .withValidity(at("03"), at("04"), "bob-turn").withConflict(decision)).stored();
    var march = store.temporalSnapshot("2026-03-01T00:00:00Z", null);
    assertEquals(List.of(bob.id()), store.atSnapshot(march, () -> store.searchMemoriesFts(profile, "service", 10)).stream().map(Memory::id).toList());
    assertEquals(List.of(alice.content()), recall("04", store.temporalSnapshot(null, null)));
    assertEquals(3, store.validityHistory(profile, alice.id()).size());
    store.revise(profile, alice.id(), new RevisionRequest(previous.assertionId(), previous.revisionId(), "Carol owns the service", null, null, null));
    Memory stale = store.store(profile, Memory.of(MemoryType.FACT, "Dan owns the service", "s", "independent.key", "{}")
      .withValidity(at("05"), null, "dan-turn").withConflict(decision)).stored();
    var may = store.temporalSnapshot("2026-05-01T00:00:00Z", null);
    var contents = store.atSnapshot(may, () -> store.searchMemoriesFts(profile, "service", 10)).stream().map(Memory::content).toList();
    assertTrue(contents.contains("Carol owns the service"));
    assertTrue(contents.contains(stale.content()), "stale semantic proposals cannot invalidate an updated assertion");
  }

  @Test void forgettingRacingWithVectorCompletionCannotRestoreEmbedding() throws Exception {
    setup(); Memory alice = put("Alice owns the service", "01", null);
    var barrier = new CyclicBarrier(2);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var futures = List.of(true, false).stream().map(forget -> executor.submit(() -> {
        barrier.await();
        var transaction = new org.springframework.transaction.support.TransactionTemplate(
          new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource));
        transaction.executeWithoutResult(_ -> {
          if (forget) store.forgetMemory(profile, alice.id());
          else store.completeVectorization(alice.id(), new float[]{1, 2});
        });
        return true;
      })).toList();
      for (var future : futures) assertTrue(future.get());
    }
    assertTrue(store.embeddingsFor(profile, List.of(alice.id())).isEmpty());
    assertTrue(store.drainOutbox(10).isEmpty());
    assertTrue(store.revisions(profile, alice.id()).isEmpty());
    assertTrue(store.validityHistory(profile, alice.id()).isEmpty());
  }

}
