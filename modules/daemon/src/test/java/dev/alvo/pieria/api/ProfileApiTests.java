package dev.alvo.pieria.api;

import dev.alvo.pieria.api.controller.ProfileController;
import dev.alvo.pieria.api.controller.ProfilesController;
import dev.alvo.pieria.api.error.GlobalExceptionHandler;
import dev.alvo.pieria.config.PieriaProperties;
import dev.alvo.pieria.ingestion.Chunker;
import dev.alvo.pieria.ingestion.IngestionService;
import dev.alvo.pieria.ingestion.TranscriptNormalizer;
import dev.alvo.pieria.model.ModelGateway;
import dev.alvo.pieria.retrieval.RetrievalService;
import dev.alvo.pieria.storage.MemoryStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {ProfileController.class, ProfilesController.class, GlobalExceptionHandler.class})
@Import({ProfileApiTests.Wiring.class, IngestionService.class, RetrievalService.class,
  dev.alvo.pieria.retrieval.DeterministicQueryAnalyzer.class,
  TranscriptNormalizer.class, Chunker.class})
class ProfileApiTests {

  @Autowired
  MockMvc mvc;
  @Autowired
  ModelGateway modelGateway;

  StubModelGateway stubModel() {
    return (StubModelGateway) modelGateway;
  }

