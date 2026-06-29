package dev.alvo.pieria.model;

import dev.alvo.pieria.config.PieriaProperties;
import dev.alvo.pieria.domain.graph.GraphFragment;
import dev.alvo.pieria.domain.memory.MemoryType;
import dev.alvo.pieria.ingestion.model.Classification;
import dev.alvo.pieria.ingestion.model.ExtractedCandidate;
import dev.alvo.pieria.ingestion.model.VerificationResult;
import dev.alvo.pieria.ingestion.model.VerificationVerdict;
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
    PieriaProperties properties = new PieriaProperties(null, null, null,
      new PieriaProperties.Model("extract-model", "synth-model", "embed", 1024, null), null, null);
    ChatClient client = ChatClient.builder(model)
      .defaultOptions(OpenAiChatOptions.builder().model("extract-model")).build();
    return new OpenAiModelGateway(client, client, null, properties);
  }

  private static ExtractedCandidate candidate(int n) {
    return new ExtractedCandidate("candidate " + n, null, 0, "extract");
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
  void extractGraphAllResolvesEveryMemoryInOneCall() {
    CountingChatModel model = new CountingChatModel(
      "{\"memories\":[{\"index\":1,\"entities\":[{\"name\":\"Redis\",\"type\":\"tool\"},"
        + "{\"name\":\"Sessions\",\"type\":\"concept\"}],"
        + "\"triples\":[{\"sourceName\":\"Redis\",\"sourceType\":\"tool\",\"relation\":\"powers\","
        + "\"targetName\":\"Sessions\",\"targetType\":\"concept\"}]},"
        + "{\"index\":2,\"entities\":[],\"triples\":[]}]}");
    OpenAiModelGateway gateway = gateway(model);

    List<GraphFragment> results = gateway.extractGraphAll(List.of("redis powers sessions", "nothing here"));

    assertThat(model.calls).hasValue(1); // one batched call for two memories
    assertThat(results).hasSize(2);
    assertThat(results.get(0).allEntities()).hasSize(2);
    assertThat(results.get(0).triples()).hasSize(1);
    assertThat(results.get(1).allEntities()).isEmpty();
    assertThat(results.get(1).triples()).isEmpty();
  }
}
