package dev.alvo.pieria.api.request;

public record WikiEvaluateRequest(Long expectedDescriptorRevision,String expectedVersionId,String intent) {}
