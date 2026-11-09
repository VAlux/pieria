package dev.alvo.pieria.harvest;

/** The state of one directory under the harvesters root. {@code detail} explains a problem. */
public record HarvesterDiagnosis(String directory, boolean ok, String detail) {
}
