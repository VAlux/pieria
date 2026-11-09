package dev.alvo.pieria.api;

import dev.alvo.pieria.api.controller.HarvesterController;
import dev.alvo.pieria.api.error.GlobalExceptionHandler;
import dev.alvo.pieria.domain.error.ConflictException;
import dev.alvo.pieria.harvest.HarvesterDiagnosis;
import dev.alvo.pieria.harvest.HarvesterInstaller;
import dev.alvo.pieria.harvest.HarvesterPackage;
import dev.alvo.pieria.harvest.HarvesterRegistry;
import dev.alvo.pieria.harvest.RegisteredHarvester;
import dev.alvo.pieria.harvester.manifest.HarvesterManifest;
import dev.alvo.pieria.harvester.manifest.ManifestParser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;
import java.util.List;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {HarvesterController.class, GlobalExceptionHandler.class})
class HarvesterApiTests {

  private static final HarvesterManifest MANIFEST = ManifestParser.parse("""
    id = "jira"
    name = "Jira"
    version = "1.2"
    protocol = 1
    command = ["./run"]
    capabilities = ["incremental"]
    network = ["*.atlassian.net"]

    [[config]]
    key = "baseUrl"
    kind = "string"
    label = "URL"
    required = true
    """);

  @Autowired
  MockMvc mvc;
  @MockitoBean
  HarvesterRegistry registry;
  @MockitoBean
  HarvesterInstaller installer;

  private static String body(String json) {
    return json;
  }

  @Test
  void listsHarvesters() throws Exception {
    when(registry.list()).thenReturn(List.of(new RegisteredHarvester(MANIFEST, Path.of("/h/jira"))));

    mvc.perform(get("/v1/harvesters"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.harvesters[0].id", is("jira")))
      .andExpect(jsonPath("$.harvesters[0].version", is("1.2")))
      .andExpect(jsonPath("$.harvesters[0].capabilities[0]", is("incremental")))
      .andExpect(jsonPath("$.harvesters[0].network[0]", is("*.atlassian.net")))
      .andExpect(jsonPath("$.harvesters[0].fields[0].key", is("baseUrl")))
      .andExpect(jsonPath("$.harvesters[0].fields[0].kind", is("string")))
      .andExpect(jsonPath("$.harvesters[0].fields[0].required", is(true)));
  }

  @Test
  void inspectReturnsHashes() throws Exception {
    when(installer.inspect(Path.of("/tmp/p"))).thenReturn(new HarvesterPackage(MANIFEST, "e", "m"));

    mvc.perform(post("/v1/harvesters/inspect").contentType(MediaType.APPLICATION_JSON).content(body("{\"path\":\"/tmp/p\"}")))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.entrypointSha256", is("e")))
      .andExpect(jsonPath("$.manifestSha256", is("m")))
      .andExpect(jsonPath("$.harvester.id", is("jira")))
      .andExpect(jsonPath("$.command[0]", is("./run")));
  }

  @Test
  void installReturns201() throws Exception {
    when(installer.install(Path.of("/tmp/p"), "e", "m", true)).thenReturn(new HarvesterPackage(MANIFEST, "e", "m"));

    mvc.perform(post("/v1/harvesters").contentType(MediaType.APPLICATION_JSON)
        .content(body("{\"path\":\"/tmp/p\",\"entrypointSha256\":\"e\",\"manifestSha256\":\"m\",\"replace\":true}")))
      .andExpect(status().isCreated())
      .andExpect(jsonPath("$.id", is("jira")));
  }

  @Test
  void installConflictIs409() throws Exception {
    when(installer.install(any(), any(), any(), eq(false))).thenThrow(new ConflictException("already installed"));

    mvc.perform(post("/v1/harvesters").contentType(MediaType.APPLICATION_JSON).content(body("{\"path\":\"/tmp/p\"}")))
      .andExpect(status().isConflict());
  }

  @Test
  void aBadPackageIs400() throws Exception {
    when(installer.install(any(), any(), any(), eq(false))).thenThrow(new IllegalArgumentException("no manifest"));

    mvc.perform(post("/v1/harvesters").contentType(MediaType.APPLICATION_JSON).content(body("{\"path\":\"/tmp/p\"}")))
      .andExpect(status().isBadRequest());
  }

  @Test
  void aBlankPathIs400() throws Exception {
    mvc.perform(post("/v1/harvesters").contentType(MediaType.APPLICATION_JSON).content(body("{\"path\":\"\"}")))
      .andExpect(status().isBadRequest());
  }

  @Test
  void removeReturns204() throws Exception {
    mvc.perform(delete("/v1/harvesters/jira")).andExpect(status().isNoContent());

    verify(installer).remove("jira");
  }

  @Test
  void removingOneInUseIs409() throws Exception {
    doThrow(new ConflictException("in use")).when(installer).remove("jira");

    mvc.perform(delete("/v1/harvesters/jira")).andExpect(status().isConflict());
  }

  @Test
  void doctorListsProblems() throws Exception {
    when(registry.diagnose()).thenReturn(List.of(new HarvesterDiagnosis("jira", false, "no pin")));

    mvc.perform(get("/v1/harvesters/doctor"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.entries[0].directory", is("jira")))
      .andExpect(jsonPath("$.entries[0].ok", is(false)))
      .andExpect(jsonPath("$.entries[0].detail", is("no pin")));
  }
}
