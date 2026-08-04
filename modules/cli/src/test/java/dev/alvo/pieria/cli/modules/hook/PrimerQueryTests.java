package dev.alvo.pieria.cli.modules.hook;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the session-open primer query against the failure that produced it: phrased in the memory
 * system's own vocabulary, it lexically matched memories describing the memory system and injected
 * those instead of anything about the project.
 */
class PrimerQueryTests {

  /**
   * Words that appear in Pieria's own standing instructions. A primer query containing them ranks
   * those memories first on any profile where they were ingested, which is every profile whose
   * harness instructions mention Pieria.
   */
  private static final List<String> MEMORY_SYSTEM_VOCABULARY =
    List.of("fact", "decision", "task", "session", "memory", "memories", "context", "recall");

  private static final List<HarnessHookSpec> SPECS =
    List.of(HarnessHookSpec.CLAUDE_CODE, HarnessHookSpec.CODEX, HarnessHookSpec.OPENCODE);

  @Test
  void everyHarnessPrimesWithTheSameQuery() {
    assertThat(SPECS).extracting(HarnessHookSpec::primerQuery).containsOnly(SPECS.getFirst().primerQuery());
  }

  @Test
  void primerQueryAvoidsMemorySystemVocabulary() {
    String query = HarnessHookSpec.CLAUDE_CODE.primerQuery().toLowerCase(Locale.ROOT);

    assertThat(MEMORY_SYSTEM_VOCABULARY)
      .allSatisfy(word -> assertThat(query)
        .withFailMessage(
          "primer query must not contain memory-system vocabulary %s — it makes the primer retrieve "
            + "memories about Pieria instead of about the project; query was: %s", word, query)
        .doesNotContain(word));
  }

  @Test
  void primerQueryAsksAboutTheCodebase() {
    String query = HarnessHookSpec.CLAUDE_CODE.primerQuery().toLowerCase(Locale.ROOT);

    assertThat(query).contains("architecture").contains("build").contains("codebase");
  }

  @Test
  void everyHarnessDeclaresAUsablePrimer() {
    assertThat(SPECS).allSatisfy(spec -> {
      assertThat(spec.primerQuery()).isNotBlank();
      assertThat(spec.primerLimit()).isPositive();
    });
  }
}
