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

  /** The value assigned to {@code key}, ignoring commented-out lines. */
  public Optional<String> get(String key) {
    int at = indexOf(key);
    if (at < 0) {
      return Optional.empty();
    }
    String line = lines.get(at);
    int separator = separatorIndex(line, key);
    return Optional.of(line.substring(separator + 1).trim());
  }

  /** Assign {@code key}, replacing an existing assignment in place or appending a new one. */
  public void set(String key, String value) {
    int at = indexOf(key);
    String assignment = key + "=" + (value == null ? "" : value);
    if (at >= 0) {
      lines.set(at, assignment);
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

  /** Drop {@code key} entirely, so the daemon's shipped default applies again. Idempotent. */
  public void remove(String key) {
    int at = indexOf(key);
    if (at >= 0) {
      lines.remove(at);
    }
  }

  /**
   * Write the file atomically: a config the daemon imports at startup must never be observed
   * half-written, so the content lands in a sibling temp file and is moved into place.
   */
  public void write(Path file) {
    try {
      Path parent = file.toAbsolutePath().getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
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

  private int indexOf(String key) {
    Pattern pattern = Pattern.compile("^\\s*" + Pattern.quote(key) + "\\s*[=:]");
    for (int i = 0; i < lines.size(); i++) {
      String line = lines.get(i);
      String trimmed = line.stripLeading();
      if (trimmed.startsWith("#") || trimmed.startsWith("!")) {
        continue;
      }
      Matcher matcher = pattern.matcher(line);
      if (matcher.find()) {
        return i;
      }
    }
    return -1;
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
