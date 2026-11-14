package dev.alvo.pieria.harvesters.atlassian;

import dev.alvo.pieria.harvester.HarvesterException;
import dev.alvo.pieria.harvesters.ConfigValues;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * An Atlassian site and how to sign in to it.
 *
 * @param baseUrl       the address requests are resolved against, with no trailing slash
 * @param authorization the {@code Authorization} header value
 */
public record AtlassianSite(String baseUrl, Deployment deployment, String authorization) {

  private static final List<String> DEPLOYMENTS = List.of("auto", "cloud", "datacenter");

  /**
   * Reads {@code baseUrl}, {@code deployment}, {@code email} and the {@code token} secret.
   *
   * @param cloudContextPath appended to a Cloud address that has no path, such as {@code /wiki}
   */
  public static AtlassianSite parse(ObjectNode config, Map<String, String> secrets, String cloudContextPath) throws HarvesterException {
    URI uri = baseUri(ConfigValues.requiredString(config, "baseUrl"));
    String token = ConfigValues.requiredSecret(secrets, "token");
    String email = ConfigValues.string(config, "email");
    Deployment deployment = switch (ConfigValues.choice(config, "deployment", "auto", DEPLOYMENTS)) {
      case "cloud" -> Deployment.CLOUD;
      case "datacenter" -> Deployment.DATA_CENTER;
      default -> uri.getHost().toLowerCase(Locale.ROOT).endsWith(".atlassian.net") ? Deployment.CLOUD : Deployment.DATA_CENTER;
    };
    String path = uri.getRawPath() == null ? "" : uri.getRawPath().replaceAll("/+$", "");
    if (deployment == Deployment.CLOUD) {
      if (email == null) {
        throw ConfigValues.invalid("email is required for Atlassian Cloud");
      }
      if (path.isEmpty()) {
        path = cloudContextPath;
      }
    }
    String authorization = email == null
      ? "Bearer " + token
      : "Basic " + Base64.getEncoder().encodeToString((email + ":" + token).getBytes(StandardCharsets.UTF_8));
    return new AtlassianSite(uri.getScheme().toLowerCase(Locale.ROOT) + "://" + uri.getRawAuthority() + path, deployment, authorization);
  }

  private static URI baseUri(String value) throws HarvesterException {
    try {
      URI uri = new URI(value);
      String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
      if (!(scheme.equals("http") || scheme.equals("https")) || uri.getHost() == null || uri.getRawQuery() != null
        || uri.getRawFragment() != null || uri.getRawUserInfo() != null) {
        throw ConfigValues.invalid("baseUrl must be an http or https address with no query, such as https://acme.atlassian.net");
      }
      return uri;
    } catch (URISyntaxException e) {
      throw ConfigValues.invalid("baseUrl is not a valid address: " + value);
    }
  }

  @Override
  public String toString() {
    return "AtlassianSite[baseUrl=" + baseUrl + ", deployment=" + deployment + "]";
  }
}
