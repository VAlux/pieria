package dev.alvo.pieria.config;

import dev.alvo.pieria.tools.os.InstallHome;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

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
    registry.addResourceHandler("/console/**")
      .addResourceLocations(location)
      .setCacheControl(CacheControl.noCache())
      .resourceChain(true)
      .addResolver(new ClientRouteResolver());
  }

  /**
   * Console routes such as {@code /console/p/pieria/memories} are client-side, so a refresh or a
   * pasted link has no file behind it. Extensionless paths fall back to {@code index.html}; a
   * missing file with an extension stays a 404 so a broken asset reference is not masked by HTML.
   */
  private static final class ClientRouteResolver extends PathResourceResolver {
    @Override
    protected Resource getResource(String resourcePath, Resource location) throws IOException {
      Resource resource = super.getResource(resourcePath, location);
      if (resource != null) return resource;
      String lastSegment = resourcePath.substring(resourcePath.lastIndexOf('/') + 1);
      return lastSegment.contains(".") ? null : super.getResource("index.html", location);
    }
  }

  public Path consoleDir() {
    return consoleDir;
  }
}
