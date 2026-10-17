package dev.alvo.pieria.console;

import dev.alvo.pieria.api.controller.ConsoleController;
import dev.alvo.pieria.config.ConsoleResourceConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ConsoleController.class)
@Import(ConsoleResourceConfig.class)
class ConsoleResourceTests {

  private static final Path CONSOLE_DIR = tempDir();

  @Autowired MockMvc mvc;

  private static Path tempDir() {
    try { return Files.createTempDirectory("pieria-console-test-"); }
    catch (IOException e) { throw new ExceptionInInitializerError(e); }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("pieria.console.dir", CONSOLE_DIR::toString);
  }

  @BeforeAll
  static void assets() throws IOException {
    Files.createDirectories(CONSOLE_DIR.resolve("assets"));
    Files.writeString(CONSOLE_DIR.resolve("index.html"), "<!doctype html><title>Console test</title>");
    Files.writeString(CONSOLE_DIR.resolve("assets/app.js"), "console.log('loaded');");
  }

  @AfterAll
  static void cleanup() throws IOException {
    Files.deleteIfExists(CONSOLE_DIR.resolve("assets/app.js"));
    Files.deleteIfExists(CONSOLE_DIR.resolve("assets"));
    Files.deleteIfExists(CONSOLE_DIR.resolve("index.html"));
    Files.deleteIfExists(CONSOLE_DIR);
  }

  @Test
  void servesEntryAndAssetsFromExternalDirectory() throws Exception {
    mvc.perform(get("/console/"))
      .andExpect(status().isOk())
      .andExpect(header().string("Cache-Control", "no-cache"))
      .andExpect(content().string("<!doctype html><title>Console test</title>"));
    mvc.perform(get("/console/assets/app.js"))
      .andExpect(status().isOk())
      .andExpect(content().string("console.log('loaded');"));
  }

  @Test
  void redirectsPathWithoutTrailingSlash() throws Exception {
    mvc.perform(get("/console"))
      .andExpect(status().isPermanentRedirect())
      .andExpect(header().string("Location", "/console/"));
  }
}
