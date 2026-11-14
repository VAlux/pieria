package dev.alvo.pieria.harvesters.atlassian;

import dev.alvo.pieria.harvester.HarvesterException;
import dev.alvo.pieria.harvester.protocol.ErrorClass;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AtlassianSiteTests {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Map<String, String> TOKEN = Map.of("token", "s3cret");

  private static ObjectNode config(String baseUrl) {
    return MAPPER.createObjectNode().put("baseUrl", baseUrl);
  }

  private static String basic(String user, String password) {
    return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
  }

  private static void assertConfigError(ObjectNode config, Map<String, String> secrets, String message) {
    assertThatThrownBy(() -> AtlassianSite.parse(config, secrets, "")).isInstanceOfSatisfying(HarvesterException.class, e -> {
      assertThat(e.errorClass()).isEqualTo(ErrorClass.CONFIG);
      assertThat(e.getMessage()).contains(message);
    });
  }

  @Test
  void autoDetectsCloudFromTheHost() throws HarvesterException {
    AtlassianSite site = AtlassianSite.parse(config("https://acme.atlassian.net").put("email", "me@acme.com"), TOKEN, "");

    assertThat(site.deployment()).isEqualTo(Deployment.CLOUD);
    assertThat(site.baseUrl()).isEqualTo("https://acme.atlassian.net");
    assertThat(site.authorization()).isEqualTo(basic("me@acme.com", "s3cret"));
  }

  @Test
  void otherHostsAreDataCenter() throws HarvesterException {
    AtlassianSite site = AtlassianSite.parse(config("https://jira.example.com/jira"), TOKEN, "");

    assertThat(site.deployment()).isEqualTo(Deployment.DATA_CENTER);
    assertThat(site.baseUrl()).isEqualTo("https://jira.example.com/jira");
  }

  @Test
  void explicitDeploymentWins() throws HarvesterException {
    AtlassianSite site = AtlassianSite.parse(config("https://jira.example.com").put("deployment", "cloud").put("email", "a@b.c"), TOKEN, "");

    assertThat(site.deployment()).isEqualTo(Deployment.CLOUD);
  }

  @Test
  void cloudConfluenceGetsTheWikiPath() throws HarvesterException {
    ObjectNode config = config("https://acme.atlassian.net/").put("email", "me@acme.com");

    assertThat(AtlassianSite.parse(config, TOKEN, "/wiki").baseUrl()).isEqualTo("https://acme.atlassian.net/wiki");
    assertThat(AtlassianSite.parse(config.put("baseUrl", "https://acme.atlassian.net/wiki"), TOKEN, "/wiki").baseUrl())
      .isEqualTo("https://acme.atlassian.net/wiki");
  }

  @Test
  void cloudNeedsAnEmail() {
    assertConfigError(config("https://acme.atlassian.net"), TOKEN, "email is required for Atlassian Cloud");
  }

  @Test
  void dataCenterWithoutEmailUsesBearer() throws HarvesterException {
    assertThat(AtlassianSite.parse(config("https://jira.example.com"), TOKEN, "").authorization()).isEqualTo("Bearer s3cret");
  }

  @Test
  void dataCenterWithUsernameUsesBasic() throws HarvesterException {
    AtlassianSite site = AtlassianSite.parse(config("https://jira.example.com").put("email", "jdoe"), TOKEN, "");

    assertThat(site.authorization()).isEqualTo(basic("jdoe", "s3cret"));
  }

  @Test
  void rejectsQueryInBaseUrl() {
    assertConfigError(config("https://jira.example.com/?a=b"), TOKEN, "baseUrl");
    assertConfigError(config("ftp://jira.example.com"), TOKEN, "baseUrl");
    assertConfigError(config("not a url"), TOKEN, "baseUrl");
    assertConfigError(config("https://user:pw@jira.example.com"), TOKEN, "baseUrl");
  }

  @Test
  void stripsTrailingSlash() throws HarvesterException {
    assertThat(AtlassianSite.parse(config("https://jira.example.com/jira//"), TOKEN, "").baseUrl()).isEqualTo("https://jira.example.com/jira");
  }

  @Test
  void toStringHidesTheCredentials() throws HarvesterException {
    AtlassianSite site = AtlassianSite.parse(config("https://jira.example.com"), TOKEN, "");

    assertThat(site.toString()).doesNotContain("s3cret").contains("jira.example.com");
  }

  @Test
  void missingTokenIsRejected() {
    assertConfigError(config("https://jira.example.com"), Map.of(), "the token secret is required");
  }

  @Test
  void unknownDeploymentIsRejected() {
    assertConfigError(config("https://jira.example.com").put("deployment", "server"), TOKEN, "deployment must be one of");
  }
}
