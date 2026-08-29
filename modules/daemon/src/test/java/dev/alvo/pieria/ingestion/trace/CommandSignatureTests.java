package dev.alvo.pieria.ingestion.trace;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CommandSignatureTests {

  @Test
  void bashCommandDropsLeadingDotSlashAndFlags() {
    assertThat(CommandSignature.of("Bash", "./gradlew test --info")).isEqualTo("gradlew-test");
    assertThat(CommandSignature.of("Bash", "gradlew test")).isEqualTo("gradlew-test");
  }

  @Test
  void moduleQualifiedGradleTaskKeepsItsModule() {
    assertThat(CommandSignature.of("Bash", "./gradlew :daemon:test"))
      .isEqualTo("gradlew-daemon-test");
  }

  // Flag values and bare numbers are run-specific noise; two runs of the same command with
  // different tuning must land on one key so the newer outcome supersedes the older.
  @Test
  void flagValuesAndNumbersAreDropped() {
    assertThat(CommandSignature.of("Bash", "npm test -- --workers 4"))
      .isEqualTo(CommandSignature.of("Bash", "npm test"));
  }

  @Test
  void tokenCountIsCappedAtFour() {
    assertThat(CommandSignature.of("Bash", "a b c d e f g")).isEqualTo("a-b-c-d");
  }

  // Bash is the only tool whose args already name the program. Every other tool needs its own
  // name in the key, or an Edit and a Write of the same file would collide.
  @Test
  void nonBashToolsArePrefixedWithTheToolName() {
    assertThat(CommandSignature.of("Edit", "src/main/java/Foo.java"))
      .isEqualTo("edit-src-main-java-foo-java");
    assertThat(CommandSignature.of("Write", "src/main/java/Foo.java"))
      .isEqualTo("write-src-main-java-foo-java");
  }

  @Test
  void blankArgsFallBackToTheToolName() {
    assertThat(CommandSignature.of("Bash", "  ")).isEqualTo("bash");
    assertThat(CommandSignature.of("Bash", null)).isEqualTo("bash");
  }

  @Test
  void emptyErrorDigestsToASentinel() {
    assertThat(CommandSignature.errorDigest(null)).isEqualTo("none");
    assertThat(CommandSignature.errorDigest("   ")).isEqualTo("none");
  }

  // A recompile shifts stack frames by a line without changing what failed. Masking line and
  // column numbers is what stops that from reading as a new outcome and churning supersession.
  @Test
  void lineAndColumnNumbersDoNotChangeTheDigest() {
    String first = "at dev.alvo.Foo.bar(Foo.java:52)";
    String second = "at dev.alvo.Foo.bar(Foo.java:71)";

    assertThat(CommandSignature.errorDigest(first))
      .isEqualTo(CommandSignature.errorDigest(second));
  }

  @Test
  void differentFailuresDigestDifferently() {
    assertThat(CommandSignature.errorDigest("NullPointerException in Foo"))
      .isNotEqualTo(CommandSignature.errorDigest("AssertionError in Bar"));
  }

  @Test
  void onlyTheTailIsDigested() {
    String shared = "x".repeat(600) + "SAME TAIL";

    assertThat(CommandSignature.errorDigest("PREFIX-A" + shared))
      .isEqualTo(CommandSignature.errorDigest("PREFIX-B" + shared));
  }
}
