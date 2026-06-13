package dev.alvo.pieria.cli.modules.update;

import java.util.List;

/**
 * Where the new binaries come from. Two implementations: {@link LocalDistSource} (a dist dir built
 * by {@code nativeDist}/{@code jvmDist} — the dev loop) and {@link ReleaseSource} (download a
 * published release — the end-user path). Both resolve to a {@link StagedDist}.
 */
public interface BinarySource {

  /**
   * The logical binaries every distribution provides; {@link Platform#exeName(String)} maps each to
   * its on-disk file name.
   */
  List<String> BINARIES = List.of("pieria", "pieria-daemon", "pieria-gateway");

  /**
   * Acquire the distribution (download/extract or validate-in-place) and return it staged on disk.
   *
   * @throws UpdateException if the source cannot be acquired or is malformed
   */
  StagedDist resolve();

  /**
   * Short human description of where the binaries come from, for the {@code --dry-run} plan.
   */
  String describe();
}
