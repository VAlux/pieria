package dev.alvo.pieria.api;

import dev.alvo.pieria.api.controller.SourceController;
import dev.alvo.pieria.api.error.GlobalExceptionHandler;
import dev.alvo.pieria.domain.error.ConflictException;
import dev.alvo.pieria.domain.error.NotFoundException;
import dev.alvo.pieria.harvest.HarvestRun;
import dev.alvo.pieria.harvest.HarvestSource;
import dev.alvo.pieria.harvest.HarvestSourceService;
import dev.alvo.pieria.harvest.RunStatus;
import dev.alvo.pieria.harvest.SourceCheckResult;
import dev.alvo.pieria.harvest.SourceDraft;
import dev.alvo.pieria.harvest.SourceMode;
import dev.alvo.pieria.harvest.SourceStatus;
import dev.alvo.pieria.harvest.SourceUpdate;
import dev.alvo.pieria.harvester.protocol.ErrorClass;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {SourceController.class, GlobalExceptionHandler.class})
class SourceApiTests {

  private static final Instant T0 = Instant.parse("2026-03-01T00:00:00Z");
  private static final String BASE = "/v1/profiles/p/sources";
  private static final String SECRET = "hunter2-value";
  private static final HarvestSource SOURCE = new HarvestSource("s", "pid", "jira", "{\"baseUrl\":\"https://x\"}", SourceMode.FULL, 3600,
    5, 9L, SourceStatus.PAUSED, null, T0, 1, 3L, T0);

  @Autowired
  MockMvc mvc;
  @MockitoBean
  HarvestSourceService service;

  @BeforeEach
  void setUp() {
    when(service.get("p", "s")).thenReturn(SOURCE);
    when(service.secretNames("s")).thenReturn(Set.of("token"));
    when(service.runs(eq("p"), eq("s"), any(Integer.class))).thenReturn(List.of());
  }

  private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder json(
    org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder, String body) {
    return builder.contentType(MediaType.APPLICATION_JSON).content(body);
  }

  private static final String CREATE = "{\"id\":\"s\",\"harvester\":\"jira\",\"config\":{\"baseUrl\":\"https://x\"},"
    + "\"secrets\":{\"token\":\"" + SECRET + "\"},\"mode\":\"raw\",\"intervalSeconds\":3600,\"maxItemsPerRun\":5,\"maxCharsPerRun\":9,\"start\":true}";

  @Test
  void createReturnsTheSourceWithoutSecretValues() throws Exception {
    when(service.create(eq("p"), any())).thenReturn(SOURCE);

    mvc.perform(json(post(BASE), CREATE))
      .andExpect(status().isCreated())
      .andExpect(jsonPath("$.id", is("s")))
      .andExpect(jsonPath("$.harvester", is("jira")))
      .andExpect(jsonPath("$.config.baseUrl", is("https://x")))
      .andExpect(jsonPath("$.secretNames[0]", is("token")))
      .andExpect(jsonPath("$.status", is("paused")))
      .andExpect(jsonPath("$.mode", is("full")))
      .andExpect(jsonPath("$.sweepBlockedCount", is(3)))
      .andExpect(content().string(not(containsString(SECRET))));

    ArgumentCaptor<SourceDraft> draft = ArgumentCaptor.forClass(SourceDraft.class);
    verify(service).create(eq("p"), draft.capture());
    assertThat(draft.getValue().mode()).isEqualTo(SourceMode.RAW);
    assertThat(draft.getValue().secrets()).containsEntry("token", SECRET);
    assertThat(draft.getValue().start()).isTrue();
    assertThat(draft.getValue().intervalSeconds()).isEqualTo(3600);
  }

  @Test
  void createDefaultsTheIntervalToSixHours() throws Exception {
    when(service.create(eq("p"), any())).thenReturn(SOURCE);

    mvc.perform(json(post(BASE), "{\"id\":\"s\",\"harvester\":\"jira\"}")).andExpect(status().isCreated());

    ArgumentCaptor<SourceDraft> draft = ArgumentCaptor.forClass(SourceDraft.class);
    verify(service).create(eq("p"), draft.capture());
    assertThat(draft.getValue().intervalSeconds()).isEqualTo(21_600);
  }

  @Test
  void createValidatesTheId() throws Exception {
    mvc.perform(json(post(BASE), "{\"id\":\"Bad Id\",\"harvester\":\"jira\"}")).andExpect(status().isBadRequest());
  }

  @Test
  void createRejectsAnUnknownMode() throws Exception {
    mvc.perform(json(post(BASE), "{\"id\":\"s\",\"harvester\":\"jira\",\"mode\":\"odd\"}")).andExpect(status().isBadRequest());
  }

  @Test
  void createReportsARemoteProviderWarning() throws Exception {
    when(service.create(eq("p"), any())).thenReturn(SOURCE);
    when(service.usesRemoteModelProvider()).thenReturn(true);

    mvc.perform(json(post(BASE), "{\"id\":\"s\",\"harvester\":\"jira\"}"))
      .andExpect(jsonPath("$.warnings[0]", is("Text from this source will be sent to your configured model provider.")));
  }

