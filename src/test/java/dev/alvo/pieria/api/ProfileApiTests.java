package dev.alvo.pieria.api;

import dev.alvo.pieria.api.error.GlobalExceptionHandler;
import dev.alvo.pieria.ingestion.IngestionService;
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

@WebMvcTest(controllers = {ProfileController.class, GlobalExceptionHandler.class})
@Import({ProfileApiTests.Wiring.class, IngestionService.class, RetrievalService.class})
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
      .andExpect(jsonPath("$.memories[0].content", is("I love coffee")));
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

    @Bean
    MemoryStore memoryStore() {
      return store;
    }

    @Bean
    @org.springframework.context.annotation.Primary
    ModelGateway modelGateway() {
      return model;
    }

    @Bean
    com.fasterxml.jackson.databind.ObjectMapper objectMapper() {
      return new com.fasterxml.jackson.databind.ObjectMapper()
        .findAndRegisterModules();
    }
  }
}
