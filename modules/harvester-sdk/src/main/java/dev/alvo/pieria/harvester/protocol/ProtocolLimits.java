package dev.alvo.pieria.harvester.protocol;

/** Size limits of protocol v1. A record over any of them is a protocol violation. */
public final class ProtocolLimits {

  public static final int MAX_LINE_BYTES = 16 * 1024 * 1024;
  public static final int MAX_ENVELOPE_BYTES = 1024 * 1024;
  /** A document's text, or the sum of the text across a thread's turns. */
  public static final int MAX_TEXT_CHARS = 2_000_000;
  public static final int MAX_ID_CHARS = 512;
  public static final int MAX_TITLE_CHARS = 1_000;
  public static final int MAX_URL_CHARS = 2_048;
  public static final int MAX_AUTHOR_CHARS = 256;
  public static final int MAX_TURNS = 5_000;
  public static final int MAX_SEEN_IDS = 1_000;
  public static final int MAX_CURSOR_CHARS = 900_000;
  public static final int MAX_METADATA_ENTRIES = 32;
  public static final int MAX_METADATA_VALUE_CHARS = 1_024;
  public static final int MAX_ERROR_CHARS = 2_000;
  /** The code a user types to sign in, from a {@code device_code} record. */
  public static final int MAX_USER_CODE_CHARS = 64;
  /** The longest a {@code device_code} may stay valid. */
  public static final int MAX_SIGN_IN_SECONDS = 3_600;

  private ProtocolLimits() {
  }
}
