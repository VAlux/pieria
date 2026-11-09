package dev.alvo.pieria.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Refuses requests a web page could have sent to a daemon listening on loopback. Any site open in the
 * user's browser can aim requests at localhost, and this API installs and runs programs. A DNS-rebound
 * name still arrives in {@code Host}, and a cross-site request carries {@code Origin}, so both must
 * name a loopback address. Clients outside a browser send no {@code Origin}.
 *
 * <p>Applies to every path, so no route can be missed, and only while the daemon is bound to loopback.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LoopbackRequestFilter extends OncePerRequestFilter {

  private static final String REFUSAL =
    "{\"error\":\"forbidden\",\"message\":\"this daemon only accepts requests addressed to its loopback name\"}";

  private final boolean enforced;

  public LoopbackRequestFilter(@Value("${pieria.daemon.host:127.0.0.1}") String boundHost) {
    this.enforced = isLoopback(boundHost.strip());
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
    throws ServletException, IOException {
    if (enforced && !(hostAllowed(request.getHeader("Host")) && originAllowed(request.getHeader("Origin")))) {
      response.setStatus(HttpServletResponse.SC_FORBIDDEN);
      response.setContentType(MediaType.APPLICATION_JSON_VALUE);
      response.setCharacterEncoding(StandardCharsets.UTF_8);
      response.getWriter().write(REFUSAL);
      return;
    }
    chain.doFilter(request, response);
  }

  private static boolean hostAllowed(String header) {
    return header == null || isLoopback(withoutPort(header.strip()));
  }

  private static boolean originAllowed(String header) {
    if (header == null) {
      return true;
    }
    try {
      String host = new URI(header.strip()).getHost();
      return host != null && isLoopback(host);
    } catch (URISyntaxException e) {
      return false;
    }
  }

  private static String withoutPort(String authority) {
    int end = authority.startsWith("[") ? authority.indexOf(']') + 1 : authority.indexOf(':');
    return end <= 0 ? authority : authority.substring(0, end);
  }

  private static boolean isLoopback(String host) {
    String name = host.toLowerCase(Locale.ROOT);
    if (name.startsWith("[") && name.endsWith("]")) {
      name = name.substring(1, name.length() - 1);
    }
    return name.equals("localhost") || name.equals("::1") || name.equals("0:0:0:0:0:0:0:1") || name.matches("127(\\.\\d{1,3}){3}");
  }
}
