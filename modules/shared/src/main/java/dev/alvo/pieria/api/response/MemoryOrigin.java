package dev.alvo.pieria.api.response;

/**
 * Where a harvested memory came from. Absent for memories from the user's own sessions.
 *
 * @param sourceId   the configured source's id
 * @param harvester  the harvester type, e.g. {@code "jira"}
 * @param externalId the item's id at the source, e.g. {@code "ACME-12"}
 * @param url        link to the item; may be null
 * @param trust      trust level; currently always {@link #UNTRUSTED}
 */
public record MemoryOrigin(String sourceId, String harvester, String externalId, String url, String trust) {

  public static final String SOURCE_ID_REGEX = "^[a-z0-9][a-z0-9_-]{0,63}$";
  public static final String UNTRUSTED = "untrusted";

  private static final int MAX_TRUST_CHARS = 20;
  private static final int MAX_HARVESTER_CHARS = 40;
  private static final int MAX_EXTERNAL_ID_CHARS = 120;
  private static final int MAX_URL_CHARS = 300;

  /**
   * The label shown beside this memory in recall output, always one bounded line. Every field but
   * {@code trust} comes from the harvester, so it is cleaned rather than trusted to be well formed.
   */
  public String renderLabel() {
    StringBuilder label = new StringBuilder(clean(trust == null ? UNTRUSTED : trust, MAX_TRUST_CHARS))
      .append(" · ").append(clean(harvester, MAX_HARVESTER_CHARS))
      .append(" · ").append(clean(externalId, MAX_EXTERNAL_ID_CHARS));
    String link = clean(url, MAX_URL_CHARS);
    if (!link.isEmpty()) {
      label.append(" · ").append(link);
    }
    return label.toString();
  }

  /**
   * Collapses control, format and separator characters to single spaces, so third-party text can't
   * start a new line in a prompt and pose as an entry of its own.
   */
  public static String oneLine(String value) {
    return value.replaceAll("[\\p{C}\\p{Z}]+", " ").strip();
  }

  /**
   * {@link #oneLine} and bounded, with brackets swapped for parentheses so a field can't close the
   * label's {@code [...]}.
   */
  private static String clean(String value, int maxChars) {
    if (value == null) {
      return "";
    }
    String cleaned = oneLine(value).replace('[', '(').replace(']', ')');
    return cleaned.length() <= maxChars ? cleaned : cleaned.substring(0, maxChars);
  }
}
