package dev.alvo.pieria.audit;

import dev.alvo.pieria.api.AuditHeaders;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class AuditApiTests {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;

  @Test
  void capturesCallerRequestResponseAndSupportsDetailSearch() throws Exception {
    String profile = "audit-" + UUID.randomUUID();
    mvc.perform(post("/v1/profiles/" + profile + "/memories")
        .header(AuditHeaders.CLIENT, "gateway")
        .header(AuditHeaders.HARNESS, "codex")
        .header(AuditHeaders.CHANNEL, "mcp")
        .header(AuditHeaders.REQUEST_ID, "request-audit-test")
        .contentType("application/json")
        .content("{\"type\":\"fact\",\"content\":\"Auditable tea preference\",\"sessionId\":\"session-a\"}"))
      .andExpect(status().isCreated())
      .andExpect(header().string(AuditHeaders.REQUEST_ID, "request-audit-test"));

    String list = mvc.perform(get("/v1/profiles/" + profile + "/audit")
        .param("q", "Auditable tea"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.events[0].operation", is("memory.remember")))
      .andExpect(jsonPath("$.events[0].client", is("gateway")))
      .andExpect(jsonPath("$.events[0].harness", is("codex")))
      .andExpect(jsonPath("$.events[0].sessionId", is("session-a")))
      .andExpect(jsonPath("$.events[0].responsePreview", containsString("Auditable tea preference")))
      .andReturn().getResponse().getContentAsString();

    JsonNode event = json.readTree(list).get("events").get(0);
    mvc.perform(get("/v1/profiles/" + profile + "/audit/" + event.get("id").asText()))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.requestSha256", notNullValue()))
      .andExpect(jsonPath("$.responseSha256", notNullValue()))
      .andExpect(jsonPath("$.requestBody", containsString("Auditable tea preference")))
      .andExpect(jsonPath("$.responseBody", containsString("Auditable tea preference")));
  }

  @Test
  void capturesValidationFailureAgainstUnknownProfile() throws Exception {
    String profile = "audit-failure-" + UUID.randomUUID();
    mvc.perform(post("/v1/profiles/" + profile + "/memories")
        .contentType("application/json").content("{\"type\":\"bogus\",\"content\":\"x\"}"))
      .andExpect(status().isBadRequest());

    mvc.perform(get("/v1/profiles/" + profile + "/audit").param("outcome", "failure"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.events[0].httpStatus", is(400)))
      .andExpect(jsonPath("$.events[0].errorKind", is("bad_request")));
  }

  @Test
  void successfulProfileDeleteRemovesHistoryAndDoesNotRecreateAnEvent() throws Exception {
    String profile = "audit-delete-" + UUID.randomUUID();
    mvc.perform(put("/v1/profiles/" + profile)).andExpect(status().isCreated());
    mvc.perform(delete("/v1/profiles/" + profile)).andExpect(status().isNoContent());
    mvc.perform(get("/v1/profiles/" + profile + "/audit"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.events.length()", is(0)));
  }
}
