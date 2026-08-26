package dev.alvo.pieria.api.controller;

import dev.alvo.pieria.config.AppDataPathResolver;
import dev.alvo.pieria.config.GlobalConfigService;
import dev.alvo.pieria.config.schema.ConfigSchemaService;
import dev.alvo.pieria.config.toml.ConfigCodec;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Process-global configuration: the settings every profile inherits and no profile can override.
 *
 * <p>Unlike {@code ProfileConfigController} these do not live in the store — they are Spring
 * properties, bound once at startup and persisted in {@code pieria.properties} in the config
 * directory. {@code spring.config.import} reads that file only at boot, so a write never changes
 * the running daemon; the response reports every written key as restart-required (or locked, for
 * the few that also need an explicit acknowledgement).
 *
 * <p>{@code restartCommand} is served rather than hardcoded in the console: the browser cannot
 * restart the daemon, so the page hands the operator the command instead of offering a button
 * that would not do what it says.
 */
@RestController
@RequestMapping("/v1/config")
public class GlobalConfigController {

  private static final String RESTART_COMMAND = "pieria daemon restart";
  private static final String PROPERTIES_FILE = "pieria.properties";

  private final GlobalConfigService configService;
  private final ConfigSchemaService schemaService;
  private final AppDataPathResolver pathResolver;

  public GlobalConfigController(GlobalConfigService configService,
                                ConfigSchemaService schemaService,
                                AppDataPathResolver pathResolver) {
    this.configService = configService;
    this.schemaService = schemaService;
    this.pathResolver = pathResolver;
  }

  /**
   * Request body for a global write. A {@code null} value clears the key back to the shipped
   * default. {@code acknowledgeDestructive} is required for locked-tier keys.
   */
  public record GlobalConfigUpdate(Map<String, String> values, boolean acknowledgeDestructive) {
  }

  /** Every editable key across both scopes, so one fetch drives both console pages. */
  @GetMapping("/schema")
  public JsonNode schema() {
    return ConfigCodec.toNode(schemaService.all());
  }

  /** The effective global configuration, with provenance and pending-restart state per key. */
  @GetMapping
  public JsonNode get() {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("entries", configService.effective());
    body.put("configFile", pathResolver.resolve().configDir().resolve(PROPERTIES_FILE).toString());
    body.put("restartCommand", RESTART_COMMAND);
    return ConfigCodec.toNode(body);
  }

  /**
   * Apply a batch of global updates. All-or-nothing: an unknown key, a value that does not parse
   * for its kind, or an unacknowledged locked key rejects the whole request with 400 and leaves
   * the file untouched.
   */
  @PutMapping
  public JsonNode put(@RequestBody GlobalConfigUpdate body) {
    GlobalConfigService.ApplyResult result = configService.apply(
      body == null ? Map.of() : body.values(),
      body != null && body.acknowledgeDestructive());
    return ConfigCodec.toNode(result);
  }
}
