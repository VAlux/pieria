package dev.alvo.pieria.evaluation;

import dev.alvo.pieria.tools.io.FileOps;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A throwaway {@code PIERIA_HOME} for one in-process Spring context, discarded on {@link #close()}.
 *
 * <p>Both contexts the harness boots — the daemon under test ({@link LiveDaemon}) and the faithfulness
 * judge ({@link LiveModelGatewayFactory}) — start from {@code PieriaApplication}, which would otherwise
 * open the operator's real database. Every one of them is pointed here instead, so a benchmark never
 * touches the installed store.
 */
final class EvalHome implements AutoCloseable {

  private final Path root;

  private EvalHome(Path root) {
    this.root = root;
  }

  static EvalHome create() {
    try {
      return new EvalHome(Files.createTempDirectory("pieria-eval-"));
    } catch (IOException e) {
      throw new IllegalStateException("could not create a temp PIERIA_HOME for the eval context", e);
    }
  }

  Path root() {
    return root;
  }

  /**
   * Command-line-style property overrides pinning the context's storage into this temp home.
   * Command-line args have the highest precedence, so they win over the daemon's bundled
   * {@code application.properties} <em>and</em> over {@code configFile} — a benchmarked config can
   * therefore change models, prompts and retrieval weights, but can never redirect the run at the
   * operator's real database.
   *
   * <p>{@code configFile} (nullable) is layered in as an additional config-data location: that is how
   * a run benchmarks the pipeline the operator actually deploys. The bundled
   * {@code application.properties} imports {@code ${pieria.app-data.config-dir}/pieria.properties},
   * but config-dir is pinned to this temp home, so nothing is ever picked up implicitly.
   */
  List<String> springArgs(Path configFile) {
    List<String> args = new ArrayList<>();
    if (configFile != null) {
      args.add("--spring.config.additional-location=optional:file:" + configFile.toAbsolutePath());
    }
    args.add("--pieria.db.path=" + root.resolve("pieria.db"));
    args.add("--pieria.app-data.root=" + root);
    args.add("--pieria.app-data.database-dir=" + root.resolve("db"));
    args.add("--pieria.app-data.config-dir=" + root.resolve("config"));
    args.add("--pieria.app-data.logs-dir=" + root.resolve("logs"));
    args.add("--pieria.app-data.runtime-dir=" + root.resolve("run"));
    // Assume the operator already has the configured models; never pull during a benchmark.
    args.add("--pieria.first-run.check-models=false");
    return args;
  }

  @Override
  public void close() {
    FileOps.deleteRecursivelyQuietly(root);
  }
}