  @Test
  void listAndGet() throws Exception {
    when(service.list("p")).thenReturn(List.of(SOURCE));

    mvc.perform(get(BASE)).andExpect(status().isOk()).andExpect(jsonPath("$.sources[0].id", is("s")));
    mvc.perform(get(BASE + "/s")).andExpect(status().isOk()).andExpect(jsonPath("$.id", is("s")));
  }

  @Test
  void getIncludesTheLastRun() throws Exception {
    when(service.runs("p", "s", 1)).thenReturn(List.of(run()));

    mvc.perform(get(BASE + "/s")).andExpect(jsonPath("$.lastRun.status", is("failed")));
  }

  private static HarvestRun run() {
    return new HarvestRun("r", "s", false, RunStatus.FAILED, T0, T0, 1, 1, 0, 2, 10, "auth", "denied", "tail");
  }

  @Test
  void patchUpdates() throws Exception {
    when(service.update(eq("p"), eq("s"), any())).thenReturn(SOURCE);

    mvc.perform(json(patch(BASE + "/s"), "{\"intervalSeconds\":120,\"resetCursor\":true}")).andExpect(status().isOk());

    ArgumentCaptor<SourceUpdate> update = ArgumentCaptor.forClass(SourceUpdate.class);
    verify(service).update(eq("p"), eq("s"), update.capture());
    assertThat(update.getValue().intervalSeconds()).isEqualTo(120L);
    assertThat(update.getValue().resetCursor()).isTrue();
    assertThat(update.getValue().config()).isNull();
  }

  @Test
  void deletePassesForgetMemories() throws Exception {
    mvc.perform(delete(BASE + "/s").param("forgetMemories", "true")).andExpect(status().isNoContent());

    verify(service).remove("p", "s", true);
  }

  @Test
  void deleteKeepsMemoriesByDefault() throws Exception {
    mvc.perform(delete(BASE + "/s")).andExpect(status().isNoContent());

    verify(service).remove("p", "s", false);
  }

  @Test
  void checkReportsFailure() throws Exception {
    when(service.check("p", "s")).thenReturn(new SourceCheckResult(false, ErrorClass.AUTH, "denied"));

    mvc.perform(post(BASE + "/s/check"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.ok", is(false)))
      .andExpect(jsonPath("$.errorClass", is("auth")))
      .andExpect(jsonPath("$.message", is("denied")));
  }

  @Test
  void runReturnsATask() throws Exception {
    UUID task = UUID.randomUUID();
    when(service.run("p", "s", true)).thenReturn(task);

    mvc.perform(json(post(BASE + "/s/run"), "{\"dryRun\":true}"))
      .andExpect(status().isAccepted())
      .andExpect(jsonPath("$.taskId", is(task.toString())));
  }

  @Test
  void runWithoutABodyIsARealRun() throws Exception {
    when(service.run("p", "s", false)).thenReturn(UUID.randomUUID());

    mvc.perform(post(BASE + "/s/run")).andExpect(status().isAccepted());
  }

  @Test
  void runningABusySourceIs409() throws Exception {
    when(service.run("p", "s", false)).thenThrow(new ConflictException("busy"));

    mvc.perform(post(BASE + "/s/run")).andExpect(status().isConflict());
  }

  @Test
  void runsLists() throws Exception {
    when(service.runs("p", "s", 3)).thenReturn(List.of(run()));

    mvc.perform(get(BASE + "/s/runs").param("limit", "3"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.runs[0].id", is("r")))
      .andExpect(jsonPath("$.runs[0].errorMessage", is("denied")));
  }

  @Test
  void aRequestNamingAnotherHostNeverReachesTheController() throws Exception {
    mvc.perform(get(BASE + "/s").header("Host", "evil.example")).andExpect(status().isForbidden());

    org.mockito.Mockito.verify(service, org.mockito.Mockito.never()).get(any(), any());
  }

  @Test
  void pauseResumeAndAcceptSweep() throws Exception {
    mvc.perform(post(BASE + "/s/pause")).andExpect(status().isOk());
    mvc.perform(post(BASE + "/s/resume")).andExpect(status().isOk());
    mvc.perform(post(BASE + "/s/accept-sweep")).andExpect(status().isOk());

    verify(service).pause("p", "s");
    verify(service).resume("p", "s");
    verify(service).acceptNextSweep("p", "s");
  }

  @Test
  void secretsReturn204() throws Exception {
    mvc.perform(json(put(BASE + "/s/secrets"), "{\"secrets\":{\"token\":\"" + SECRET + "\"}}")).andExpect(status().isNoContent());

    verify(service).setSecrets("p", "s", Map.of("token", SECRET));
  }

  @Test
  void emptySecretsAre400() throws Exception {
    mvc.perform(json(put(BASE + "/s/secrets"), "{\"secrets\":{}}")).andExpect(status().isBadRequest());
  }

  @Test
  void unknownSourceIs404() throws Exception {
    when(service.get("p", "absent")).thenThrow(new NotFoundException("source not found"));

    mvc.perform(get(BASE + "/absent")).andExpect(status().isNotFound());
  }
}
