package dev.alvo.pieria.harvester.protocol;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Converts the stdin envelope to and from its one line of JSON. */
public final class RequestCodec {

  private RequestCodec() {
  }

  public static String encode(HarvestRequest request) {
    ObjectNode node = WireJson.MAPPER.createObjectNode();
    node.put("protocol", request.protocol());
    node.set("config", request.config());
    ObjectNode secrets = node.putObject("secrets");
    request.secrets().forEach(secrets::put);
    if (request.cursor() != null) {
      node.set("cursor", request.cursor());
    }
    ObjectNode limits = node.putObject("limits");
    if (request.limits().maxItemsPerRun() != null) {
      limits.put("maxItemsPerRun", request.limits().maxItemsPerRun().intValue());
    }
    if (request.limits().maxCharsPerRun() != null) {
      limits.put("maxCharsPerRun", request.limits().maxCharsPerRun().longValue());
    }
    return node.toString();
  }

  public static HarvestRequest decode(String line) {
    JsonNode node = WireJson.parseObject(line);
    if (!(node.get("config") instanceof ObjectNode config)) {
      throw new ProtocolViolation("config must be an object");
    }
    return new HarvestRequest(
      toInt(WireJson.requiredLong(node, "protocol")),
      config,
      WireJson.stringMap(node, "secrets"),
      node.get("cursor"),
      limits(node.get("limits")));
  }

  private static int toInt(long value) {
    if (value != (int) value) {
      throw new ProtocolViolation("number is out of range: " + value);
    }
    return (int) value;
  }

  private static HarvestRequest.Limits limits(JsonNode limits) {
    if (limits == null || limits.isNull()) {
      return HarvestRequest.Limits.NONE;
    }
    Long maxItems = WireJson.optionalLong(limits, "maxItemsPerRun");
    return new HarvestRequest.Limits(maxItems == null ? null : toInt(maxItems),
      WireJson.optionalLong(limits, "maxCharsPerRun"));
  }
}
