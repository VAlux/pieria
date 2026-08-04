package dev.alvo.pieria.model;

import dev.alvo.pieria.config.PieriaProperties;
import dev.alvo.pieria.config.VerifyMode;
import dev.alvo.pieria.domain.graph.Entity;
import dev.alvo.pieria.domain.graph.GraphFragment;
import dev.alvo.pieria.domain.memory.MemoryType;
import dev.alvo.pieria.ingestion.model.Chunk;
import dev.alvo.pieria.ingestion.model.Classification;
import dev.alvo.pieria.ingestion.model.UnifiedCandidate;
import dev.alvo.pieria.ingestion.model.VerificationResult;
import dev.alvo.pieria.ingestion.model.VerificationVerdict;
import dev.alvo.pieria.model.provider.OllamaModelProviderAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Verifies batched verify/classify: many candidates are resolved in a single model call (aligned by
 * 1-based index), and an unusable batch response falls back to per-item calls. Driven through a
 * call-counting fake {@link ChatModel}, so no live provider is needed.
 */
class OpenAiModelGatewayBatchTests {

  /** Counts model invocations and returns a canned (per-call) assistant message. */
  private static final class CountingChatModel implements ChatModel {
    final AtomicInteger calls = new AtomicInteger();
    private final String cannedContent;

    CountingChatModel(String cannedContent) {
      this.cannedContent = cannedContent;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
      calls.incrementAndGet();
      return new ChatResponse(List.of(new Generation(new AssistantMessage(cannedContent))));
    }

    @Override
    public ChatOptions getDefaultOptions() {
      return OpenAiChatOptions.builder().build();
    }
  }

  private static OpenAiModelGateway gateway(CountingChatModel model) {
    return gateway(model, null);
  }

  private static OpenAiModelGateway gateway(CountingChatModel model, PieriaProperties.Ingestion ingestion) {
    PieriaProperties properties = new PieriaProperties(null, null, null,
      new PieriaProperties.Model("extract-model", "synth-model", "embed", 1024, 4, null, null),
      ingestion, null, null);
    ChatClient client = ChatClient.builder(model)
      .defaultOptions(OpenAiChatOptions.builder().model("extract-model")).build();
    return new OpenAiModelGateway(client, client, null, properties, new OllamaModelProviderAdapter());
  }

  private static String candidate(int n) {
    return "candidate " + n;
  }

  private static Chunk chunk(String transcript) {
    return new Chunk(0, 0, 0, List.of(), transcript);
  }

  @Test
  void verifyAllResolvesEveryCandidateInOneCall() {
    CountingChatModel model = new CountingChatModel(
      "{\"verdicts\":[{\"index\":1,\"verdict\":\"pass\",\"content\":\"candidate 1\",\"reason\":\"ok\"},"
        + "{\"index\":2,\"verdict\":\"drop\",\"content\":\"\",\"reason\":\"unsupported\"},"
        + "{\"index\":3,\"verdict\":\"pass\",\"content\":\"candidate 3\",\"reason\":\"ok\"}]}");
    OpenAiModelGateway gateway = gateway(model);

    List<VerificationResult> results = gateway.verifyAll(
      List.of(candidate(1), candidate(2), candidate(3)), "transcript");

    assertThat(model.calls).hasValue(1); // one batched call for three candidates
    assertThat(results).hasSize(3);
    assertThat(results.get(0).verdict()).isEqualTo(VerificationVerdict.PASS);
    assertThat(results.get(1).verdict()).isEqualTo(VerificationVerdict.DROP);
    assertThat(results.get(2).verdict()).isEqualTo(VerificationVerdict.PASS);
  }

  @Test
  void verifyAllFallsBackToPerCandidateOnUnusableBatch() {
    // Structurally valid but empty → mapBatchVerdicts returns null → per-candidate fallback.
    CountingChatModel model = new CountingChatModel("{\"verdicts\":[]}");
    OpenAiModelGateway gateway = gateway(model);

    List<VerificationResult> results = gateway.verifyAll(List.of(candidate(1), candidate(2)), "transcript");

    assertThat(model.calls).hasValue(3); // 1 failed batch + 2 per-candidate retries
    assertThat(results).hasSize(2);
  }

