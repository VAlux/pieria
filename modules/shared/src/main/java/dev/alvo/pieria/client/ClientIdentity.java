package dev.alvo.pieria.client;

/** Declared caller identity attached to daemon requests for profile audit attribution. */
public record ClientIdentity(String client, String harness, String channel, String version) {
  public static ClientIdentity directApi() {
    return new ClientIdentity("api", null, "http", null);
  }
}
