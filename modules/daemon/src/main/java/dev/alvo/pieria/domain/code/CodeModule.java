package dev.alvo.pieria.domain.code;

import dev.alvo.pieria.domain.ContentId;

import java.time.Instant;

/**
 * A build unit or source root (e.g. a Gradle module or a package directory), detected
 * deterministically from the repo layout. The {@code id} is content-addressed over
 * {@code (profileId, path)} (see {@link ContentId#forCodeModule}) so a module collapses to one row
 * regardless of how many files reference it. {@code id} and {@code createdAt} are assigned at store
 * time when null.
 *
 * @param name  display label (e.g. {@code "daemon"})
 * @param path  repo-relative module root (the identity component)
 */
public record CodeModule(
  String id,
  String profileId,
  String name,
  String path,
  Instant createdAt) {

  /** A freshly detected module with no id or timestamp yet (assigned at store time). */
  public static CodeModule of(String name, String path) {
    return new CodeModule(null, null, name, path, null);
  }
}
