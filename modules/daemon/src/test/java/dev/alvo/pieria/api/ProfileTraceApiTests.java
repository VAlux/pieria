package dev.alvo.pieria.api;

import dev.alvo.pieria.config.VerifyMode;

import dev.alvo.pieria.api.controller.ProfileController;
import dev.alvo.pieria.api.controller.ProfilesController;
import dev.alvo.pieria.api.error.GlobalExceptionHandler;
import dev.alvo.pieria.api.request.RecallMode;
import dev.alvo.pieria.config.PieriaProperties;
import dev.alvo.pieria.config.TraceProperties;
import dev.alvo.pieria.ingestion.Chunker;
import dev.alvo.pieria.ingestion.IngestionService;
import dev.alvo.pieria.ingestion.TranscriptNormalizer;
import dev.alvo.pieria.ingestion.trace.TraceIngestionService;
import dev.alvo.pieria.model.ModelGateway;
import dev.alvo.pieria.retrieval.RetrievalService;
import dev.alvo.pieria.storage.CodeIndexStore;
import dev.alvo.pieria.storage.MemoryStore;
import dev.alvo.pieria.storage.NoOpCodeIndexStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Wire-level coverage for routing {@code traces} through {@code POST /ingest}: the
 * {@code messages}-only compatibility guarantee, a traces-only body, a mixed body, the
 * {@code @AssertTrue} guard that rejects neither, and lenient {@link dev.alvo.pieria.api.request.TraceStatus}
 * parsing at the JSON boundary.
 */
@WebMvcTest(controllers = {ProfileController.class, ProfilesController.class, GlobalExceptionHandler.class})
@Import({ProfileTraceApiTests.Wiring.class, IngestionService.class, RetrievalService.class,
  dev.alvo.pieria.retrieval.DeterministicQueryAnalyzer.class,
  TranscriptNormalizer.class, Chunker.class,
  dev.alvo.pieria.ingestion.transcript.TranscriptParserRegistry.class,
  dev.alvo.pieria.ingestion.transcript.ClaudeCodeTranscriptParser.class,
  dev.alvo.pieria.ingestion.transcript.CodexTranscriptParser.class,
  TraceIngestionService.class})
class ProfileTraceApiTests {

  @Autowired
  MockMvc mockMvc;

  // The compatibility guarantee: the payload every existing caller sends must keep working.
  @Test
  void messagesOnlyIngestStillWorks() throws Exception {
    mockMvc.perform(post("/v1/profiles/p/ingest")
        .contentType(MediaType.APPLICATION_JSON)
        .content("""
          {"sessionId":"s1","messages":[{"role":"user","content":"hello"}]}"""))
      .andExpect(status().isOk());
  }

  // Asserting only status().isOk() here would also pass for a controller that silently drops
  // traces: IngestionService.ingest tolerates an empty messages list and returns 0 memories
  // without throwing. Pin the routing itself: a stored count of 1, and that the stored memory is
  // the TraceIngestionService-derived event, not something IngestionService produced.
  @Test
  void tracesOnlyIngestIsAccepted() throws Exception {
    mockMvc.perform(post("/v1/profiles/p/ingest")
        .contentType(MediaType.APPLICATION_JSON)
        .content("""
          {"sessionId":"s1","traces":[
            {"tool":"Bash","args":"./gradlew test","status":"failure","exitCode":1,
             "output":"BUILD FAILED","endedAt":"2026-08-29T10:00:00Z"}]}"""))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.count", is(1)))
      .andExpect(jsonPath("$.memories", hasSize(1)))
      .andExpect(jsonPath("$.memories[0].type", is("event")));
  }

  // Same rationale as tracesOnlyIngestIsAccepted: pin that BOTH paths ran, not merely that the
  // response was 200. The messages branch alone (StubModelGateway extracts exactly one FACT per
  // chunk) would satisfy a bare isOk() even if the traces branch were dropped entirely.
  @Test
  void mixedIngestIsAccepted() throws Exception {
    mockMvc.perform(post("/v1/profiles/p/ingest")
        .contentType(MediaType.APPLICATION_JSON)
        .content("""
          {"sessionId":"s1",
           "messages":[{"role":"user","content":"run the tests"}],
           "traces":[{"tool":"Bash","args":"./gradlew test","status":"success","exitCode":0}]}"""))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.count", is(2)))
      .andExpect(jsonPath("$.memories", hasSize(2)))
      .andExpect(jsonPath("$.memories[0].type", is("fact")))
      .andExpect(jsonPath("$.memories[1].type", is("event")));
  }

