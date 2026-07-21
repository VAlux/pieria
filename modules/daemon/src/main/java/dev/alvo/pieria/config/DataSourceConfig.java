package dev.alvo.pieria.config;


import com.zaxxer.hikari.HikariDataSource;
import dev.alvo.pieria.tools.io.FileOps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Builds the embedded SQLite {@link DataSource} from the resolved app-data database path, creating
 * the parent directory if needed and enabling WAL mode for concurrent reads. Flyway and the application share this datasource.
 *
 * <p>Each pooled connection also attempts a best-effort load of the {@code sqlite-vec}
 * extension. The xerial driver only permits {@code load_extension(...)} when
 * {@code enable_load_extension} was set on the connection, so we set that driver property and
 * then probe whether the native {@code vec0} module is actually available. Loading is strictly
 * best-effort: if the native extension is missing the application still starts and runs, with
 * vector features disabled (see {@link dev.alvo.pieria.storage.SqliteVectorIndex}).
 */
@Configuration
public class DataSourceConfig {

  private static final Logger log = LoggerFactory.getLogger(DataSourceConfig.class);

  /**
   * Holds whether the {@code sqlite-vec} native extension successfully loaded on this process.
   * Determined once on the first connection init and read by the vector startup component and
   * the store's capability check.
   */
  public static final class VecCapability {
    private volatile boolean loaded;

    public boolean isLoaded() {
      return loaded;
    }

    void markLoaded() {
      this.loaded = true;
    }
  }

  private static void ensureParentDirectory(String dbPath) {
    FileOps.ensureParentDirectory(Path.of(dbPath).toAbsolutePath());
  }

  /**
   * Shared capability flag describing whether {@code sqlite-vec} loaded. Exposed as a bean so the
   * vector startup component and the store can read it.
   */
  @Bean
  public VecCapability vecCapability() {
    return new VecCapability();
  }

  @Bean
  @Primary
  public DataSource dataSource(AppDataPathResolver pathResolver,
                               VecCapability vecCapability,
                               VecExtensionResolver vecResolver) {
    String path = pathResolver.resolve().databaseFile().toString();
    ensureParentDirectory(path);

    // enable_load_extension is a xerial connection property: without it load_extension() throws.
    // busy_timeout makes a connection wait (up to N ms) for a held write lock instead of failing
    // immediately with SQLITE_BUSY — SQLite is single-writer, so the vectorization worker would
    // otherwise collide with concurrent ingestion / code-index write transactions. IMMEDIATE makes
    // xerial start Spring-managed transactions with BEGIN IMMEDIATE, reserving the writer before a
    // transaction's first read. Without it, a keyed-memory SELECT can open a deferred read snapshot,
    // another connection can commit vectorization, and the later memory INSERT fails with
    // SQLITE_BUSY_SNAPSHOT because the stale snapshot cannot be upgraded. Both settings apply to
    // every pooled connection.
    String url = "jdbc:sqlite:" + path
      + "?enable_load_extension=true&busy_timeout=5000&transaction_mode=IMMEDIATE";
    HikariDataSource dataSource = DataSourceBuilder.create()
      .type(HikariDataSource.class)
      .driverClassName("org.sqlite.JDBC")
      .url(url)
      .build();

    // Decide the per-connection init SQL before the pool starts (Hikari seals its config on the
    // first getConnection). SQLite is single-writer; WAL lets readers proceed during a write.
    configureConnectionInit(dataSource, url, vecCapability, vecResolver.resolve());
    return dataSource;
  }

  /**
   * Probe a raw (non-pooled) connection for {@code sqlite-vec}, then install the per-connection
   * init SQL on the not-yet-started pool.
   *
   * <p>{@code load_extension} is <em>connection-scoped</em> in the xerial driver — the module is NOT
   * shared process-wide — so the winning {@code load_extension(...)} invocation is installed as
   * Hikari's {@code connectionInitSql}, making every pooled connection load {@code vec0} on
   * creation. Without this, queries against {@code memories_vec} fail with {@code no such module:
   * vec0} whenever they land on a connection other than the one that did the load.
   *
   * <p>We probe over a separate {@link DriverManager} connection rather than {@code
   * dataSource.getConnection()} because the latter starts the pool and seals its configuration,
   * which would then reject {@code setConnectionInitSql}. {@code PRAGMA journal_mode=WAL} is run on
   * the probe connection; it is a persistent database-level setting, so it sticks for the pool too.
   *
   * <p>When {@code bundledExtension} is present it is tried first by absolute path; this is the path that works on a clean
   * install with no system-wide sqlite-vec. The bare entry-point names remain as a fallback for
   * developer machines that have the library on the OS extension search path. When no extension is
   * available the pool still asserts WAL on every connection and vector search stays disabled.
   */
  private static void configureConnectionInit(HikariDataSource dataSource,
                                              String url,
                                              VecCapability cap,
                                              Optional<Path> bundledExtension) {
    String walPragma = "PRAGMA journal_mode=WAL";
    try (Connection conn = DriverManager.getConnection(url); Statement st = conn.createStatement()) {
      st.execute(walPragma);
      String loadSql = firstLoadableSql(st, bundledExtension);
      if (loadSql != null && probeVec(st)) {
        dataSource.setConnectionInitSql(loadSql);
        cap.markLoaded();
        log.info("sqlite-vec extension loaded ({}); embedded vector search enabled.", loadSql);
        return;
      }
      log.warn("sqlite-vec extension not available; vector search disabled "
        + "(FTS + keyed lookup still work). Bundle vec0 beside the binary or set "
        + "pieria.vec.extension-path / PIERIA_VEC_EXTENSION to enable it.");
    } catch (Exception e) {
      log.warn("sqlite-vec extension could not be loaded ({}); vector search disabled.", e.toString());
    }
    // No vector capability: keep asserting WAL on every pooled connection.
    dataSource.setConnectionInitSql(walPragma);
  }

  /**
   * Return the first {@code SELECT load_extension(...)} statement that succeeds, or {@code null}.
   * The bundled absolute path is preferred; bare entry-point names and the explicit init symbol are
   * fallbacks for developer machines with the library on the OS extension search path.
   */
  private static String firstLoadableSql(Statement st, Optional<Path> bundledExtension) {
    List<String> argLists = new ArrayList<>();
    bundledExtension.ifPresent(p -> argLists.add("'" + p + "'"));
    argLists.add("'vec0'");
    argLists.add("'vec'");
    argLists.add("'sqlite_vec'");
    argLists.add("'vec0', 'sqlite3_vec_init'");
    for (String args : argLists) {
      String sql = "SELECT load_extension(" + args + ")";
      if (tryExecute(st, sql)) {
        return sql;
      }
    }
    return null;
  }

  private static boolean tryExecute(Statement st, String sql) {
    try {
      st.execute(sql);
      return true;
    } catch (Exception ignored) {
      return false;
    }
  }

  /** Confirm the vec module is actually usable by reading its version function. */
  private static boolean probeVec(Statement st) {
    try (var rs = st.executeQuery("SELECT vec_version()")) {
      return rs.next();
    } catch (Exception ignored) {
      return false;
    }
  }
}
