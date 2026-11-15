package dev.alvo.pieria.harvesters.teams;

import java.util.Set;

/**
 * What a sign-in or refresh returned.
 *
 * @param scopes granted permissions, lower-cased and without Graph's resource prefix
 */
public record Tokens(String accessToken, String refreshToken, Set<String> scopes) {

  public Tokens {
    scopes = Set.copyOf(scopes);
  }

  @Override
  public String toString() {
    return "Tokens[scopes=" + scopes + "]";
  }
}
