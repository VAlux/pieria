package dev.alvo.pieria.harvester.conformance;

import java.util.List;
import java.util.stream.Collectors;

/** The outcome of every conformance check, in the order they ran. */
public record ConformanceReport(List<Result> results) {

  public enum Status { PASS, FAIL, SKIP }

  public record Result(String check, Status status, String detail) {
  }

  public ConformanceReport {
    results = List.copyOf(results);
  }

  public boolean passed() {
    return results.stream().noneMatch(result -> result.status() == Status.FAIL);
  }

  public String render() {
    return results.stream()
      .map(result -> String.format("%-5s %-30s %s", result.status(), result.check(), result.detail()).stripTrailing())
      .collect(Collectors.joining("\n"));
  }
}
