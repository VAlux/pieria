package dev.alvo.pieria.harvesters.atlassian;

/** Where an Atlassian product runs. The two differ in endpoints and in how they authenticate. */
public enum Deployment {
  CLOUD,
  DATA_CENTER
}
