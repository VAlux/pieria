package dev.alvo.pieria.code;

import java.util.Locale;
import java.util.Map;

/**
 * Maps a file path to a language-pack id by extension. Polyglot by construction — adding a language
 * is one registry entry plus a {@link CodeParser} that {@code supports} it. Unknown extensions
 * return {@code ""} (the file is still indexed, with no symbols).
 */
public final class LanguageDetector {

  private static final Map<String, String> BY_EXTENSION = LanguagePackRegistry.byExtension();

  private LanguageDetector() {
  }

  /**
   * Language-pack id for a repo-relative path, or {@code ""} when the extension is unknown.
   */
  public static String detect(String repoRelPath) {
    if (repoRelPath == null) {
      return "";
    }
    int dot = repoRelPath.lastIndexOf('.');
    int slash = Math.max(repoRelPath.lastIndexOf('/'), repoRelPath.lastIndexOf('\\'));
    if (dot < 0 || dot < slash || dot == repoRelPath.length() - 1) {
      return "";
    }
    String ext = repoRelPath.substring(dot + 1).toLowerCase(Locale.ROOT);
    return BY_EXTENSION.getOrDefault(ext, "");
  }
}
