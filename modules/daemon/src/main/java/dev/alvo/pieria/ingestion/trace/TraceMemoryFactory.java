package dev.alvo.pieria.ingestion.trace;

import dev.alvo.pieria.api.request.TraceStatus;
import dev.alvo.pieria.domain.memory.Memory;
import dev.alvo.pieria.domain.memory.MemoryTimes;
import dev.alvo.pieria.domain.memory.MemoryType;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds durable memories from traces deterministically, in Java, with no model call.
 *
 * <p>A trace already states the command, the exit code, and the error text. There is nothing to
 * infer, so there is nothing for the verify stage to catch — these memories are grounded by
 * construction. The model's only job on the trace path is generalizing a <em>recipe</em> from a
 * sequence, which {@code TraceRecipeExtractor} owns.
 *
 * <p>Outcomes are keyed on the command signature so the existing supersession machinery keeps one
 * active row per command: run {@code n+1} demotes run {@code n} to history and drops its vector in
 * the same transaction.
 */
public final class TraceMemoryFactory {

  /** Payload marker distinguishing trace-derived memories from conversational ones. */
  public static final String SOURCE_TRACE = "trace";

  public static final String OUTCOME_KEY_PREFIX = "trace:outcome:";
  public static final String RECIPE_KEY_PREFIX = "trace:recipe:";

  /**
   * Payload key holding resolved code-index symbol ids. Must stay exactly this spelling:
   * {@code SqliteMemoryStore.findCodeMemoriesBySymbolIds} queries {@code payload.$.symbolIds}, and
   * renaming it would silently cut trace memories out of the code channels.
   */
  private static final String SYMBOL_IDS = "symbolIds";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private TraceMemoryFactory() {
  }

  /** The factual {@code event} memory for one trace. */
  public static Memory outcome(TraceEvent event, List<String> symbolIds) {
    String content = outcomeContent(event);
    ObjectNode payload = basePayload(event.occurredAt(), symbolIds);
    payload.put("tool", event.tool());
    payload.put("command", event.invocation());
    payload.put("status", event.status().wire());
    if (event.exitCode() != null) {
      payload.put("exit_code", event.exitCode());
    }
    payload.put("error_digest", CommandSignature.errorDigest(event.errorOrOutput()));

    Memory memory = Memory.of(
      MemoryType.EVENT, content, event.sessionId(),
      OUTCOME_KEY_PREFIX + event.signature(), payload.toString());

    return withEmbedText(memory, embedText(content, event.invocation(),
      event.status() == TraceStatus.FAILURE));
  }

  /** A model-derived procedural {@code instruction}, keyed so a changed recipe supersedes. */
  public static Memory recipe(String statement, String signature, Instant statedAt,
                              List<String> symbolIds) {
    ObjectNode payload = basePayload(statedAt, symbolIds);
    Memory memory = Memory.of(
      MemoryType.INSTRUCTION, statement, null, RECIPE_KEY_PREFIX + signature, payload.toString());
    return withEmbedText(memory, statement);
  }

  /**
   * The raw evidence row stored in {@code messages} under role {@code "tool"}. Carries the command
   * and its output verbatim, because {@code MessageFtsChannel} searches this text.
   */
  public static String rawMessageContent(TraceEvent event) {
    StringBuilder text = new StringBuilder()
      .append(event.tool()).append(' ').append(event.args() == null ? "" : event.args())
      .append("\nstatus: ").append(event.status().wire());
    if (event.exitCode() != null) {
      text.append(" (exit ").append(event.exitCode()).append(')');
    }
    if (event.output() != null && !event.output().isBlank()) {
      text.append("\noutput:\n").append(event.output());
    }
    if (event.error() != null && !event.error().isBlank()) {
      text.append("\nerror:\n").append(event.error());
    }
    return text.toString();
  }

  private static String outcomeContent(TraceEvent event) {
    String invocation = "`" + event.invocation() + "`";
    String exitPart = event.exitCode() == null ? "" : " (exit " + event.exitCode() + ")";
    return switch (event.status()) {
      case FAILURE -> invocation + " failed" + exitPart + ": " + event.signalLine();
      case SUCCESS -> invocation + " succeeded" + exitPart;
      case UNKNOWN -> invocation + " ran; outcome unknown";
    };
  }

  /**
   * The payload fields every trace memory carries. Both times come from the trace, never from the
   * store clock: {@code occurred_at} because the command genuinely ran then, {@code stated_at}
   * because {@code MemoryTimes.knowledgeTime} reads it to order supersession — a spool drained
   * hours later must still order by when the command ran.
   */
  private static ObjectNode basePayload(Instant at, List<String> symbolIds) {
    ObjectNode payload = MAPPER.createObjectNode();
    payload.put("source", SOURCE_TRACE);
    if (at != null) {
      payload.put(MemoryTimes.OCCURRED_AT, at.toString());
      payload.put(MemoryTimes.STATED_AT, at.toString());
    }
    if (symbolIds != null && !symbolIds.isEmpty()) {
      ArrayNode ids = payload.putArray(SYMBOL_IDS);
      symbolIds.forEach(ids::add);
    }
    return payload;
  }

  /**
   * Pair the declarative statement with the questions an agent actually asks. These are fixed
   * templates, not model output — the point is that "how do I run the tests" reaches a memory whose
   * content is a command line.
   */
  private static String embedText(String content, String invocation, boolean failed) {
    List<String> lines = new ArrayList<>();
    lines.add(content);
    lines.add("how do I run `" + invocation + "`");
    lines.add("does `" + invocation + "` pass");
    lines.add("what command runs `" + invocation + "`");
    if (failed) {
      lines.add("why does `" + invocation + "` fail");
    }
    return String.join("\n", lines);
  }

  private static Memory withEmbedText(Memory memory, String embedText) {
    return new Memory(memory.id(), memory.sessionId(), memory.type(), memory.content(),
      memory.topicKey(), memory.supersedes(), memory.superseded(), memory.payload(),
      embedText, memory.createdAt());
  }
}
