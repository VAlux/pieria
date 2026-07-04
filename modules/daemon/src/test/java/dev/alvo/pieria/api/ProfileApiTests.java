package dev.alvo.pieria.api;

import dev.alvo.pieria.api.controller.ProfileController;
import dev.alvo.pieria.api.controller.ProfilesController;
import dev.alvo.pieria.api.error.GlobalExceptionHandler;
import dev.alvo.pieria.config.PieriaProperties;
import dev.alvo.pieria.ingestion.Chunker;
import dev.alvo.pieria.ingestion.IngestionService;
import dev.alvo.pieria.ingestion.TranscriptNormalizer;
import dev.alvo.pieria.domain.profile.Profile;
import dev.alvo.pieria.model.ModelGateway;
import dev.alvo.pieria.model.usage.InferenceTier;
import dev.alvo.pieria.model.usage.TierUsage;
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
  TranscriptNormalizer.class, Chunker.class,
  dev.alvo.pieria.ingestion.transcript.TranscriptParserRegistry.class,
  dev.alvo.pieria.ingestion.transcript.ClaudeCodeTranscriptParser.class,
  dev.alvo.pieria.ingestion.transcript.CodexTranscriptParser.class})
class ProfileApiTests {

  @Autowired
  MockMvc mvc;
  @Autowired
  ModelGateway modelGateway;
  @Autowired
  MemoryStore store;

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
  void listIncludesSupersededOnlyWhenRequested() throws Exception {
    // Use a dedicated profile + content so superseding it cannot pollute the shared "alice"
    // fixture that the recall tests rely on (the stub store is a singleton across test methods,
    // and content-addressed ids make re-inserts a no-op once a memory is superseded).
    String body = mvc.perform(post("/v1/profiles/supq/memories")
        .contentType("application/json")
        .content("{\"type\":\"fact\",\"content\":\"Superseded probe\",\"sessionId\":\"sup1\"}"))
      .andExpect(status().isCreated())
      .andReturn().getResponse().getContentAsString();
    int idx = body.indexOf("\"id\":\"") + 6;
    String id = body.substring(idx, body.indexOf('"', idx));

    // forget it -> now superseded
    mvc.perform(delete("/v1/profiles/supq/memories/" + id))
      .andExpect(status().isNoContent());

    // default list omits the superseded memory
    mvc.perform(get("/v1/profiles/supq/memories"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.memories", org.hamcrest.Matchers.hasSize(0)));

    // includeSuperseded=true surfaces it, flagged
    mvc.perform(get("/v1/profiles/supq/memories").param("includeSuperseded", "true"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.memories", org.hamcrest.Matchers.hasSize(1)))
      .andExpect(jsonPath("$.memories[0].content", is("Superseded probe")))
      .andExpect(jsonPath("$.memories[0].superseded", is(true)));
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
      .andExpect(jsonPath("$.debug.channels", org.hamcrest.Matchers.hasSize(6)));
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
      .andExpect(jsonPath("$.firstMemoryAt", is(org.hamcrest.Matchers.notNullValue())))
      // The impact block is always present; explicit POST /memories does not record usage,
      // so the counters are zero, but the display knobs fall back to their defaults.
      .andExpect(jsonPath("$.impact.recalls", is(0)))
      .andExpect(jsonPath("$.impact.tokensSavedEvidence", is(0)))
      .andExpect(jsonPath("$.impact.contextWindowTokens", is(200000)))
      // No inference spend recorded for this profile, so the spend block is omitted.
      .andExpect(jsonPath("$.spend").value(org.hamcrest.Matchers.nullValue()));
  }

  @Test
  void statsReportsInferenceSpendWithPerTierCost() throws Exception {
    Profile p = store.getOrCreateProfile("spendtest");
    store.recordInferenceUsage(p.id(), java.util.Map.of(
      InferenceTier.EXTRACTION, new TierUsage(10, 1_000_000, 200_000),
      InferenceTier.SYNTHESIS, new TierUsage(2, 500_000, 100_000)));

    mvc.perform(get("/v1/profiles/spendtest/stats"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.spend.costAvailable", is(true)))
      .andExpect(jsonPath("$.spend.totalPromptTokens", is(1500000)))
      .andExpect(jsonPath("$.spend.totalCompletionTokens", is(300000)))
      // extraction: 1.0*0.30 + 0.2*0.60 = 0.42; synthesis: 0.5*3.0 + 0.1*15.0 = 3.0; total 3.42.
      .andExpect(jsonPath("$.spend.totalCostUsd", org.hamcrest.Matchers.closeTo(3.42, 1e-6)))
      .andExpect(jsonPath("$.spend.tiers[?(@.tier=='extraction')].costUsd",
        org.hamcrest.Matchers.contains(org.hamcrest.Matchers.closeTo(0.42, 1e-6))))
      .andExpect(jsonPath("$.spend.tiers[?(@.tier=='synthesis')].calls",
        org.hamcrest.Matchers.contains(2)));
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

  @Test
  void recallTextPlainReturnsInjectableBlock() throws Exception {
    remember(); // stores "Bob likes tea" in profile alice

    mvc.perform(post("/v1/profiles/alice/recall")
        .contentType(MediaType.APPLICATION_JSON)
        .accept(MediaType.TEXT_PLAIN)
        .content("{\"query\":\"tea\"}"))
      .andExpect(status().isOk())
      .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
      .andExpect(content().string(containsString("[pieria] Relevant prior context")))
      .andExpect(content().string(containsString("Bob likes tea")));
  }

  @Test
  void recallTextPlainIsNoContentWhenNothingRecalled() throws Exception {
    remember(); // alice has memories, but none match the query below

    mvc.perform(post("/v1/profiles/alice/recall")
        .contentType(MediaType.APPLICATION_JSON)
        .accept(MediaType.TEXT_PLAIN)
        .content("{\"query\":\"zzzznomatchwhatsoever\"}"))
      .andExpect(status().isNoContent());
  }

  @Test
  void graphReturnsNodesAndLinksWithProvenance() throws Exception {
    Profile p = store.getOrCreateProfile("graphtest");
    store.insertMemory(p.id(), dev.alvo.pieria.domain.memory.Memory.of(
      dev.alvo.pieria.domain.memory.MemoryType.FACT, "alpha uses beta", "s1", null, null));
    String memId = dev.alvo.pieria.domain.ContentId.forMemory("s1",
      dev.alvo.pieria.domain.memory.MemoryType.FACT, "alpha uses beta");
    var alpha = store.upsertEntity(p.id(), dev.alvo.pieria.domain.graph.Entity.of("concept", "alpha", "{}"));
    var beta = store.upsertEntity(p.id(), dev.alvo.pieria.domain.graph.Entity.of("concept", "beta", "{}"));
    store.upsertEdge(p.id(), new dev.alvo.pieria.domain.graph.Edge(
      null, p.id(), alpha.id(), beta.id(), "uses", memId, null));

    mvc.perform(get("/v1/profiles/graphtest/graph"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.nodes", org.hamcrest.Matchers.hasSize(2)))
      .andExpect(jsonPath("$.links", org.hamcrest.Matchers.hasSize(1)))
      .andExpect(jsonPath("$.links[0].relation", is("uses")))
      .andExpect(jsonPath("$.links[0].source", is(alpha.id())))
      .andExpect(jsonPath("$.links[0].target", is(beta.id())))
      .andExpect(jsonPath("$.links[0].memory", is("alpha uses beta")));
  }

  @Test
  void graphOnMissingProfileIsNotFound() throws Exception {
    mvc.perform(get("/v1/profiles/ghost/graph"))
      .andExpect(status().isNotFound())
      .andExpect(jsonPath("$.error", is("not_found")));
  }

  @Test
  void graphViewRedirectsToStaticViewerWithProfile() throws Exception {
    mvc.perform(get("/v1/profiles/alice/graph/view"))
      .andExpect(status().isFound())
      .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
        .redirectedUrl("/index.html?view=graph&profile=alice"));
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

    @Bean("profileApiCodeIndexStore")
    CodeIndexStore codeIndexStore() {
      return new NoOpCodeIndexStore();
    }

    @Bean("profileApiPieriaProperties")
    PieriaProperties pieriaProperties() {
      return new PieriaProperties(null, null, null, null,
        new PieriaProperties.Ingestion(10000, 2, 4, 9, 32, 5, false, 5000),
        new PieriaProperties.Retrieval(false, 60, 3.0, 1.0, 1.0, 1.0, 0.5, 1.0, 2, 20, 8, 10, 3000, 0.0, 0.0, 2, 20, 8, "heuristic"),
        new PieriaProperties.Stats(0.0, 200000, java.util.Map.of(
          "extraction", new PieriaProperties.Stats.TierPrice(0.30, 0.60),
          "synthesis", new PieriaProperties.Stats.TierPrice(3.0, 15.0),
          "embedding", new PieriaProperties.Stats.TierPrice(0.02, 0.0))));
    }

    @Bean("profileApiEffectiveConfigResolver")
    dev.alvo.pieria.config.EffectiveConfigResolver effectiveConfigResolver() {
      return dev.alvo.pieria.config.EffectiveConfigResolver.withoutOverrides(pieriaProperties());
    }

    @Bean("profileApiTaskRegistry")
    dev.alvo.pieria.task.TaskRegistry taskRegistry() {
      return new dev.alvo.pieria.task.TaskRegistry();
    }

    @Bean("profileApiObjectMapper")
    com.fasterxml.jackson.databind.ObjectMapper objectMapper() {
      return new com.fasterxml.jackson.databind.ObjectMapper()
        .findAndRegisterModules();
    }
  }
}
