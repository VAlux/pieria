package dev.alvo.pieria.api.controller;

import dev.alvo.pieria.config.ConsoleResourceConfig;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;

/** The console entry point; /v1 requests remain exclusively daemon API routes. */
@RestController
public class ConsoleController {

  private final ConsoleResourceConfig resources;

  public ConsoleController(ConsoleResourceConfig resources) {
    this.resources = resources;
  }

  @GetMapping("/console")
  public ResponseEntity<Void> redirect() {
    return ResponseEntity.status(308).header("Location", "/console/").build();
  }

  @GetMapping("/console/")
  public ResponseEntity<FileSystemResource> index() {
    Path index = resources.consoleDir().resolve("index.html");
    if (!Files.isRegularFile(index)) return ResponseEntity.notFound().build();
    return ResponseEntity.ok().contentType(MediaType.TEXT_HTML)
      .header("Cache-Control", "no-cache").body(new FileSystemResource(index));
  }
}
