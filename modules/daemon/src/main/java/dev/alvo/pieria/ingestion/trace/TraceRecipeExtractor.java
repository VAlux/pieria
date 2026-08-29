package dev.alvo.pieria.ingestion.trace;

import dev.alvo.pieria.api.request.TraceStatus;
import dev.alvo.pieria.config.TraceProperties;
import dev.alvo.pieria.ingestion.GroundingFilter;
import dev.alvo.pieria.ingestion.model.VerificationResult;
import dev.alvo.pieria.ingestion.model.VerificationVerdict;
import dev.alvo.pieria.model.ModelGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The one model-consulting stage of the trace path: generalizes reusable procedural knowledge from
 * a sequence of tool calls.
 *
 * <p>It runs at most once per ingest batch, over the whole surviving sequence <em>in arrival
 * order</em>. The ordering is the point — a failure and the fix that followed it are frequently in
 * different turns, and only a batch that spans them can produce "X fails with Y; the fix is Z".
 *
 * <p>Unlike the deterministic outcome events, these statements <em>are</em> generalizations, so they
 * go through the same grounding pre-filter and model verification the conversational path uses.
 */
public class TraceRecipeExtractor {

  private static final Logger log = LoggerFactory.getLogger(TraceRecipeExtractor.class);

  private final ModelGateway modelGateway;
  private final TraceProperties properties;

  public TraceRecipeExtractor(ModelGateway modelGateway, TraceProperties properties) {
    this.modelGateway = modelGateway;
    this.properties = properties;
  }

  /**
   * @param recipes   verified statements, capped at {@code maxRecipesPerBatch}
   * @param traceLog  the log the model saw, reused as the verification transcript
   * @param skipped   whether the cost guard or the config switch avoided the model call entirely
   * @param attempted how many candidates the model returned
   * @param dropped   how many verification rejected
   */
  public record Result(List<TraceRecipe> recipes, String traceLog, boolean skipped, int attempted,
                       int dropped) {
  }

  /**
   * The result for every path that avoids the model call entirely: the cost guard, the config
   * switch, or an empty batch. Not a same-named static factory on {@link Result} — the record's own
   * {@code skipped} component already generates a public {@code skipped()} accessor, and a static
   * method with that name would collide with it.
   */
  private static final Result SKIPPED = new Result(List.of(), "", true, 0, 0);

  /**
   * @param events           surviving traces, in arrival order
   * @param knownSignatures  command signatures this profile has already recorded an outcome for
   */
  public Result extract(List<TraceEvent> events, Set<String> knownSignatures) {
    if (!properties.recipeExtractionEnabled() || properties.maxRecipesPerBatch() == 0
      || events == null || events.isEmpty()) {
      return SKIPPED;
    }
    if (!worthAModelCall(events, knownSignatures)) {
      // A batch of routine successes on commands already recorded yields nothing new.
      return SKIPPED;
    }

    String traceLog = renderLog(events);
    List<TraceRecipe> candidates;
    try {
      candidates = modelGateway.extractTraceRecipes(traceLog);
    } catch (RuntimeException e) {
      // Additive and degradable: losing the recipes must never lose the events.
      log.warn("trace recipe extraction failed; storing events without recipes: {}", e.toString());
      return new Result(List.of(), traceLog, false, 0, 0);
    }
    if (candidates == null || candidates.isEmpty()) {
      return new Result(List.of(), traceLog, false, 0, 0);
    }

    return verify(candidates, traceLog);
  }

  /**
   * Whether this batch can plausibly teach anything: any failure, or any command this profile has
   * not recorded an outcome for. Everything else is a repeat of known-good behaviour.
   */
  private static boolean worthAModelCall(List<TraceEvent> events, Set<String> knownSignatures) {
    Set<String> known = knownSignatures == null ? Set.of() : knownSignatures;
    for (TraceEvent event : events) {
      if (event.status() == TraceStatus.FAILURE || !known.contains(event.signature())) {
        return true;
      }
    }
    return false;
  }

  /** The ordered log the model reads, and the transcript verification checks statements against. */
  private static String renderLog(List<TraceEvent> events) {
    StringBuilder log = new StringBuilder();
    int index = 1;
    for (TraceEvent event : events) {
      log.append(index++).append(". ").append(event.invocation())
        .append("\n   status: ").append(event.status().wire());
      if (event.exitCode() != null) {
        log.append(" (exit ").append(event.exitCode()).append(')');
      }
      log.append("\n   signal: ").append(event.signalLine()).append('\n');
    }
    return log.toString();
  }

  /**
   * Grounded statements pass without a model call; the rest go to batched verification, exactly as
   * the conversational path does.
   */
  private Result verify(List<TraceRecipe> candidates, String traceLog) {
    List<TraceRecipe> accepted = new ArrayList<>();
    List<TraceRecipe> suspects = new ArrayList<>();
    for (TraceRecipe candidate : candidates) {
      if (candidate == null || candidate.statement() == null || candidate.statement().isBlank()) {
        continue;
      }
      if (GroundingFilter.grounded(candidate.statement(), traceLog)) {
        accepted.add(candidate);
      } else {
        suspects.add(candidate);
      }
    }

    int dropped = 0;
    if (!suspects.isEmpty()) {
      List<String> contents = suspects.stream().map(TraceRecipe::statement).toList();
      List<VerificationResult> verdicts;
      try {
        verdicts = modelGateway.verifyAll(contents, traceLog);
      } catch (RuntimeException e) {
        log.warn("trace recipe verification failed; dropping {} suspect(s): {}",
          suspects.size(), e.toString());
        verdicts = List.of();
      }
      for (int i = 0; i < suspects.size(); i++) {
        VerificationResult verdict = i < verdicts.size() ? verdicts.get(i) : null;
        if (verdict == null || verdict.verdict() == VerificationVerdict.DROP) {
          dropped++;
          continue;
        }
        String content = verdict.verdict() == VerificationVerdict.CORRECT
          ? verdict.content() : suspects.get(i).statement();
        accepted.add(new TraceRecipe(suspects.get(i).command(), content));
      }
    }

    List<TraceRecipe> capped = accepted.size() <= properties.maxRecipesPerBatch()
      ? accepted : accepted.subList(0, properties.maxRecipesPerBatch());
    return new Result(List.copyOf(capped), traceLog, false, candidates.size(), dropped);
  }
}
