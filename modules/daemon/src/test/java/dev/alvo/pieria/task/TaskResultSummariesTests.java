package dev.alvo.pieria.task;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TaskResultSummariesTests {

  private final ObjectMapper mapper = JsonMapper.builder().build();

  @Test
  void describesEachKnownResultShape() {
    assertThat(TaskResultSummaries.summarize(mapper.valueToTree(Map.of("count", 1)))).isEqualTo("1 memory stored");
    assertThat(TaskResultSummaries.summarize(mapper.valueToTree(Map.of("filesParsed", 12, "symbols", 340))))
      .isEqualTo("12 files parsed · 340 symbols");
    assertThat(TaskResultSummaries.summarize(mapper.valueToTree(Map.of("memoriesAdopted", 4, "memoriesScanned", 10))))
      .isEqualTo("4 of 10 memories adopted");
    assertThat(TaskResultSummaries.summarize(mapper.valueToTree(Map.of("documents", 2, "memoriesStored", 9))))
      .isEqualTo("2 documents · 9 memories stored");
  }

  @Test
  void unknownOrMissingResultsHaveNoSummary() {
    assertThat(TaskResultSummaries.summarize(null)).isNull();
    assertThat(TaskResultSummaries.summarize(mapper.valueToTree(Map.of("other", 1)))).isNull();
  }
}