  @Test
  void classifyAllResolvesEveryMemoryInOneCall() {
    CountingChatModel model = new CountingChatModel(
      "{\"items\":[{\"index\":1,\"type\":\"fact\",\"topicKey\":\"user.editor\",\"interrogativeQueries\":[\"q\"],\"payload\":\"{}\"},"
        + "{\"index\":2,\"type\":\"task\",\"topicKey\":\"\",\"interrogativeQueries\":[\"q\"],\"payload\":\"{}\"}]}");
    OpenAiModelGateway gateway = gateway(model);

    List<Classification> results = gateway.classifyAll(List.of("a fact", "a task"));

    assertThat(model.calls).hasValue(1);
    assertThat(results).hasSize(2);
    assertThat(results.get(0).type()).isEqualTo(MemoryType.FACT);
    assertThat(results.get(0).topicKey()).isEqualTo("user.editor");
    assertThat(results.get(1).type()).isEqualTo(MemoryType.TASK);
  }

  @Test
  void classifyAllDegradesToPlainFactsOnMalformedJson() {
    // A raw unescaped newline inside a JSON string value — exactly the model output that aborted an
    // ingest in production. The batch parse throws; classifyAll must fall back per-memory and never
    // throw, so ingestion still stores every memory (as a plain fact when classification can't parse).
    CountingChatModel model = new CountingChatModel("{\"items\":[{\"index\":1,\"type\":\"fa\nct\"}]}");
    OpenAiModelGateway gateway = gateway(model);

    List<Classification> results = gateway.classifyAll(List.of("memory one", "memory two"));

    assertThat(results).hasSize(2);
    assertThat(results).allSatisfy(c -> assertThat(c.type()).isEqualTo(MemoryType.FACT));
    assertThat(model.calls).hasValueGreaterThanOrEqualTo(2); // batch attempt + per-memory retries
  }

  @Test
  void verifyAllDegradesToDropsOnMalformedJson() {
    CountingChatModel model = new CountingChatModel("{\"verdicts\":[{\"index\":1,\"verdict\":\"pa\nss\"}]}");
    OpenAiModelGateway gateway = gateway(model);

    List<VerificationResult> results = gateway.verifyAll(List.of(candidate(1), candidate(2)), "transcript");

    assertThat(results).hasSize(2);
    assertThat(results).allSatisfy(r -> assertThat(r.verdict()).isEqualTo(VerificationVerdict.DROP));
  }

  @Test
  void extractUnifiedParsesBareArrayWithClassification() {
    CountingChatModel model = new CountingChatModel(
      "[{\"content\":\"The editor is Zed\",\"type\":\"fact\",\"topicKey\":\"User Editor\","
        + "\"interrogativeQueries\":[\"which editor?\"],\"payload\":\"{}\"},"
        + "{\"content\":\"Ship the release\",\"type\":\"task\"}]");
    OpenAiModelGateway gateway = gateway(model);

    List<UnifiedCandidate> candidates = gateway.extractUnified(chunk("user: I use Zed"));

    assertThat(model.calls).hasValue(1);
    assertThat(candidates).hasSize(2);
    assertThat(candidates.get(0).content()).isEqualTo("The editor is Zed");
    assertThat(candidates.get(0).classification().type()).isEqualTo(MemoryType.FACT);
    assertThat(candidates.get(0).classification().topicKey()).isEqualTo("user.editor");
    assertThat(candidates.get(0).classification().interrogativeQueries()).containsExactly("which editor?");
    assertThat(candidates.get(1).classification().type()).isEqualTo(MemoryType.TASK);
    assertThat(candidates.get(1).classification().topicKey()).isNull(); // tasks are never keyed
  }

