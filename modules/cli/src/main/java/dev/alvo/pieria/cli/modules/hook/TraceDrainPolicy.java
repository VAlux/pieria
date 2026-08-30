package dev.alvo.pieria.cli.modules.hook;

/**
 * When a lifecycle hook should ship the spool.
 *
 * <p>A final capture always drains — leaving traces behind at session end would lose them. An
 * end-of-turn capture drains only once the spool is large enough to be worth a round trip, because
 * {@code Stop} fires every turn and draining each time would cut every batch down to one turn. A
 * failure and the fix that followed it are routinely in different turns, and only a batch spanning
 * both can produce a useful recipe.
 */
public final class TraceDrainPolicy {

  private TraceDrainPolicy() {
  }

  /**
   * @param partial         whether this is a routine mid-session capture ({@code Stop})
   * @param spoolBytes      current spool size
   * @param spoolEvents     current buffered event count
   * @param thresholdBytes  size at or above which an end-of-turn capture drains
   * @param thresholdEvents count at or above which an end-of-turn capture drains
   */
  public static boolean shouldDrain(boolean partial, long spoolBytes, int spoolEvents,
                                    long thresholdBytes, int thresholdEvents) {
    if (!partial) {
      return true;
    }
    if (spoolEvents <= 0) {
      return false;
    }
    return spoolBytes >= thresholdBytes || spoolEvents >= thresholdEvents;
  }
}