  @Test
  void rememberThenListThenRecallThenForgetThenExport() throws Exception {
    // remember -> 201
    String id = remember();

    // list -> contains it
    mvc.perform(get("/v1/profiles/alice/memories"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.memories[0].content", is("Bob likes tea")))
      .andExpect(jsonPath("$.memories[0].type", is("fact")));

    // list filtered by type
    mvc.perform(get("/v1/profiles/alice/memories").param("type", "instruction"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.memories", org.hamcrest.Matchers.hasSize(0)));

    // recall -> synthesized answer + memories
    mvc.perform(post("/v1/profiles/alice/recall")
        .contentType("application/json")
        .content("{\"query\":\"tea\"}"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.answer", containsString("Bob likes tea")))
      .andExpect(jsonPath("$.memories[0].content", is("Bob likes tea")));

    // export -> ndjson
    mvc.perform(get("/v1/profiles/alice/export"))
      .andExpect(status().isOk())
      .andExpect(content().contentTypeCompatibleWith("application/x-ndjson"))
      .andExpect(content().string(containsString("Bob likes tea")));

    // forget -> 204
    mvc.perform(delete("/v1/profiles/alice/memories/" + id))
      .andExpect(status().isNoContent());

    // now list is empty (superseded)
    mvc.perform(get("/v1/profiles/alice/memories"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.memories", org.hamcrest.Matchers.hasSize(0)));
  }

  @Test
  void ingestExtractsMemories() throws Exception {
    mvc.perform(post("/v1/profiles/bob/ingest")
        .contentType("application/json")
        .content("{\"sessionId\":\"s1\",\"messages\":[{\"role\":\"user\",\"content\":\"I love coffee\"},{\"role\":\"assistant\",\"content\":\"noted\"}]}"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.count", is(1)))
      .andExpect(jsonPath("$.memories[0].content", containsString("I love coffee")));
  }

  @Test
  void blankQueryIsBadRequest() throws Exception {
    mvc.perform(post("/v1/profiles/alice/recall")
        .contentType("application/json")
        .content("{\"query\":\"\"}"))
      .andExpect(status().isBadRequest())
      .andExpect(jsonPath("$.error", is("bad_request")));
  }

  @Test
  void invalidMemoryTypeIsBadRequest() throws Exception {
    mvc.perform(post("/v1/profiles/alice/memories")
        .contentType("application/json")
        .content("{\"type\":\"nonsense\",\"content\":\"x\"}"))
      .andExpect(status().isBadRequest())
      .andExpect(jsonPath("$.error", is("bad_request")));
  }

  @Test
  void forgetMissingMemoryIsNotFound() throws Exception {
    // ensure profile exists
    remember();
    mvc.perform(delete("/v1/profiles/alice/memories/does-not-exist"))
      .andExpect(status().isNotFound())
      .andExpect(jsonPath("$.error", is("not_found")));
  }

  @Test
  void recallOnMissingProfileIsNotFound() throws Exception {
    mvc.perform(post("/v1/profiles/ghost/recall")
        .contentType("application/json")
        .content("{\"query\":\"hi\"}"))
      .andExpect(status().isNotFound())
      .andExpect(jsonPath("$.error", is("not_found")));
  }

  @Test
  void modelUnavailableDuringRecallIsServiceUnavailable() throws Exception {
    remember();
    stubModel().setUnavailable(true);
    try {
      mvc.perform(post("/v1/profiles/alice/recall")
          .contentType("application/json")
          .content("{\"query\":\"tea\"}"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.error", is("model_unavailable")));
    } finally {
      stubModel().setUnavailable(false);
    }
  }

  @Test
  void recallWithDebugReturnsProvenanceAndChannels() throws Exception {
    remember();
    mvc.perform(post("/v1/profiles/alice/recall")
        .contentType("application/json")
        .content("{\"query\":\"tea\",\"debug\":true}"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.memories[0].content", is("Bob likes tea")))
      .andExpect(jsonPath("$.debug.candidates[0].source", containsString("fts_memory")))
      .andExpect(jsonPath("$.debug.candidates[0].id", is(org.hamcrest.Matchers.notNullValue())))
      .andExpect(jsonPath("$.debug.channels", org.hamcrest.Matchers.hasSize(5)));
  }

  @Test
  void recallWithoutDebugOmitsDebugBlock() throws Exception {
    remember();
    mvc.perform(post("/v1/profiles/alice/recall")
        .contentType("application/json")
        .content("{\"query\":\"tea\"}"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.debug").doesNotExist());
  }

  @Test
  void recallWithNoMatchesReportsInsufficientEvidence() throws Exception {
    remember();
    mvc.perform(post("/v1/profiles/alice/recall")
        .contentType("application/json")
        .content("{\"query\":\"quantumchromodynamics\"}"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.memories", org.hamcrest.Matchers.hasSize(0)))
      .andExpect(jsonPath("$.answer", is("No relevant memories.")));
  }

  @Test
  void listProfilesReportsActiveCounts() throws Exception {
    // Dedicated profile so the count is stable regardless of sibling-test ordering.
    storeFact("listtest", "only fact in listtest");

    mvc.perform(get("/v1/profiles"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.profiles[?(@.name=='listtest')].memoryCount",
        org.hamcrest.Matchers.contains(1)));
  }

  @Test
  void statsReportsCountsForProfile() throws Exception {
    // Dedicated profile (never forgotten elsewhere) keeps the counts deterministic.
    storeFact("statstest", "only fact in statstest");

    mvc.perform(get("/v1/profiles/statstest/stats"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.name", is("statstest")))
      .andExpect(jsonPath("$.totalActive", is(1)))
      .andExpect(jsonPath("$.byType.fact", is(1)))
      .andExpect(jsonPath("$.byType.event", is(0)))
      .andExpect(jsonPath("$.sessions", is(1)))
      .andExpect(jsonPath("$.firstMemoryAt", is(org.hamcrest.Matchers.notNullValue())));
  }

  private void storeFact(String profile, String content) throws Exception {
    mvc.perform(post("/v1/profiles/" + profile + "/memories")
        .contentType("application/json")
        .content("{\"type\":\"fact\",\"content\":\"" + content + "\",\"sessionId\":\"s1\"}"))
      .andExpect(status().isCreated());
  }

  @Test
  void statsOnMissingProfileIsNotFound() throws Exception {
    mvc.perform(get("/v1/profiles/ghost/stats"))
      .andExpect(status().isNotFound())
      .andExpect(jsonPath("$.error", is("not_found")));
  }

  private String remember() throws Exception {
    String response = mvc.perform(post("/v1/profiles/alice/memories")
        .contentType("application/json")
        .content("{\"type\":\"fact\",\"content\":\"Bob likes tea\",\"sessionId\":\"s1\"}"))
      .andExpect(status().isCreated())
      .andExpect(jsonPath("$.type", is("fact")))
      .andExpect(jsonPath("$.content", is("Bob likes tea")))
      .andReturn().getResponse().getContentAsString();
    int idx = response.indexOf("\"id\":\"") + 6;
    return response.substring(idx, response.indexOf('"', idx));
  }

  @TestConfiguration
  static class Wiring {
    private final StubMemoryStore store = new StubMemoryStore();
    private final StubModelGateway model = new StubModelGateway();

    @Bean("profileApiMemoryStore")
    MemoryStore memoryStore() {
      return store;
    }

    @Bean("profileApiModelGateway")
    @org.springframework.context.annotation.Primary
    ModelGateway modelGateway() {
      return model;
    }

    @Bean("profileApiPieriaProperties")
    PieriaProperties pieriaProperties() {
      return new PieriaProperties(null, null, null, null,
        new PieriaProperties.Ingestion(10000, 2, 4, 9, 32, 5, false, 5000),
        new PieriaProperties.Retrieval(false, 60, 3.0, 1.0, 1.0, 1.0, 0.5, 10, 3000));
    }

    @Bean("profileApiObjectMapper")
    com.fasterxml.jackson.databind.ObjectMapper objectMapper() {
      return new com.fasterxml.jackson.databind.ObjectMapper()
        .findAndRegisterModules();
    }
  }
}
