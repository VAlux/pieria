package dev.alvo.pieria.harvester.protocol;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.Map;

/**
 * The single envelope a harvester reads from stdin.
 *
 * @param protocol protocol version the daemon is speaking
 * @param config   the source's non-secret settings
 * @param secrets  only the secrets the harvester's manifest declares
 * @param cursor   the last checkpoint's cursor, or null on a first run
 * @param limits   how much this run may produce; never null, its fields may be
 */
public record HarvestRequest(int protocol, ObjectNode config, Map<String, String> secrets, JsonNode cursor,
                             Limits limits) {

  public static final int PROTOCOL_VERSION = 1;

  /** Run limits. A harvester that reaches one stops, leaves a checkpoint and exits 0 with no sweep. */
  public record Limits(Integer maxItemsPerRun, Long maxCharsPerRun) {
    public static final Limits NONE = new Limits(null, null);

    public Limits {
      if ((maxItemsPerRun != null && maxItemsPerRun <= 0) || (maxCharsPerRun != null && maxCharsPerRun <= 0)) {
        throw new ProtocolViolation("limits must be positive");
      }
    }
  }

  public HarvestRequest {
    if (config == null) {
      throw new ProtocolViolation("config is required");
    }
    secrets = secrets == null ? Map.of() : Map.copyOf(secrets);
    cursor = cursor == null || cursor.isNull() ? null : cursor;
    limits = limits == null ? Limits.NONE : limits;
  }

  @Override
  public String toString() {
    return "HarvestRequest[protocol=" + protocol + ", config=" + config + ", secrets=" + secrets.keySet()
      + ", cursor=" + cursor + ", limits=" + limits + "]";
  }
}