  // The @AssertTrue guard that replaced @NotEmpty on messages.
  @Test
  void anIngestCarryingNeitherIsRejected() throws Exception {
    mockMvc.perform(post("/v1/profiles/p/ingest")
        .contentType(MediaType.APPLICATION_JSON)
        .content("""
          {"sessionId":"s1","messages":[],"traces":[]}"""))
      .andExpect(status().isBadRequest());
  }

  @Test
  void anUnknownTraceStatusDegradesRatherThanFailing() throws Exception {
    mockMvc.perform(post("/v1/profiles/p/ingest")
        .contentType(MediaType.APPLICATION_JSON)
        .content("""
          {"sessionId":"s1","traces":[
            {"tool":"Bash","args":"./gradlew test","status":"weird"}]}"""))
      .andExpect(status().isOk());
  }

  @Test
  void aTraceWithoutAToolIsRejected() throws Exception {
    mockMvc.perform(post("/v1/profiles/p/ingest")
        .contentType(MediaType.APPLICATION_JSON)
        .content("""
          {"sessionId":"s1","traces":[{"args":"./gradlew test","status":"success"}]}"""))
      .andExpect(status().isBadRequest());
  }

  @TestConfiguration
  static class Wiring {
    private final StubMemoryStore store = new StubMemoryStore();
    private final StubModelGateway model = new StubModelGateway();

    @Bean("profileTraceApiMemoryStore")
    MemoryStore memoryStore() {
      return store;
    }

    @Bean("profileTraceApiModelGateway")
    @org.springframework.context.annotation.Primary
    ModelGateway modelGateway() {
      return model;
    }

    @Bean("profileTraceApiCodeIndexStore")
    CodeIndexStore codeIndexStore() {
      return new NoOpCodeIndexStore();
    }

    @Bean("profileTraceApiTraceProperties")
    TraceProperties traceProperties() {
      return TraceProperties.defaults();
    }

    @Bean("profileTraceApiPieriaProperties")
    PieriaProperties pieriaProperties() {
      return new PieriaProperties(null, null, null, null,
        new PieriaProperties.Ingestion(10000, 2, 4, VerifyMode.ALWAYS, 1, 0, 0, false, 3, 3, 32, 5, false, 5000, true, 0.70),
        new PieriaProperties.Retrieval(false, 60, 3.0, 1.0, 1.0, 1.0, 0.5, 1.0, 2, 20, 8, 10, 3000, 0.0, 0.0, 2, 20, 8, "heuristic", RecallMode.SYNTHESIZED, 0.60, 0.78),
        new PieriaProperties.Stats(0.0, 200000, java.util.Map.of(
          "extraction", new PieriaProperties.Stats.TierPrice(0.30, 0.60),
          "synthesis", new PieriaProperties.Stats.TierPrice(3.0, 15.0),
          "embedding", new PieriaProperties.Stats.TierPrice(0.02, 0.0))));
    }

    @Bean("profileTraceApiEffectiveConfigResolver")
    dev.alvo.pieria.config.EffectiveConfigResolver effectiveConfigResolver() {
      return dev.alvo.pieria.config.EffectiveConfigResolver.withoutOverrides(pieriaProperties());
    }

    @Bean("profileTraceApiTaskRegistry")
    dev.alvo.pieria.task.TaskRegistry taskRegistry() {
      return new dev.alvo.pieria.task.TaskRegistry();
    }

    @Bean("profileTraceApiObjectMapper")
    com.fasterxml.jackson.databind.ObjectMapper objectMapper() {
      return new com.fasterxml.jackson.databind.ObjectMapper()
        .findAndRegisterModules();
    }

    @Bean("profileTraceApiProfileService")
    dev.alvo.pieria.profile.ProfileService profileService(MemoryStore store) {
      return new dev.alvo.pieria.profile.ProfileService(store);
    }

    @Bean("profileTraceApiProfileStatsService")
    dev.alvo.pieria.profile.ProfileStatsService profileStatsService(MemoryStore store, PieriaProperties properties) {
      return new dev.alvo.pieria.profile.ProfileStatsService(store, properties);
    }
  }
}
