package dev.alvo.pieria.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads, edits and rewrites a {@code .properties} file one line at a time.
 *
 * <p>{@code pieria.properties} is materialized from a heavily commented template that users are
 * expected to edit by hand, so {@link java.util.Properties#store} is not usable here: it discards
 * every comment and reorders the file. This editor rewrites only the lines it owns — an existing
 * key is replaced where it sits, a new one is appended under {@link #MANAGED_HEADER}, and
 * everything else survives byte for byte.
 *
 * <p>Not thread-safe. The daemon is a single writer; callers read, edit and write in one go.
 */
public final class PropertiesFileEditor {

  /** Section the editor appends newly-introduced keys under, so hand edits stay separable. */
  public static final String MANAGED_HEADER = "# --- Written by the Pieria console ---";

  private final List<String> lines;

  private PropertiesFileEditor(List<String> lines) {
    this.lines = lines;
  }

  /** Read a properties file. A missing file reads as empty rather than failing. */
  public static PropertiesFileEditor read(Path file) {
    if (file == null || !Files.isRegularFile(file)) {
      return new PropertiesFileEditor(new ArrayList<>());
    }
    try {
      return new PropertiesFileEditor(new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8)));
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read " + file, e);
    }
  }

  /**
   * The value assigned to {@code key}, ignoring commented-out lines. When a key appears multiple
   * times (a hand-maintained file may have accidental duplicates), this returns the value the
   * daemon would load — the last occurrence, which is how {@link java.util.Properties} resolves
   * duplicates via {@link java.util.Properties#load}.
   */
  public Optional<String> get(String key) {
    List<Integer> indices = indicesOf(key);
    if (indices.isEmpty()) {
      return Optional.empty();
    }
    int at = indices.get(indices.size() - 1);  // last occurrence
    String line = lines.get(at);
    int separator = separatorIndex(line, key);
    return Optional.of(line.substring(separator + 1).trim());
  }

  /**
   * Assign {@code key}, replacing an existing assignment in place or appending a new one. When
   * duplicates are present (a hand-maintained file may have accidental duplicates), this collapses
   * them — the first occurrence is rewritten with the new value, and any later duplicates are
   * removed. This matches the daemon's loading behaviour: a duplicate that is not the last
   * occurrence is dead code.
   */
  public void set(String key, String value) {
    List<Integer> indices = indicesOf(key);
    String assignment = key + "=" + (value == null ? "" : value);
    if (!indices.isEmpty()) {
      // Remove later duplicates in reverse order to preserve indices.
      for (int i = indices.size() - 1; i > 0; i--) {
        lines.remove((int) indices.get(i));
      }
      // Rewrite the first (now the only) occurrence.
      lines.set(indices.get(0), assignment);
      return;
    }
    if (!lines.contains(MANAGED_HEADER)) {
      if (!lines.isEmpty()) {
        lines.add("");
      }
      lines.add(MANAGED_HEADER);
    }
    lines.add(assignment);
  }

  /**
   * Drop {@code key} entirely, so the daemon's shipped default applies again. When duplicates are
   * present, removes all occurrences so the key cannot re-emerge from a later duplicate. Idempotent.
   */
  public void remove(String key) {
    List<Integer> indices = indicesOf(key);
    // Remove in reverse order to preserve indices as we delete.
    for (int i = indices.size() - 1; i >= 0; i--) {
      lines.remove((int) indices.get(i));
    }
  }

  /**
   * Write the file atomically: a config the daemon imports at startup must never be observed
   * half-written, so the content lands in a sibling temp file and is moved into place.
   */
  public void write(Path file) {
    try {
      Path parent = file.toAbsolutePath().getParent();
      // getParent() is null only at filesystem roots; no config file lives there.
      Files.createDirectories(parent);
      Path temp = Files.createTempFile(parent, "pieria-properties", ".tmp");
      Files.write(temp, lines, StandardCharsets.UTF_8);
      try {
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } catch (IOException atomicUnsupported) {
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot write " + file, e);
    }
  }

  private List<Integer> indicesOf(String key) {
    List<Integer> result = new ArrayList<>();
    Pattern pattern = Pattern.compile("^\\s*" + Pattern.quote(key) + "\\s*[=:]");
    for (int i = 0; i < lines.size(); i++) {
      String line = lines.get(i);
      String trimmed = line.stripLeading();
      if (trimmed.startsWith("#") || trimmed.startsWith("!")) {
        continue;
      }
      Matcher matcher = pattern.matcher(line);
      if (matcher.find()) {
        result.add(i);
      }
    }
    return result;
  }

  private static int separatorIndex(String line, String key) {
    int from = line.indexOf(key) + key.length();
    for (int i = from; i < line.length(); i++) {
      char c = line.charAt(i);
      if (c == '=' || c == ':') {
        return i;
      }
    }
    return line.length() - 1;
  }
}
