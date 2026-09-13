package dev.alvo.pieria.domain.temporal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Collectors;

/** Pure interval partitioning. Asserted bounds are never mutated by an override. */
public final class ValidityResolver {
  private ValidityResolver() {}

  public record Assertion(String revisionId, Instant from, Instant to, Instant statedAt,
                          String sourceOrder, long transaction) {
    public Assertion {
      if (from == null || (to != null && !from.isBefore(to))) {
        throw new IllegalArgumentException("validFrom must precede validTo");
      }
    }
  }

  public record Segment(String revisionId, Instant from, Instant to) {}

  public static List<Segment> resolve(List<Assertion> assertions) {
    TreeSet<Instant> boundaries = new TreeSet<>();
    for (Assertion a : assertions) {
      boundaries.add(a.from());
      if (a.to() != null) boundaries.add(a.to());
    }
    List<Instant> points = List.copyOf(boundaries);
    List<Segment> result = new ArrayList<>();
    // Choose a single tie-break mode per effective-start bucket. Pairwise switching between
    // speaking time and transaction order would make a mixed comparator non-transitive.
    var untimedStarts = assertions.stream().filter(a -> a.statedAt() == null)
      .map(Assertion::from).collect(Collectors.toSet());
    Comparator<Assertion> priority = Comparator.comparing(Assertion::from)
      .thenComparing(a -> untimedStarts.contains(a.from()) ? Instant.MIN : a.statedAt())
      .thenComparingLong(a -> untimedStarts.contains(a.from()) ? a.transaction() : 0)
      .thenComparing(Assertion::sourceOrder).thenComparing(Assertion::revisionId);
    for (int i = 0; i < points.size(); i++) {
      Instant from = points.get(i);
      Instant to = i + 1 < points.size() ? points.get(i + 1) : null;
      Assertion winner = assertions.stream()
        .filter(a -> !from.isBefore(a.from()) && (a.to() == null || from.isBefore(a.to())))
        .max(priority).orElse(null);
      if (winner == null) continue;
      if (!result.isEmpty()) {
        Segment last = result.getLast();
        if (last.revisionId().equals(winner.revisionId()) && from.equals(last.to())) {
          result.set(result.size() - 1, new Segment(last.revisionId(), last.from(), to));
          continue;
        }
      }
      result.add(new Segment(winner.revisionId(), from, to));
    }
    return List.copyOf(result);
  }
}
