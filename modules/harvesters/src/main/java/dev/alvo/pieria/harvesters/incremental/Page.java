package dev.alvo.pieria.harvesters.incremental;

import java.util.List;

/** One page of a listing. {@code next} asks for the following page, and is null on the last. */
public record Page<T>(List<T> entries, String next) {

  public Page {
    entries = List.copyOf(entries);
  }
}
