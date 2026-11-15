package dev.alvo.pieria.harvesters.teams;

/**
 * A device-code sign-in in progress. {@code deviceCode} is what the harvester polls with and stays
 * in this process; the rest is shown to the user.
 *
 * @param message   Microsoft's own instructions, or null
 * @param expiresIn seconds the code is valid for
 * @param interval  seconds to wait between polls
 */
public record DeviceCodeGrant(String deviceCode, String userCode, String verificationUri, String message, int expiresIn, int interval) {

  @Override
  public String toString() {
    return "DeviceCodeGrant[userCode=" + userCode + ", verificationUri=" + verificationUri + ", expiresIn=" + expiresIn
      + ", interval=" + interval + "]";
  }
}
