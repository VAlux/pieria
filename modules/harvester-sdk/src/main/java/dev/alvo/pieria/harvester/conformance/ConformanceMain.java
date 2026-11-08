package dev.alvo.pieria.harvester.conformance;

import dev.alvo.pieria.harvester.manifest.ManifestException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Command-line front end, so a harvester in any language can be tested. */
public final class ConformanceMain {

  private static final int EXIT_PASSED = 0;
  private static final int EXIT_FAILED = 1;
  private static final int EXIT_USAGE = 2;
  private static final long DEFAULT_TIMEOUT_SECONDS = 60;
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String USAGE = "usage: harvester-conformance --manifest <file> --config <json file>"
    + " [--secrets <json file>] [--invalid-config <json file>] [--timeout-seconds <n>]";

  private ConformanceMain() {
  }

  public static void main(String[] args) {
    System.exit(run(args, System.out));
  }

  static int run(String[] args, PrintStream out) {
    try {
      Map<String, String> flags = flags(args);
      String manifest = flags.get("--manifest");
      String config = flags.get("--config");
      if (manifest == null || config == null) {
        out.println(USAGE);
        return EXIT_USAGE;
      }
      long timeout = flags.containsKey("--timeout-seconds")
        ? Long.parseLong(flags.get("--timeout-seconds")) : DEFAULT_TIMEOUT_SECONDS;
      ConformanceOptions options = new ConformanceOptions(readObject(config),
        flags.containsKey("--secrets") ? readSecrets(flags.get("--secrets")) : Map.of(),
        flags.containsKey("--invalid-config") ? readObject(flags.get("--invalid-config")) : null,
        Duration.ofSeconds(timeout));
      ConformanceReport report = ConformanceKit.run(ConformanceTarget.fromManifest(Path.of(manifest)), options);
      out.println(report.render());
      return report.passed() ? EXIT_PASSED : EXIT_FAILED;
    } catch (IOException | JacksonException | ManifestException | IllegalArgumentException e) {
      out.println("error: " + e.getMessage());
      out.println(USAGE);
      return EXIT_USAGE;
    }
  }

  private static Map<String, String> flags(String[] args) {
    if (args.length % 2 != 0) {
      throw new IllegalArgumentException("every option needs a value");
    }
    Map<String, String> flags = new HashMap<>();
    for (int i = 0; i < args.length; i += 2) {
      flags.put(args[i], args[i + 1]);
    }
    return flags;
  }

  private static ObjectNode readObject(String file) throws IOException {
    if (MAPPER.readTree(Files.readString(Path.of(file))) instanceof ObjectNode object) {
      return object;
    }
    throw new IllegalArgumentException("expected a JSON object in " + file);
  }

  private static Map<String, String> readSecrets(String file) throws IOException {
    Map<String, String> secrets = new LinkedHashMap<>();
    for (Map.Entry<String, JsonNode> entry : readObject(file).properties()) {
      if (!entry.getValue().isString()) {
        throw new IllegalArgumentException("secret values must be strings: " + entry.getKey());
      }
      secrets.put(entry.getKey(), entry.getValue().asString());
    }
    return secrets;
  }
}
