package dev.alvo.pieria.code;

import io.github.treesitter.jtreesitter.NativeLibraryLookup;

import java.lang.foreign.Arena;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Points jtreesitter at the daemon's resolved <em>core</em> {@code libtree-sitter} library.
 *
 * <p>jtreesitter discovers this implementation via {@link java.util.ServiceLoader} (registered in
 * {@code META-INF/services/io.github.treesitter.jtreesitter.NativeLibraryLookup}) and chains it ahead
 * of its own default lookup. We cannot inject Spring state into a no-arg service instance, so the
 * absolute path is passed through the {@link #CORE_PATH_PROPERTY} system property, which
 * {@link TreeSitterEngine} sets (from {@link TreeSitterLibraryResolver}) <em>before</em> the first
 * jtreesitter call. When the property is unset the lookup is empty and jtreesitter falls through to
 * its default ({@code libtree-sitter} on the OS search path), so this never breaks a non-Pieria use.
 */
public final class PieriaTreeSitterLibraryLookup implements NativeLibraryLookup {

  /** System property carrying the absolute path to the core {@code libtree-sitter} library. */
  public static final String CORE_PATH_PROPERTY = "pieria.treesitter.core.path";

  @Override
  public SymbolLookup get(Arena arena) {
    String path = System.getProperty(CORE_PATH_PROPERTY);
    if (path == null || path.isBlank()) {
      return name -> Optional.empty();
    }
    return SymbolLookup.libraryLookup(Path.of(path), arena);
  }
}
