package dev.alvo.pieria.storage;

import dev.alvo.pieria.config.DataSourceConfig.VecCapability;
import dev.alvo.pieria.config.PieriaProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase 3 step 2/3: programmatically creates the {@code memories_vec} (sqlite-vec {@code vec0})
 * virtual table at startup and backfills it from existing embedding BLOBs.
 *
 * <p>The table is created here, not in Flyway, because a {@code vec0} table requires the native
 * extension to be present at creation time; doing it in a migration would crash startup wherever
 * sqlite-vec is unavailable. When the extension did not load this component does nothing, leaving
 * the daemon fully functional with vector search disabled.
 *
 * <p>If the configured embedding dimension conflicts with an already-existing {@code memories_vec}
 * of a different width, startup fails with a clear error (re-embedding the store at a new width is
 * an explicit, deliberate operation — see SPEC 5.1 / 18).
 */
@Component
public class SqliteVectorIndex {

  private static final Logger log = LoggerFactory.getLogger(SqliteVectorIndex.class);
  private static final Pattern FLOAT_WIDTH = Pattern.compile("FLOAT\\s*\\[\\s*(\\d+)\\s*]", Pattern.CASE_INSENSITIVE);

  private final JdbcClient jdbc;
  private final VecCapability vecCapability;
  private final int dimension;
  private final MemoryStore store;

  public SqliteVectorIndex(JdbcClient jdbc,
                           VecCapability vecCapability,
                           PieriaProperties properties,
                           MemoryStore store) {
    this.jdbc = jdbc;
    this.vecCapability = vecCapability;
    this.dimension = properties.model().embeddingDimension();
    this.store = store;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void initialize() {
    if (!vecCapability.isLoaded()) {
      log.info("sqlite-vec unavailable; skipping memories_vec creation (vector search disabled).");
      return;
    }

    verifyDimensionOrFail();

    jdbc.sql("CREATE VIRTUAL TABLE IF NOT EXISTS memories_vec USING vec0("
      + "memory_id TEXT PRIMARY KEY, embedding FLOAT[" + dimension + "])").update();

    int backfilled = store.backfillVectors();
    log.info("memories_vec ready (dimension {}); backfilled {} existing vectors.", dimension, backfilled);
  }

  /**
   * If a {@code memories_vec} table already exists with a different {@code FLOAT[n]} width than the
   * configured dimension, fail fast: silently mixing widths would corrupt KNN results.
   */
  private void verifyDimensionOrFail() {
    Optional<String> existingDdl = jdbc.sql(
        "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'memories_vec'")
      .query(String.class)
      .optional();
    if (existingDdl.isEmpty() || existingDdl.get() == null) {
      return;
    }
    Matcher matcher = FLOAT_WIDTH.matcher(existingDdl.get());
    if (matcher.find()) {
      int existing = Integer.parseInt(matcher.group(1));
      if (existing != dimension) {
        throw new IllegalStateException(
          "Embedding dimension mismatch: memories_vec was created with FLOAT[" + existing
            + "] but pieria.model.embedding-dimension is " + dimension
            + ". Re-embedding at a new width is a deliberate operation; drop memories_vec and "
            + "re-vectorize, or restore the original dimension.");
      }
    }
  }
}