  @Test
  void extractUnifiedParsesWrappedObjectAndCodeFences() {
    CountingChatModel model = new CountingChatModel(
      "```json\n{\"candidates\":[{\"content\":\"A fact\",\"type\":\"fact\"}]}\n```");
    OpenAiModelGateway gateway = gateway(model);

    List<UnifiedCandidate> candidates = gateway.extractUnified(chunk("user: hi"));

    assertThat(candidates).hasSize(1);
    assertThat(candidates.get(0).content()).isEqualTo("A fact");
    assertThat(candidates.get(0).classification().type()).isEqualTo(MemoryType.FACT);
  }

  @Test
  void extractUnifiedSalvagesMarkdownOutputThroughClassify() {
    // Non-JSON output with content: lines → the salvage path scrapes the contents and enriches them
    // with a batched classify call (which here also returns non-JSON, degrading to per-item classify
    // that also fails → plain FACTs). The candidates must still come back rather than being lost.
    CountingChatModel model = new CountingChatModel("- content: The user prefers dark mode\n  type: fact");
    OpenAiModelGateway gateway = gateway(model);

    List<UnifiedCandidate> candidates = gateway.extractUnified(chunk("user: dark mode please"));

    assertThat(candidates).hasSize(1);
    assertThat(candidates.get(0).content()).isEqualTo("The user prefers dark mode");
    assertThat(candidates.get(0).classification().type()).isEqualTo(MemoryType.FACT);
  }

  @Test
  void extractUnifiedTreatsUnparseableOutputAsEmpty() {
    CountingChatModel model = new CountingChatModel("no structured output at all");
    OpenAiModelGateway gateway = gateway(model);

    assertThat(gateway.extractUnified(chunk("user: hi"))).isEmpty();
  }

  @Test
  void extractGraphAllResolvesEveryMemoryInOneCall() {
    CountingChatModel model = new CountingChatModel("""
      E1|tool:Redis|concept:Sessions
      T1|Redis|powers|Sessions""");
    OpenAiModelGateway gateway = gateway(model);

    List<GraphFragment> results = gateway.extractGraphAll(List.of("redis powers sessions", "nothing here"));

    assertThat(model.calls).hasValue(1); // one batched call for two memories
    assertThat(results).hasSize(2);
    assertThat(results.get(0).allEntities()).hasSize(2);
    assertThat(results.get(0).triples()).hasSize(1);
    // A memory the model omitted entirely still gets its slot, empty.
    assertThat(results.get(1).allEntities()).isEmpty();
    assertThat(results.get(1).triples()).isEmpty();
  }

  @Test
  void extractGraphAllTakesTripleEndpointTypesFromTheEntityLine() {
    CountingChatModel model = new CountingChatModel("""
      E1|tool:Redis|concept:Sessions
      T1|Redis|powers|Sessions
      T1|Redis|talks to|Undeclared""");
    OpenAiModelGateway gateway = gateway(model);

    GraphFragment fragment = gateway.extractGraphAll(List.of("a", "b")).getFirst();

    assertThat(fragment.triples())
      .extracting(GraphFragment.EdgeTriple::sourceType, GraphFragment.EdgeTriple::targetType)
      .containsExactly(tuple("tool", "concept"), tuple("tool", "concept"));
    // "Undeclared" was never on the E line, so it falls back to the default type rather than being lost.
    assertThat(fragment.triples().get(1).targetName()).isEqualTo("undeclared");
  }

