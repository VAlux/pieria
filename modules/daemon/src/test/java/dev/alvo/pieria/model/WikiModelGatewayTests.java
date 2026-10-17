package dev.alvo.pieria.model;

import com.sun.net.httpserver.HttpServer;
import dev.alvo.pieria.config.PieriaProperties;
import dev.alvo.pieria.config.WikiProperties;
import dev.alvo.pieria.model.provider.OllamaModelProviderAdapter;
import dev.alvo.pieria.wiki.WikiData.Bundle;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class WikiModelGatewayTests {
  @Test void providerFailureMakesOneTransportAttemptSoTheQueueOwnsRetryBudget() throws Exception {
    var calls = new AtomicInteger();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1/chat/completions", exchange -> {
      calls.incrementAndGet();
      byte[] body = "{\"error\":{\"message\":\"fixture unavailable\",\"type\":\"server_error\"}}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(500, body.length);
      try (var output = exchange.getResponseBody()) { output.write(body); }
    });
    server.start();
    try {
      var provider = new PieriaProperties.Provider("http://127.0.0.1:" + server.getAddress().getPort(), "fixture", "fixture", "openai", null);
      var properties = new PieriaProperties(null, null, provider,
        new PieriaProperties.Model("small", "large", "embed", 3, 1, null, null), null, null, null);
      var adapter = new OllamaModelProviderAdapter();
      var client = new ModelGatewayConfig().wikiChatClient(properties, adapter, WikiProperties.DEFAULT);
      var gateway = new OpenAiModelGateway(null, null, null, properties, adapter, client);
      var bundle = new Bundle("page", "Topic", 1, 1, "fingerprint", "rendering", null, 0,
        List.of(), List.of(), List.of(), 0, 0, 100, null);
      assertThrows(RuntimeException.class, () -> gateway.synthesizeWiki(bundle));
      assertEquals(1, calls.get());
    } finally {
      server.stop(0);
    }
  }
}
