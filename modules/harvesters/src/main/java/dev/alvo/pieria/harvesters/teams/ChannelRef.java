package dev.alvo.pieria.harvesters.teams;

import dev.alvo.pieria.harvester.HarvesterException;
import dev.alvo.pieria.harvesters.ConfigValues;

/**
 * A team's channels to read, by display name.
 *
 * @param channel null for every channel in the team
 */
public record ChannelRef(String team, String channel) {

  /** {@code "Team"} or {@code "Team/Channel"}, split at the first {@code /}. */
  static ChannelRef parse(String value) throws HarvesterException {
    int slash = value.indexOf('/');
    String team = (slash < 0 ? value : value.substring(0, slash)).strip();
    String channel = slash < 0 ? null : value.substring(slash + 1).strip();
    if (team.isEmpty() || (channel != null && channel.isEmpty())) {
      throw ConfigValues.invalid("channels entries must be Team or Team/Channel: " + value);
    }
    return new ChannelRef(team, channel);
  }

  boolean matches(String teamName, String channelName) {
    return team.equalsIgnoreCase(teamName.strip()) && (channel == null || channel.equalsIgnoreCase(channelName.strip()));
  }
}