  @Test
  void extractGraphAllCapsEntitiesAndTriplesPerMemory() {
    CountingChatModel model = new CountingChatModel("""
      E1|tool:one|tool:two|tool:three|tool:four|tool:five
      T1|one|uses|two
      T1|two|uses|three
      T1|three|uses|four
      T1|four|uses|five""");
    // Caps of 2/2, tighter than the 3/3 default, so the parser is demonstrably doing the capping.
    PieriaProperties.Ingestion tuning = new PieriaProperties.Ingestion(
      10000, 0, 4, VerifyMode.ALWAYS, 1, 0, 0, false, 2, 2, 32, 5, false, 5000, true, 0.70);

    GraphFragment fragment = gateway(model, tuning).extractGraphAll(List.of("a", "b")).getFirst();

    assertThat(fragment.entities()).hasSize(2);
    assertThat(fragment.triples()).hasSize(2);
    // allEntities() still materializes every triple endpoint, so an edge always has both its nodes:
    // "three" survives as a node because the second triple points at it.
    assertThat(fragment.allEntities()).extracting(Entity::name).containsExactly("one", "two", "three");
  }

  @Test
  void extractGraphAllRetriesTheBatchOnceBeforeFallingBackPerMemory() {
    CountingChatModel model = new CountingChatModel("I'm sorry, I cannot help with extracting a graph here.");
    OpenAiModelGateway gateway = gateway(model);

    List<GraphFragment> results = gateway.extractGraphAll(List.of("a", "b", "c", "d"));

    // Two batch attempts at the compact format, then one JSON call per memory — never more.
    assertThat(model.calls).hasValue(2 + 4);
    assertThat(results).hasSize(4);
    assertThat(results).allSatisfy(fragment -> assertThat(fragment.isEmpty()).isTrue());
  }

  @Test
  void extractGraphAllTreatsAShortEmptyReplyAsNothingToExtract() {
    CountingChatModel model = new CountingChatModel("");
    OpenAiModelGateway gateway = gateway(model);

    List<GraphFragment> results = gateway.extractGraphAll(List.of("a", "b", "c"));

    // "Nothing here" is a legitimate answer, not a parse failure: one call, no retry, no fallback.
    assertThat(model.calls).hasValue(1);
    assertThat(results).hasSize(3);
    assertThat(results).allSatisfy(fragment -> assertThat(fragment.isEmpty()).isTrue());
  }

  @Test
  void evaluationControlsCapQueriesAndCandidatesInTheParser() {
    StringBuilder response = new StringBuilder("[");
    for (int i = 1; i <= 13; i++) {
      if (i > 1) response.append(',');
      response.append("{\"content\":\"fact ").append(i)
        .append("\",\"type\":\"fact\",\"interrogativeQueries\":[\"q1\",\"q2\",\"q3\"]}");
    }
    response.append(']');
    CountingChatModel model = new CountingChatModel(response.toString());
    PieriaProperties.Ingestion tuning = new PieriaProperties.Ingestion(
      10000, 0, 4, VerifyMode.ALWAYS, 1, 2, 12, false, 3, 3, 32, 5, false, 5000, true, 0.70);

    List<UnifiedCandidate> candidates = gateway(model, tuning).extractUnified(chunk("user: facts"));

    assertThat(candidates).hasSize(12);
    assertThat(candidates).allSatisfy(candidate ->
      assertThat(candidate.classification().interrogativeQueries()).containsExactly("q1", "q2"));
  }

  @Test
  void unifiedGraphExperimentParsesOptionalCappedFragments() {
    CountingChatModel model = new CountingChatModel("""
      [{"content":"Redis powers sessions","type":"fact","graphEntities":[
        {"name":"Redis","type":"tool"},{"name":"Sessions","type":"concept"}],
        "graphTriples":[{"sourceName":"Redis","sourceType":"tool","relation":"powers",
        "targetName":"Sessions","targetType":"concept"}]}]
      """);
    PieriaProperties.Ingestion tuning = new PieriaProperties.Ingestion(
      10000, 0, 4, VerifyMode.ALWAYS, 1, 0, 0, true, 3, 3, 32, 5, false, 5000, true, 0.70);

    UnifiedCandidate candidate = gateway(model, tuning)
      .extractUnified(chunk("user: Redis powers sessions")).getFirst();

    assertThat(candidate.graph().entities()).hasSize(2);
    assertThat(candidate.graph().triples()).hasSize(1);
  }
}
