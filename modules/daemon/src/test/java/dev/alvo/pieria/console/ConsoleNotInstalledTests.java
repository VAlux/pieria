package dev.alvo.pieria.console;

import dev.alvo.pieria.api.controller.ConsoleController;
import dev.alvo.pieria.config.ConsoleResourceConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ConsoleController.class)
@Import(ConsoleResourceConfig.class)
class ConsoleNotInstalledTests {

  private static final Path MISSING_DIR = Path.of(System.getProperty("java.io.tmpdir"), "pieria-console-absent-" + System.nanoTime());

  @Autowired MockMvc mvc;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("pieria.console.dir", MISSING_DIR::toString);
  }

  @Test
  void explainsMissingInstallation() throws Exception {
    mvc.perform(get("/console/"))
      .andExpect(status().isNotFound())
      .andExpect(content().string(containsString("Pieria console not installed")))
      .andExpect(content().string(containsString("pieria update")));
  }

  @Test
  void clientRoutesWithoutInstallationAreNotFound() throws Exception {
    mvc.perform(get("/console/p/pieria")).andExpect(status().isNotFound());
  }
}
