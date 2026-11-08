package dev.alvo.pieria.domain.memory;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SourceFilterTests {

  @Test
  void noneAdmitsEverything() {
    assertThat(SourceFilter.NONE.admits(null)).isTrue();
    assertThat(SourceFilter.NONE.admits("a")).isTrue();
    assertThat(SourceFilter.NONE.isEmpty()).isTrue();
  }

  @Test
  void includeLimitsToNamedSources() {
    SourceFilter filter = SourceFilter.of(List.of("a"), List.of());

    assertThat(filter.admits("a")).isTrue();
    assertThat(filter.admits("b")).isFalse();
    assertThat(filter.admits(null)).isFalse();
  }

  @Test
  void excludeKeepsOwnMemories() {
    SourceFilter filter = SourceFilter.of(List.of(), List.of("a"));

    assertThat(filter.admits("a")).isFalse();
    assertThat(filter.admits(null)).isTrue();
  }

  @Test
  void excludeWinsOverInclude() {
    assertThat(SourceFilter.of(List.of("a"), List.of("a")).admits("a")).isFalse();
  }

  @Test
  void nullListsAreEmpty() {
    assertThat(SourceFilter.of(null, null).isEmpty()).isTrue();
  }

  @Test
  void rejectsUnsafeIds() {
    assertThatThrownBy(() -> SourceFilter.of(List.of("a' OR 1=1"), List.of()))
      .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> SourceFilter.of(List.of(), List.of("a' OR 1=1")))
      .isInstanceOf(IllegalArgumentException.class);
  }
}
