package dev.alvo.pieria.onboarding;

/**
 * A unit of text discovered by a content source, ready to feed the memory-extraction pipeline. The
 * {@code provenance} label (e.g. {@code "Project documentation — docs/SPEC.md"} or
 * {@code "Web page — https://…"}) is prefixed onto every chunk the document is split into, so the
 * extractor always knows where a fact came from even after chunking.
 */
public record ContentDocument(String provenance, String text) {
}
