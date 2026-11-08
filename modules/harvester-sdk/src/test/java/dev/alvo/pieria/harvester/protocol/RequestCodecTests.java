package dev.alvo.pieria.harvester.protocol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestCodecTests {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static HarvestRequest request() {
    ObjectNode config = MAPPER.createObjectNode().put("baseUrl", "https://x");
    return new HarvestRequest(1, config, Map.of("token", "hunter2"), MAPPER.createObjectNode().put("page", 3),
      new HarvestRequest.Limits(10, 5000L));
  }

  @Test
  void roundTripsAFullEnvelope() {
    assertThat(RequestCodec.decode(RequestCodec.encode(request()))).isEqualTo(request());
  }

  @Test
  void firstRunHasNullCursorAndEmptyLimits() {
    HarvestRequest decoded = RequestCodec.decode("{\"protocol\":1,\"config\":{},\"secrets\":{}}");

    assertThat(decoded.cursor()).isNull();
    assertThat(decoded.limits().maxItemsPerRun()).isNull();
    assertThat(decoded.limits().maxCharsPerRun()).isNull();
  }

  @Test
  void explicitNullCursorIsAFirstRun() {
    assertThat(RequestCodec.decode("{\"protocol\":1,\"config\":{},\"secrets\":{},\"cursor\":null}").cursor()).isNull();
  }

  @Test
  void requestNeverPrintsSecrets() {
    assertThat(request().toString()).doesNotContain("hunter2");
  }

  @ParameterizedTest
  @ValueSource(strings = {"not json", "{\"config\":{},\"secrets\":{}}", "{\"protocol\":1,\"config\":[],\"secrets\":{}}",
    "{\"protocol\":1,\"secrets\":{}}", "{\"protocol\":99999999999,\"config\":{},\"secrets\":{}}", "{\"protocol\":1,\"config\":{},\"secrets\":{\"a\":1}}",
    "{\"protocol\":1,\"config\":{},\"secrets\":{},\"limits\":{\"maxItemsPerRun\":0}}"})
  void malformedEnvelopesAreViolations(String line) {
    assertThatThrownBy(() -> RequestCodec.decode(line)).isInstanceOf(ProtocolViolation.class);
  }
}
