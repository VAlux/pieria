package dev.alvo.pieria.harvesters.html;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/** Decides which URLs a crawl covers, and gives each page one spelling of its URL. */
public final class CrawlScope {

  /** Keeps a full site's URLs inside the protocol's cursor limit. */
  static final int MAX_URL_CHARS = 400;

  private final CrawlConfig config;

  public CrawlScope(CrawlConfig config) {
    this.config = config;
  }

  /** The canonical form of {@code url}, or null when the crawler can't or shouldn't fetch it. */
  public static String normalize(String url) {
    if (url == null) {
      return null;
    }
    try {
      URI uri = new URI(url.strip().replace(" ", "%20"));
      String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
      if (!(scheme.equals("http") || scheme.equals("https")) || uri.getHost() == null || uri.getRawUserInfo() != null) {
        return null;
      }
      boolean defaultPort = uri.getPort() == -1 || uri.getPort() == (scheme.equals("http") ? 80 : 443);
      String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
      String normalized = new URI(scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT) + (defaultPort ? "" : ":" + uri.getPort())
        + path + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery())).toASCIIString();
      return normalized.length() <= MAX_URL_CHARS ? normalized : null;
    } catch (URISyntaxException e) {
      return null;
    }
  }

  public boolean allows(String normalizedUrl) {
    return hostAllowed(URI.create(normalizedUrl).getHost())
      && (config.include().isEmpty() || config.include().stream().anyMatch(matching(normalizedUrl)))
      && config.exclude().stream().noneMatch(matching(normalizedUrl));
  }

  private static Predicate<Pattern> matching(String url) {
    return pattern -> pattern.matcher(url).find();
  }

  private boolean hostAllowed(String host) {
    return config.allowedHosts().stream().anyMatch(allowed ->
      allowed.startsWith("*.") ? host.endsWith(allowed.substring(1)) : host.equals(allowed));
  }
}
