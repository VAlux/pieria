package dev.alvo.pieria.tools;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads prompt templates from classpath resources under {@code prompts/<name>.txt} and
 * interpolates {@code {{placeholder}}} markers with caller-supplied values. Templates are the
 * verbatim prompt text sent to the model, so the files are the single source of truth for prompt
 * wording; the Java call sites only supply the dynamic values.
 *
 * <p>Fails loudly: a missing template resource or a placeholder without a supplied value throws
 * {@link IllegalArgumentException} — prompts are internal wiring, and a silent blank would only
 * surface as degraded model output. Placeholder values are inserted literally (no recursive
 * expansion), so user-controlled content can safely contain {@code {{...}}}. Loaded templates are
 * cached for the JVM lifetime; the files are static classpath resources.
 */
public final class PromptTemplateLoader {

  private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([a-zA-Z0-9._-]+)}}");

  private static final ConcurrentHashMap<String, String> CACHE = new ConcurrentHashMap<>();

  private PromptTemplateLoader() {
  }

  /**
   * Load the raw template named {@code name} (resource {@code prompts/<name>.txt}), uncached
   * placeholders intact.
   */
  public static String load(String name) {
    return CACHE.computeIfAbsent(name, PromptTemplateLoader::readResource);
  }

  /**
   * Load the template named {@code name} and replace every {@code {{key}}} marker with
   * {@code values.get(key)}. Every placeholder in the template must have a value; extra map
   * entries are ignored.
   */
  public static String render(String name, Map<String, String> values) {
    String template = load(name);
    Matcher matcher = PLACEHOLDER.matcher(template);
    StringBuilder rendered = new StringBuilder(template.length());
    while (matcher.find()) {
      String key = matcher.group(1);
      String value = values.get(key);
      if (value == null) {
        throw new IllegalArgumentException(
          "no value supplied for placeholder '" + key + "' in prompt template '" + name + "'");
      }
      matcher.appendReplacement(rendered, Matcher.quoteReplacement(value));
    }
    matcher.appendTail(rendered);
    return rendered.toString();
  }

  private static String readResource(String name) {
    String path = "prompts/" + name + ".txt";
    try (InputStream in = PromptTemplateLoader.class.getClassLoader().getResourceAsStream(path)) {
      if (in == null) {
        throw new IllegalArgumentException("prompt template resource not found on classpath: " + path);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("failed reading prompt template resource: " + path, e);
    }
  }
}
