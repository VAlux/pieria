package dev.alvo.pieria.onboarding;

import com.sun.net.httpserver.HttpServer;
import dev.alvo.pieria.onboarding.WebFetcher.FetchedPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests {@link HttpWebFetcher} against a throwaway localhost HTTP server: it extracts the readable
 * main text (stripping boilerplate), captures the title, and fails on a non-2xx response.
 */
class HttpWebFetcherTests {

  private HttpServer server;
  private final HttpWebFetcher fetcher = new HttpWebFetcher();

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.start();
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  private String url(String path) {
    return "http://127.0.0.1:" + server.getAddress().getPort() + path;
  }

  private void serve(String path, int status, String body) {
    server.createContext(path, exchange -> {
      byte[] out = body.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
      exchange.sendResponseHeaders(status, out.length == 0 ? -1 : out.length);
      try (var os = exchange.getResponseBody()) {
        os.write(out);
      }
    });
  }

  @Test
  void extractsMainTextAndTitleStrippingBoilerplate() {
    serve("/page", 200, """
      <html><head><title>My Page</title><style>.x{color:red}</style></head>
      <body>
        <nav>home about</nav>
        <script>console.log('tracking')</script>
        <p>The durable knowledge lives here.</p>
        <footer>copyright</footer>
      </body></html>
      """);

    FetchedPage page = fetcher.fetch(url("/page"));

    assertThat(page.title()).isEqualTo("My Page");
    assertThat(page.text()).contains("The durable knowledge lives here.");
    assertThat(page.text())
      .doesNotContain("tracking")   // script stripped
      .doesNotContain("home about") // nav stripped
      .doesNotContain("copyright"); // footer stripped
  }

  @Test
  void nonSuccessStatusThrows() {
    serve("/missing", 404, "nope");

    assertThatThrownBy(() -> fetcher.fetch(url("/missing")))
      .isInstanceOf(RuntimeException.class)
      .hasMessageContaining("404");
  }

  @Test
  void nonHttpSchemeIsRejected() {
    assertThatThrownBy(() -> fetcher.fetch("file:///etc/passwd"))
      .isInstanceOf(IllegalArgumentException.class);
  }
}
