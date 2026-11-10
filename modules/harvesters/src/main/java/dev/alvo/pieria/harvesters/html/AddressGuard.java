package dev.alvo.pieria.harvesters.html;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.function.Consumer;

/**
 * Keeps the crawler off private and loopback addresses, so a link or redirect on a public page can't
 * reach services on the user's machine or network. The check happens before each request; a name that
 * changes its answer between this check and the connection is not caught.
 */
public final class AddressGuard implements Consumer<String> {

  private final boolean allowPrivate;

  public AddressGuard(boolean allowPrivate) {
    this.allowPrivate = allowPrivate;
  }

  @Override
  public void accept(String host) {
    if (allowPrivate) {
      return;
    }
    String name = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
    InetAddress[] addresses;
    try {
      addresses = InetAddress.getAllByName(name);
    } catch (UnknownHostException e) {
      throw new BlockedAddressException(host + " did not resolve");
    }
    for (InetAddress address : addresses) {
      if (isBlocked(address)) {
        throw new BlockedAddressException(host + " is a private or loopback address; set allowPrivate to crawl it");
      }
    }
  }

  private static InetAddress embeddedIpv4(byte[] bytes) {
    try {
      return InetAddress.getByAddress(Arrays.copyOfRange(bytes, 12, 16));
    } catch (UnknownHostException e) {
      throw new IllegalStateException(e);
    }
  }

  public static boolean isBlocked(InetAddress address) {
    if (address.isLoopbackAddress() || address.isAnyLocalAddress() || address.isLinkLocalAddress()
      || address.isSiteLocalAddress() || address.isMulticastAddress()) {
      return true;
    }
    byte[] bytes = address.getAddress();
    if (address instanceof Inet6Address v6) {
      return (bytes[0] & 0xFE) == 0xFC || (v6.isIPv4CompatibleAddress() && isBlocked(embeddedIpv4(bytes)));
    }
    int first = bytes[0] & 0xFF;
    boolean thisNetwork = first == 0;
    boolean sharedAddressSpace = first == 100 && (bytes[1] & 0xC0) == 0x40;
    boolean protocolAssignments = first == 192 && bytes[1] == 0 && bytes[2] == 0;
    boolean reserved = (first & 0xF0) == 0xF0;
    return thisNetwork || sharedAddressSpace || protocolAssignments || reserved;
  }
}
