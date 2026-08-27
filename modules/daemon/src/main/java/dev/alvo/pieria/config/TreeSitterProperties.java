package dev.alvo.pieria.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Tree-sitter native-library configuration for the code-index parser.
 *
 * <p>jtreesitter is an FFM/Panama binding that needs native shared libraries reached at runtime:
 * the <em>core</em> {@code libtree-sitter} runtime and per-language <em>grammars</em> (e.g.
 * {@code tree-sitter-java}). Like sqlite-vec they are {@code dlopen}'d (cannot be static-linked into
 * the native image), so the distribution bundles them under {@code native/<os>-<arch>/}. Language
 * grammars are always loaded from those embedded resources; only the core runtime can be overridden.
 *
 * @param enabled  master switch; {@code false} disables Tree-sitter parsing entirely (the
 *                 code index then degrades to file/module/dependency facts, as before).
 * @param corePath absolute path to {@code libtree-sitter.*}; blank ⇒ auto-resolve.
 */
@ConfigurationProperties(prefix = "pieria.treesitter")
public record TreeSitterProperties(@DefaultValue("true") boolean enabled,
                                   @DefaultValue("") String corePath) {
}
