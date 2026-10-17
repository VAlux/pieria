package dev.alvo.pieria.config;

import dev.alvo.pieria.tools.os.InstallHome;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;

/** Serves the separately built console without putting its assets in the daemon image. */
@Configuration
public class ConsoleResourceConfig implements WebMvcConfigurer {

  private final Path consoleDir;

  public ConsoleResourceConfig(@Value("${pieria.console.dir:}") String override) {
    if (override != null && !override.isBlank()) {
      consoleDir = Path.of(override).toAbsolutePath().normalize();
      return;
    }
    String configuredHome = System.getenv("PIERIA_HOME");
    Path installHome = configuredHome != null && !configuredHome.isBlank()
      ? Path.of(configuredHome)
      : executableInstallHome();
    if (installHome == null) {
      installHome = InstallHome.defaultHome(System::getenv, Path.of(System.getProperty("user.home")),
        System.getProperty("os.name", "").toLowerCase().contains("win"));
    }
    consoleDir = installHome.resolve("console").toAbsolutePath().normalize();
  }

  private static Path executableInstallHome() {
    return ProcessHandle.current().info().command().map(Path::of).map(command -> {
      String name = command.getFileName().toString();
      if (!name.equals("pieria-daemon") && !name.equals("pieria-daemon.exe")) return null;
      try {
        Path bin = command.toRealPath().getParent();
        return bin != null && bin.getFileName().toString().equals("bin") ? bin.getParent() : null;
      } catch (IOException ignored) {
        return null;
      }
    }).orElse(null);
  }

  @Override
  public void addResourceHandlers(ResourceHandlerRegistry registry) {
    String location = consoleDir.toUri().toString();
    if (!location.endsWith("/")) location += "/";
    registry.addResourceHandler("/console/assets/**")
      .addResourceLocations(location + "assets/")
      .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable());
    registry.addResourceHandler("/console/**").addResourceLocations(location);
  }

  public Path consoleDir() {
    return consoleDir;
  }
}
