package dev.alvo.pieria.api;

import dev.alvo.pieria.api.controller.ProfileController;
import dev.alvo.pieria.api.error.GlobalExceptionHandler;
import dev.alvo.pieria.config.PieriaProperties;
import dev.alvo.pieria.ingestion.Chunker;
import dev.alvo.pieria.ingestion.IngestionService;
import dev.alvo.pieria.ingestion.TranscriptNormalizer;
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
  dev.alvo.pieria.retrieval.DeterministicQueryAnalyzer.class,
  TranscriptNormalizer.class, Chunker.class})
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
      .andExpect(jsonPath("$.memories").isArray());
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
  void recallOnUnknownProfileReturnsNotFoundShape() throws Exception {
    mvc.perform(post("/v1/profiles/ghost/recall")
        .contentType("application/json")
        .content("{\"query\":\"hi\"}"))
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

    @Bean("apiContractPieriaProperties")
    PieriaProperties pieriaProperties() {
      return new PieriaProperties(null, null, null, null,
        new PieriaProperties.Ingestion(10000, 2, 4, 9, 32, 5, false, 5000),
        new PieriaProperties.Retrieval(false, 60, 3.0, 1.0, 1.0, 1.0, 0.5, 1.0, 2, 20, 8, 10, 3000, 0.0, 0.0, 2, 20, 8, "heuristic"));
    }

    @Bean("apiContractObjectMapper")
    com.fasterxml.jackson.databind.ObjectMapper objectMapper() {
      return new com.fasterxml.jackson.databind.ObjectMapper()
        .findAndRegisterModules();
    }
  }
}
