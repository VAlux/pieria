package dev.alvo.pieria.tools;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class RedactionTests {

  @Test
  void shortTextIsNotTruncated() {
    assertThat(Redaction.truncate("hello", 100)).isEqualTo("hello");
    assertThat(Redaction.truncate(null, 100)).isNull();
  }

  // The trailing lines carry the error, which is the whole reason a failing trace is worth
  // storing. Head-only truncation would clip exactly the signal.
  @Test
  void truncationKeepsBothEndsAndFavoursTheTail() {
    String text = "HEAD" + "x".repeat(500) + "TAIL";

    String truncated = Redaction.truncate(text, 100);

    assertThat(truncated).startsWith("HEAD");
    assertThat(truncated).endsWith("TAIL");
    assertThat(truncated).contains("elided");
    assertThat(truncated.length()).isLessThan(text.length());
  }

  @Test
  void assignmentStyleSecretsAreRedacted() {
    Redaction.Redacted result = Redaction.redactSecrets(
      "export API_KEY=abcd1234efgh5678\npassword: hunter2trombone");

    assertThat(result.text()).doesNotContain("abcd1234efgh5678");
    assertThat(result.text()).doesNotContain("hunter2trombone");
    assertThat(result.text()).contains("[redacted]");
    assertThat(result.hits()).isEqualTo(2);
  }

  @Test
  void wellKnownTokenShapesAreRedacted() {
    String text = String.join("\n",
      "ghp_" + "a".repeat(36),
      "sk-" + "b".repeat(32),
      "Bearer " + "c".repeat(24),
      "AKIA" + "D".repeat(16));

    Redaction.Redacted result = Redaction.redactSecrets(text);

    assertThat(result.text()).doesNotContain("a".repeat(36));
    assertThat(result.text()).doesNotContain("b".repeat(32));
    assertThat(result.text()).doesNotContain("c".repeat(24));
    assertThat(result.text()).doesNotContain("AKIA" + "D".repeat(16));
    assertThat(result.hits()).isEqualTo(4);
  }

  @Test
  void privateKeyBlockIsRedacted() {
    String text = "-----BEGIN RSA PRIVATE KEY-----\nMIIEow\nlines\n-----END RSA PRIVATE KEY-----";

    Redaction.Redacted result = Redaction.redactSecrets(text);

    assertThat(result.text()).doesNotContain("MIIEow");
    assertThat(result.hits()).isEqualTo(1);
  }

  @Test
  void cleanTextIsUnchangedAndScoresNoHits() {
    Redaction.Redacted result = Redaction.redactSecrets("./gradlew test\nBUILD FAILED");

    assertThat(result.text()).isEqualTo("./gradlew test\nBUILD FAILED");
    assertThat(result.hits()).isZero();
  }

  @Test
  void repoAndHomePathsAreNormalized() {
    Path repo = Path.of("/Users/dev/projects/pieria");
    Path home = Path.of("/Users/dev");

    String text = Redaction.normalizePaths(
      "error in /Users/dev/projects/pieria/modules/daemon/App.java and /Users/dev/.m2/settings.xml",
      repo, home);

    assertThat(text).contains("./modules/daemon/App.java");
    assertThat(text).contains("~/.m2/settings.xml");
    assertThat(text).doesNotContain("/Users/dev");
  }

  // Redaction runs in the hook and again in the daemon; running it twice must not corrupt the text
  // or double-count.
  @Test
  void scrubIsIdempotent() {
    Path repo = Path.of("/repo");
    String raw = "cd /repo/src && TOKEN=zzzzzzzzzzzzzzzz ./run.sh";

    Redaction.Redacted once = Redaction.scrub(raw, 4000, repo, Path.of("/home/dev"));
    Redaction.Redacted twice = Redaction.scrub(once.text(), 4000, repo, Path.of("/home/dev"));

    assertThat(twice.text()).isEqualTo(once.text());
    assertThat(twice.hits()).isZero();
  }

  @Test
  void scrubTruncatesBeforeRedacting() {
    // A secret past the budget never reaches the regex, and never reaches disk.
    String raw = "start" + "-".repeat(9000) + "API_KEY=abcd1234efgh5678";

    Redaction.Redacted result = Redaction.scrub(raw, 200, Path.of("/repo"), Path.of("/home"));

    assertThat(result.text().length()).isLessThanOrEqualTo(260);
    assertThat(result.text()).doesNotContain("abcd1234efgh5678");
  }

  // I1: snake_case and kebab-case credential names must be recognized.
  @Test
  void snakeCaseCredentialNamesAreRedacted() {
    Redaction.Redacted result = Redaction.redactSecrets(
      "SECRET_KEY=abcd1234efgh\nDATABASE_PASSWORD=hunter2pass\nGITHUB_TOKEN=ghp_abc123456789\nmy_password_field=secret123456");

    assertThat(result.text()).doesNotContain("abcd1234efgh");
    assertThat(result.text()).doesNotContain("hunter2pass");
    assertThat(result.text()).doesNotContain("ghp_abc123456789");
    assertThat(result.text()).doesNotContain("secret123456");
    assertThat(result.hits()).isEqualTo(4);
  }

  // I2: Quoted JSON keys must match the credential pattern.
  @Test
  void quotedJsonKeysAreRedacted() {
    String jsonLine = "{\"password\": \"hunter2trombone\"}";

    Redaction.Redacted result = Redaction.redactSecrets(jsonLine);

    assertThat(result.text()).doesNotContain("hunter2trombone");
    assertThat(result.text()).contains("[redacted]");
    assertThat(result.hits()).isEqualTo(1);
  }

  // I3: Bearer pattern must not cause the assignment pattern to double-count or duplicate the mask.
  @Test
  void bearerTokenIsNotDoubleRedacted() {
    String text = "token: Bearer " + "c".repeat(24);

    Redaction.Redacted result = Redaction.redactSecrets(text);

    // Should be redacted once by Bearer pattern, not again by assignment pattern
    assertThat(result.text()).contains("[redacted]");
    assertThat(result.hits()).isEqualTo(1);
    // Verify no duplicate mask
    assertThat(result.text()).doesNotContain("[redacted] [redacted]");
  }

  // I4: Quoted secret values must keep their closing quote.
  @Test
  void quotedSecretValuesPreserveClosingQuote() {
    String text = "password: \"hunter2trombone\"\napi_key: 'abcd1234efgh5678'";

    Redaction.Redacted result = Redaction.redactSecrets(text);

    assertThat(result.text()).containsPattern("password: \"\\[redacted\\]\"");
    assertThat(result.text()).containsPattern("api_key: '\\[redacted\\]'");
    assertThat(result.hits()).isEqualTo(2);
  }

  // C1: Long non-terminating credential names must not cause StackOverflowError.
  // Regression test for catastrophic backtracking in the possessive suffix group.
  // The suffix group (?:[_-][a-zA-Z0-9]+)*+ must not attempt unbounded backtracking when
  // no terminating : or = is found. Input of 5000 _a repetitions would overflow stack without
  // the possessive quantifier fix.
  @Test
  void longNonTerminatingCredentialNameDoesNotCauseStackOverflow() {
    String text = "secret" + "_a".repeat(5000);  // No terminating : or =

    // Should complete without StackOverflowError
    Redaction.Redacted result = Redaction.redactSecrets(text);

    // No secrets detected because there's no terminating : or =
    assertThat(result.hits()).isZero();
    assertThat(result.text()).isEqualTo(text);
  }
}
