package dev.alvo.pieria.ingestion.trace;

import dev.alvo.pieria.api.request.TraceStatus;
import dev.alvo.pieria.domain.graph.EntityNormalizer;
import dev.alvo.pieria.domain.graph.GraphFragment;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns one trace into the entity/relation fragment stored alongside its memory, so the Phase 8
 * graph channel co-retrieves related commands, tests, and files.
 *
 * <p>Entity types are {@code command}, {@code tool}, {@code test}, {@code file}, and {@code module}.
 * The feature brief's "build tool" is deliberately not its own type: Gradle, Maven, and npm are
 * {@code tool} nodes distinguished by name, and a type that only ever holds three values buys
 * nothing.
 *
 * <p>Purely deterministic — no model is asked what a command relates to.
 */
public class TraceGraphBuilder {

  private static final String TYPE_COMMAND = "command";
  private static final String TYPE_TOOL = "tool";
  private static final String TYPE_TEST = "test";
  private static final String TYPE_FILE = "file";
  private static final String TYPE_MODULE = "module";

  /** Gradle's failure line: {@code SomeTests > someCase FAILED}. */
  private static final Pattern GRADLE_TEST_FAILURE =
    Pattern.compile("([A-Za-z_][A-Za-z0-9_.$]*)\\s*>\\s*[^\\n]*?\\bFAILED\\b");

  /** A module-qualified Gradle task: {@code :daemon:test}. */
  private static final Pattern GRADLE_MODULE_TASK = Pattern.compile(":([A-Za-z0-9_-]+):");

  /** A source path with a recognized extension. */
  private static final Pattern SOURCE_PATH =
    Pattern.compile("[\\w./-]+\\.(?:java|kt|scala|js|ts|tsx|scss|py|go|rs)");

  private final int maxEntities;
  private final int maxTriples;

  public TraceGraphBuilder(int maxEntities, int maxTriples) {
    this.maxEntities = Math.max(1, maxEntities);
    this.maxTriples = Math.max(1, maxTriples);
  }

  public GraphFragment build(TraceEvent event) {
    String command = EntityNormalizer.normalizeName(commandName(event));
    if (command.isEmpty()) {
      return GraphFragment.empty();
    }
    List<GraphFragment.EdgeTriple> triples = new ArrayList<>();

    String tool = EntityNormalizer.normalizeName(event.tool());
    if (!tool.isEmpty()) {
      triples.add(triple(tool, TYPE_TOOL, "invoked", command, TYPE_COMMAND));
    }

    // Only a failure may claim a failure edge, or "why did X fail" would retrieve a green build.
    if (event.status() == TraceStatus.FAILURE) {
      for (String test : matches(GRADLE_TEST_FAILURE, event.error(), event.output())) {
        triples.add(triple(command, TYPE_COMMAND, "failed_in",
          EntityNormalizer.normalizeName(test), TYPE_TEST));
      }
    }

    for (String module : matches(GRADLE_MODULE_TASK, event.args())) {
      triples.add(triple(command, TYPE_COMMAND, "validates",
        EntityNormalizer.normalizeName(module), TYPE_MODULE));
    }

    if (isEditingTool(event.tool())) {
      for (String path : matches(SOURCE_PATH, event.args())) {
        triples.add(triple(command, TYPE_COMMAND, "touched",
          EntityNormalizer.normalizeName(path), TYPE_FILE));
      }
    }

    List<GraphFragment.EdgeTriple> capped =
      triples.size() <= maxTriples ? triples : triples.subList(0, maxTriples);
    GraphFragment fragment = new GraphFragment(List.of(), capped);
    return fragment.allEntities().size() > maxEntities
      ? new GraphFragment(List.of(), capped.subList(0, Math.max(1, maxEntities / 2)))
      : fragment;
  }

  /** The command node's name: the invocation minus a leading {@code ./}, lower-cased downstream.
   * If args is blank, returns empty string (no meaningful command to graph). */
  private static String commandName(TraceEvent event) {
    String args = event.args();
    if (args == null || args.isBlank()) {
      return "";
    }
    String invocation = event.invocation();
    return invocation == null ? "" : invocation.replace("./", "").strip();
  }

  private static boolean isEditingTool(String tool) {
    if (tool == null) {
      return false;
    }
    return switch (tool.toLowerCase(java.util.Locale.ROOT)) {
      case "edit", "write", "multiedit", "notebookedit" -> true;
      default -> false;
    };
  }

  private static GraphFragment.EdgeTriple triple(String source, String sourceType, String relation,
                                                 String target, String targetType) {
    return new GraphFragment.EdgeTriple(source, sourceType, relation, target, targetType);
  }

  /** First capture group of every match across the given texts, deduped, in order. */
  private static List<String> matches(Pattern pattern, String... texts) {
    List<String> found = new ArrayList<>();
    for (String text : texts) {
      if (text == null || text.isBlank()) {
        continue;
      }
      Matcher matcher = pattern.matcher(text);
      while (matcher.find()) {
        String value = matcher.groupCount() >= 1 ? matcher.group(1) : matcher.group();
        if (value != null && !value.isBlank() && !found.contains(value)) {
          found.add(value);
        }
      }
    }
    return found;
  }
}
