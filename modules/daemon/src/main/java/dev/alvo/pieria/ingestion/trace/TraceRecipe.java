package dev.alvo.pieria.ingestion.trace;

/**
 * One procedural statement the model derived from a trace sequence, together with the command it
 * is about.
 *
 * <p>The command matters as much as the statement: it is what the topic key is computed from, so a
 * changed recipe for the same command supersedes rather than accumulating. Asking the model for the
 * command alongside the prose is cheaper and far more reliable than trying to recover it from the
 * sentence afterwards.
 *
 * @param command   the invocation the statement is about, e.g. {@code ./gradlew test}
 * @param statement the durable declarative sentence
 */
public record TraceRecipe(String command, String statement) {
}
