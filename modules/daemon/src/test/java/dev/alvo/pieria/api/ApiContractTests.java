package dev.alvo.pieria.api;

import dev.alvo.pieria.api.controller.ProfileController;
import dev.alvo.pieria.api.error.GlobalExceptionHandler;
import dev.alvo.pieria.api.request.RecallMode;
import dev.alvo.pieria.config.EffectiveConfigResolver;
import dev.alvo.pieria.config.PieriaProperties;
import dev.alvo.pieria.config.TraceProperties;
import dev.alvo.pieria.config.VerifyMode;
import dev.alvo.pieria.ingestion.Chunker;
import dev.alvo.pieria.ingestion.IngestionService;
import dev.alvo.pieria.ingestion.TranscriptNormalizer;
import dev.alvo.pieria.ingestion.trace.TraceIngestionService;
import dev.alvo.pieria.ingestion.transcript.ClaudeCodeTranscriptParser;
import dev.alvo.pieria.ingestion.transcript.CodexTranscriptParser;
import dev.alvo.pieria.ingestion.transcript.TranscriptParserRegistry;
import dev.alvo.pieria.model.ModelGateway;
import dev.alvo.pieria.profile.ProfileService;
import dev.alvo.pieria.profile.ProfileStatsService;
import dev.alvo.pieria.retrieval.DeterministicQueryAnalyzer;
import dev.alvo.pieria.retrieval.RetrievalService;
import dev.alvo.pieria.storage.CodeIndexStore;
import dev.alvo.pieria.storage.MemoryStore;
import dev.alvo.pieria.storage.NoOpCodeIndexStore;
import dev.alvo.pieria.task.TaskRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * JSON contract tests for every gateway-facing ProfileController endpoint. Field names here are the
 * stable contract the MCP gateway depends on; do NOT rename them without a coordinated gateway update.
 *
 * <p>Covered contracts:
 * <ul>
 *   <li>recall: {@code {answer, memories[]}} with memory shape {@code {id, type, content, ...}}</li>
 *   <li>remember (201): memory shape {@code {id, type, content, topicKey, sessionId, superseded, payload, createdAt}}</li>
 *   <li>list: {@code {memories[]}}</li>
 *   <li>forget 204 (success) and 404 (missing)</li>
 *   <li>error body: {@code {error, message}} for bad_request, not_found, model_unavailable</li>
 * </ul>
 */
@WebMvcTest(controllers = {ProfileController.class, GlobalExceptionHandler.class})
@Import({ApiContractTests.Wiring.class, IngestionService.class, RetrievalService.class,
  DeterministicQueryAnalyzer.class,
  TranscriptNormalizer.class, Chunker.class,
  TranscriptParserRegistry.class,
  ClaudeCodeTranscriptParser.class,
  CodexTranscriptParser.class,
  TraceIngestionService.class})
class ApiContractTests {

  @Autowired
  MockMvc mvc;

  @Autowired
  ModelGateway modelGateway;

  StubModelGateway stubModel() {
    return (StubModelGateway) modelGateway;
  }

  // ---- recall contract -------------------------------------------------------------------

