package dev.alvo.pieria.api.controller;

import dev.alvo.pieria.config.ConsoleResourceConfig;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

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

  /**
   * The console used to live at the root. Old bookmarks ({@code /}, {@code /index.html?view=wiki&profile=x&page=y})
   * keep working: the query string is carried over and the console maps legacy {@code ?view=} links onto its routes.
   */
  @GetMapping({"/", "/index.html"})
  public ResponseEntity<Void> legacyRoot(HttpServletRequest request) {
    String query = request.getQueryString();
    String location = query == null || query.isBlank() ? "/console/" : "/console/?" + query;
    return ResponseEntity.status(HttpStatus.FOUND).header("Location", location).build();
  }

  @GetMapping("/console/")
  public ResponseEntity<?> index() {
    Path index = resources.consoleDir().resolve("index.html");
    if (!Files.isRegularFile(index)) {
      return ResponseEntity.status(404).contentType(MediaType.TEXT_HTML).body(notInstalledPage());
    }
    return ResponseEntity.ok().contentType(MediaType.TEXT_HTML)
      .header("Cache-Control", "no-cache").body(new FileSystemResource(index));
  }

  private String notInstalledPage() {
    String dir = HtmlUtils.htmlEscape(resources.consoleDir().toString());
    return "<!doctype html><meta charset=\"utf-8\"><title>Pieria console not installed</title>"
      + "<h1>Pieria console not installed</h1>"
      + "<p>No console build was found at <code>" + dir + "</code>. "
      + "Reinstall Pieria or run <code>pieria update</code>. The API is still available under /v1.</p>";
  }
}
