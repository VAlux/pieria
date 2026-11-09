package dev.alvo.pieria.cli.command.harvest;

import dev.alvo.pieria.api.response.HarvesterResponse;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SourceOptionsTests {

  private static HarvesterResponse.Field field(String key, String kind) {
    return new HarvesterResponse.Field(key, kind, key, null, false, List.of(), null);
  }

  private static final HarvesterResponse HARVESTER = new HarvesterResponse("jira", "Jira", "1", List.of(), List.of(),
    List.of(field("url", "string"), field("count", "int"), field("on", "bool"), field("tags", "list"), field("mode", "enum"), field("token", "secret")));

  @Test
  void parsesIntervals() {
    assertThat(SourceOptions.parseInterval("90")).isEqualTo(90);
    assertThat(SourceOptions.parseInterval("30m")).isEqualTo(1_800);
    assertThat(SourceOptions.parseInterval("6h")).isEqualTo(21_600);
    assertThat(SourceOptions.parseInterval("1d")).isEqualTo(86_400);
  }

  @Test
  void rejectsABadInterval() {
    assertThatThrownBy(() -> SourceOptions.parseInterval("soon")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void convertsConfigByKind() {
    ObjectNode config = SourceOptions.parseConfig(List.of("url=https://x?a=b", "count=3", "on=true", "tags=a, b", "mode=full"), HARVESTER);

    assertThat(config.get("url").asString()).isEqualTo("https://x?a=b");
    assertThat(config.get("count").isIntegralNumber()).isTrue();
    assertThat(config.get("on").isBoolean()).isTrue();
    assertThat(config.get("tags").size()).isEqualTo(2);
    assertThat(config.get("tags").get(1).asString()).isEqualTo("b");
    assertThat(config.get("mode").asString()).isEqualTo("full");
  }

  @Test
  void rejectsAnUnknownKey() {
    assertThatThrownBy(() -> SourceOptions.parseConfig(List.of("nope=1"), HARVESTER)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsASecretPassedAsASetting() {
    assertThatThrownBy(() -> SourceOptions.parseConfig(List.of("token=abc"), HARVESTER)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsAMalformedSetting() {
    assertThatThrownBy(() -> SourceOptions.parseConfig(List.of("noequals"), HARVESTER)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsABadNumberOrBoolean() {
    assertThatThrownBy(() -> SourceOptions.parseConfig(List.of("count=lots"), HARVESTER)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> SourceOptions.parseConfig(List.of("on=maybe"), HARVESTER)).isInstanceOf(IllegalArgumentException.class);
  }
}
