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
}
