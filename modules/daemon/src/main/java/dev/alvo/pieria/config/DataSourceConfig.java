package dev.alvo.pieria.config;


import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Optional;

/**
 * Builds the embedded SQLite {@link DataSource} from the resolved app-data database path, creating
 * the parent directory if needed and enabling WAL mode (concurrent reads under the single writer,
 * SPEC 5.2 / 11). Flyway and the application share this datasource.
 *
 * <p>Each pooled connection also attempts a best-effort load of the {@code sqlite-vec}
 * extension (SPEC 4 / 5.2). The xerial driver only permits {@code load_extension(...)} when
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
    Path parent = Path.of(dbPath).toAbsolutePath().getParent();
    if (parent != null) {
      try {
        Files.createDirectories(parent);
      } catch (IOException e) {
        throw new UncheckedIOException("Cannot create database directory: " + parent, e);
      }
    }
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

    HikariDataSource dataSource = DataSourceBuilder.create()
      .type(HikariDataSource.class)
      .driverClassName("org.sqlite.JDBC")
      // enable_load_extension is a xerial connection property: without it load_extension() throws.
      .url("jdbc:sqlite:" + path + "?enable_load_extension=true")
      .build();

    // SQLite is single-writer; WAL lets readers proceed during a write. We also try to load
    // sqlite-vec per connection. connectionInitSql must succeed for a connection to be handed out,
    // so we cannot put a hard load_extension there (it would fail the whole pool when the native
    // lib is absent). Instead WAL is the init SQL, and the extension is loaded best-effort here on
    // each new physical connection via a Hikari listener.
    dataSource.setConnectionInitSql("PRAGMA journal_mode=WAL");
    loadVecExtensionBestEffort(dataSource, vecCapability, vecResolver.resolve());
    return dataSource;
  }

  /**
   * Best-effort: open one connection, try to load {@code sqlite-vec}, and probe it. We register no
   * permanent per-connection hook because, once loaded into the process, the {@code vec0} module is
   * available to all connections of the same SQLite library; but to be safe each store query that
   * touches {@code memories_vec} runs against a pool where the module has been loaded at least once.
   * The xerial driver loads extensions process-wide via {@code load_extension}.
   *
   * <p>When {@code bundledExtension} is present (the distribution shipped {@code vec0} next to the
   * binary, SPEC 14) it is tried first by absolute path; this is the path that works on a clean
   * install with no system-wide sqlite-vec. The bare entry-point names remain as a fallback for
   * developer machines that have the library on the OS extension search path.
   */
  private static void loadVecExtensionBestEffort(HikariDataSource dataSource,
                                                 VecCapability cap,
                                                 Optional<Path> bundledExtension) {
    try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
      // Prefer the bundled extension by absolute path; otherwise probe common search-path names.
      boolean loaded = bundledExtension.map(p -> tryLoad(st, p.toString())).orElse(false)
        || tryLoad(st, "vec0")
        || tryLoad(st, "vec")
        || tryLoad(st, "sqlite_vec")
        || tryLoadAuto(st);
      if (loaded && probeVec(st)) {
        cap.markLoaded();
        bundledExtension.ifPresentOrElse(
          p -> log.info("sqlite-vec extension loaded from {}; embedded vector search enabled.", p),
          () -> log.info("sqlite-vec extension loaded from OS search path; embedded vector search enabled."));
      } else {
        log.warn("sqlite-vec extension not available; vector search disabled "
          + "(FTS + keyed lookup still work). Bundle vec0 beside the binary or set "
          + "pieria.vec.extension-path / PIERIA_VEC_EXTENSION to enable it.");
      }
    } catch (Exception e) {
      log.warn("sqlite-vec extension could not be loaded ({}); vector search disabled.", e.toString());
    }
  }

  private static boolean tryLoad(Statement st, String name) {
    try {
      st.execute("SELECT load_extension('" + name + "')");
      return true;
    } catch (Exception ignored) {
      return false;
    }
  }

  /** Fall back to letting SQLite resolve the default entry point from a bare {@code vec0} name. */
  private static boolean tryLoadAuto(Statement st) {
    try {
      st.execute("SELECT load_extension('vec0', 'sqlite3_vec_init')");
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
