package dev.alvo.pieria.harvest;

import dev.alvo.pieria.harvester.manifest.HarvesterManifest;
import dev.alvo.pieria.harvester.manifest.ManifestParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The harvesters that ship with Pieria. Their manifests are built into the daemon and their code is
 * the {@code pieria-harvest} binary beside it. The ids are listed because a native image can't
 * enumerate its resources.
 */
public final class BundledHarvesters {

  public static final List<String> IDS = List.of("html", "jira");

  private BundledHarvesters() {
  }

  public static boolean isBundled(String id) {
    return IDS.contains(id);
  }

  public static String resourcePath(String id) {
    return "bundled-harvesters/" + id + "/harvester.toml";
  }

  public static HarvesterManifest manifest(String id) {
    try (InputStream in = BundledHarvesters.class.getClassLoader().getResourceAsStream(resourcePath(id))) {
      if (in == null) {
        throw new IllegalStateException("no manifest is built in for harvester " + id);
      }
      return ManifestParser.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read the manifest of harvester " + id, e);
    }
  }
}
