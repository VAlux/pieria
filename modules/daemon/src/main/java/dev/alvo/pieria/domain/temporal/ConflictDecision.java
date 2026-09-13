package dev.alvo.pieria.domain.temporal;

/** Grounded model judgment, revalidated against the targeted revision at commit. */
public record ConflictDecision(String revisionId, String relationship, String subject, String property,
                               String incomingEvidence, String existingEvidence) {}
