package dev.alvo.pieria.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * sqlite-vec loadable-extension configuration.
 *
 * <p>The xerial SQLite driver bundles its own SQLite engine as a runtime JNI library, so sqlite-vec
 * cannot be statically linked into the GraalVM native image; it must be a loadable extension reached
 * via {@code load_extension(...)}. The distribution therefore ships the platform-specific
 * {@code vec0} extension next to the daemon binary/jar, and this property pins its location.
 *
 * @param extensionPath absolute path to the {@code vec0} extension file. Blank ⇒
 *                      {@link VecExtensionResolver} auto-resolves it (env var, then alongside the
 *                      running binary/jar); when nothing is found the daemon falls back to the OS
 *                      extension search path and, failing that, disables vector search.
 */
@ConfigurationProperties(prefix = "pieria.vec")
public record VecProperties(@DefaultValue("") String extensionPath) {
}