  @Test
  void recallResponseHasAnswerAndMemoriesFields() throws Exception {
    storeMemory();
    mvc.perform(post("/v1/profiles/alice/recall")
        .contentType("application/json")
        .content("{\"query\":\"tea\"}"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.answer").exists())
      .andExpect(jsonPath("$.memories").isArray())
      // Wire compatibility: codeEvidence is omitted entirely when there is none.
      .andExpect(jsonPath("$.codeEvidence").doesNotExist());
  }

  @Test
  void recallMemoryShapeIsStable() throws Exception {
    storeMemory();
    mvc.perform(post("/v1/profiles/alice/recall")
        .contentType("application/json")
        .content("{\"query\":\"tea\"}"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.memories[0].id", notNullValue()))
      .andExpect(jsonPath("$.memories[0].type", is("fact")))
      .andExpect(jsonPath("$.memories[0].content", is("Bob likes tea")))
      .andExpect(jsonPath("$.memories[0].topicKey", is("bob-beverage")))
      .andExpect(jsonPath("$.memories[0].sessionId").exists())
      .andExpect(jsonPath("$.memories[0].superseded").exists())
      .andExpect(jsonPath("$.memories[0].payload").exists())
      .andExpect(jsonPath("$.memories[0].createdAt").exists());
  }

  @Test
  void temporalParametersValidateOffsetsAndIndependentDefaults() throws Exception {
    mvc.perform(post("/v1/profiles/alice/recall").contentType("application/json")
        .content("{\"query\":\"tea\",\"mode\":\"evidence\",\"asOf\":\"2020-01-01T02:00:00+02:00\"}"))
      .andExpect(status().isOk()).andExpect(jsonPath("$.temporal.asOf", is("2020-01-01T00:00:00Z")))
      .andExpect(jsonPath("$.temporal.knownAsOf").exists());
    for (String malformed : new String[]{"yesterday", "2020-01-01", "2020-01-01T00:00:00"}) {
      mvc.perform(post("/v1/profiles/alice/recall").contentType("application/json")
          .content("{\"query\":\"tea\",\"asOf\":\"" + malformed + "\"}"))
        .andExpect(status().isBadRequest());
    }
    mvc.perform(post("/v1/profiles/alice/recall").contentType("application/json")
        .content("{\"query\":\"tea\",\"knownAsOf\":\"2999-01-01T00:00:00Z\"}"))
      .andExpect(status().isBadRequest());
    mvc.perform(post("/v1/profiles/alice/memories").contentType("application/json")
        .content("{\"type\":\"fact\",\"content\":\"x\",\"validFrom\":\"2026-02-01T00:00:00Z\",\"validTo\":\"2026-02-01T00:00:00Z\"}"))
      .andExpect(status().isBadRequest());
  }

  // ---- remember 201 contract -------------------------------------------------------------

  @Test
  void rememberReturns201WithMemoryShape() throws Exception {
    mvc.perform(post("/v1/profiles/alice/memories")
        .contentType("application/json")
        .content("{\"type\":\"fact\",\"content\":\"Bob likes tea\",\"sessionId\":\"s1\",\"topicKey\":\"bob-beverage\"}"))
      .andExpect(status().isCreated())
      .andExpect(jsonPath("$.id", notNullValue()))
      .andExpect(jsonPath("$.type", is("fact")))
      .andExpect(jsonPath("$.content", is("Bob likes tea")))
      .andExpect(jsonPath("$.topicKey", is("bob-beverage")))
      .andExpect(jsonPath("$.sessionId", is("s1")))
      .andExpect(jsonPath("$.superseded", is(false)))
      .andExpect(jsonPath("$.payload").exists())
      .andExpect(jsonPath("$.createdAt").exists());
  }

  // ---- list contract ---------------------------------------------------------------------

  @Test
  void listResponseHasMemoriesArray() throws Exception {
    storeMemory();
    mvc.perform(get("/v1/profiles/alice/memories"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.memories").isArray())
      .andExpect(jsonPath("$.memories[0].id", notNullValue()))
      .andExpect(jsonPath("$.memories[0].type", is("fact")))
      .andExpect(jsonPath("$.memories[0].content", is("Bob likes tea")));
  }

  @Test
  void factHistoryReturnsBothAxesAndAssertionProvenance() throws Exception {
    String id = storeMemory();
    mvc.perform(get("/v1/profiles/alice/memories/" + id + "/history")
        .param("asOf", "2030-01-01T02:00:00+02:00"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.factGroup", is("fact:bob-beverage")))
      .andExpect(jsonPath("$.selectedRevision.memoryId", is(id)))
      .andExpect(jsonPath("$.selectedValidity.assertionId", notNullValue()))
      .andExpect(jsonPath("$.revisions[0].revisionId", notNullValue()))
      .andExpect(jsonPath("$.segments[0].validFrom", notNullValue()))
      .andExpect(jsonPath("$.temporal.asOf", is("2030-01-01T00:00:00Z")))
      .andExpect(jsonPath("$.temporal.knownAsOf", notNullValue()))
      .andExpect(jsonPath("$.temporal.snapshotSequence", notNullValue()));
  }

  @Test
  void factHistoryRejectsFutureKnowledgeAndMissingMemory() throws Exception {
    String id = storeMemory();
    mvc.perform(get("/v1/profiles/alice/memories/" + id + "/history")
        .param("knownAsOf", "2999-01-01T00:00:00Z"))
      .andExpect(status().isBadRequest());
    mvc.perform(get("/v1/profiles/alice/memories/missing/history"))
      .andExpect(status().isNotFound());
  }

  // ---- forget 204 and 404 ---------------------------------------------------------------

  @Test
  void forgetExistingMemoryReturns204() throws Exception {
    String id = storeMemory();
    mvc.perform(delete("/v1/profiles/alice/memories/" + id))
      .andExpect(status().isNoContent());
  }

  @Test
  void forgetMissingMemoryReturns404WithErrorShape() throws Exception {
    storeMemory(); // ensure profile exists so forget reaches the not-found branch
    mvc.perform(delete("/v1/profiles/alice/memories/no-such-id"))
      .andExpect(status().isNotFound())
      .andExpect(jsonPath("$.error", is("not_found")))
      .andExpect(jsonPath("$.message", notNullValue()));
  }

  // ---- error body contract: bad_request -------------------------------------------------

  @Test
  void blankRecallQueryReturnsBadRequestShape() throws Exception {
    mvc.perform(post("/v1/profiles/alice/recall")
        .contentType("application/json")
        .content("{\"query\":\"\"}"))
      .andExpect(status().isBadRequest())
      .andExpect(jsonPath("$.error", is("bad_request")))
      .andExpect(jsonPath("$.message", notNullValue()));
  }

  @Test
  void invalidMemoryTypeReturnsBadRequestShape() throws Exception {
    mvc.perform(post("/v1/profiles/alice/memories")
        .contentType("application/json")
        .content("{\"type\":\"bogus\",\"content\":\"x\"}"))
      .andExpect(status().isBadRequest())
      .andExpect(jsonPath("$.error", is("bad_request")))
      .andExpect(jsonPath("$.message", notNullValue()));
  }

  // ---- error body contract: not_found ---------------------------------------------------

  @Test
  void statsOnUnknownProfileReturnsNotFoundShape() throws Exception {
    // Recall tolerates an unknown profile (returns empty); stats still 404s, so use it to exercise
    // the not_found error-body contract.
    mvc.perform(get("/v1/profiles/ghost/stats"))
      .andExpect(status().isNotFound())
      .andExpect(jsonPath("$.error", is("not_found")))
      .andExpect(jsonPath("$.message", notNullValue()));
  }

  // ---- error body contract: model_unavailable -------------------------------------------

  @Test
  void modelUnavailableReturnsServiceUnavailableShape() throws Exception {
    storeMemory();
    stubModel().setUnavailable(true);
    try {
      mvc.perform(post("/v1/profiles/alice/recall")
          .contentType("application/json")
          .content("{\"query\":\"tea\"}"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.error", is("model_unavailable")))
        .andExpect(jsonPath("$.message", notNullValue()));
    } finally {
      stubModel().setUnavailable(false);
    }
  }

  // ---- helper ---------------------------------------------------------------------------

  /** Store one fact memory for profile "alice" and return its id. */
  private String storeMemory() throws Exception {
    String response = mvc.perform(post("/v1/profiles/alice/memories")
        .contentType("application/json")
        .content("{\"type\":\"fact\",\"content\":\"Bob likes tea\",\"sessionId\":\"s1\",\"topicKey\":\"bob-beverage\"}"))
      .andExpect(status().isCreated())
      .andReturn().getResponse().getContentAsString();
    int idx = response.indexOf("\"id\":\"") + 6;
    return response.substring(idx, response.indexOf('"', idx));
  }

  @TestConfiguration
  static class Wiring {
    private final StubMemoryStore store = new StubMemoryStore();
    private final StubModelGateway model = new StubModelGateway();

    @Bean("apiContractMemoryStore")
    MemoryStore memoryStore() {
      return store;
    }

    @Bean("apiContractModelGateway")
    @org.springframework.context.annotation.Primary
    ModelGateway modelGateway() {
      return model;
    }

    @Bean("apiContractCodeIndexStore")
    CodeIndexStore codeIndexStore() {
      return new NoOpCodeIndexStore();
    }

    @Bean("apiContractTraceProperties")
    TraceProperties traceProperties() {
      return TraceProperties.defaults();
    }

    @Bean("apiContractPieriaProperties")
    PieriaProperties pieriaProperties() {
      return new PieriaProperties(null, null, null, null,
        new PieriaProperties.Ingestion(10000, 2, 4, VerifyMode.ALWAYS, 1, 0, 0, false, 3, 3, 32, 5, false, 5000, true, 0.70),
        new PieriaProperties.Retrieval(false, 60, 3.0, 1.0, 1.0, 1.0, 0.5, 1.0, 2, 20, 8, 10, 3000, 0.0, 0.0, 2, 20, 8, "heuristic", RecallMode.SYNTHESIZED, 0.60, 0.78,
          false, 0.4, true, 30, 400, 4000L),
      null);
    }

    @Bean("apiContractEffectiveConfigResolver")
    EffectiveConfigResolver effectiveConfigResolver() {
      return EffectiveConfigResolver.withoutOverrides(pieriaProperties());
    }

    @Bean("apiContractTaskRegistry")
    TaskRegistry taskRegistry() {
      return new TaskRegistry();
    }

    @Bean("apiContractObjectMapper")
    com.fasterxml.jackson.databind.ObjectMapper objectMapper() {
      return new com.fasterxml.jackson.databind.ObjectMapper()
        .findAndRegisterModules();
    }

    @Bean("apiContractProfileService")
    ProfileService profileService(MemoryStore store) {
      return new ProfileService(store);
    }

    @Bean("apiContractProfileStatsService")
    ProfileStatsService profileStatsService(MemoryStore store, PieriaProperties properties) {
      return new ProfileStatsService(store, properties);
    }
  }
}
