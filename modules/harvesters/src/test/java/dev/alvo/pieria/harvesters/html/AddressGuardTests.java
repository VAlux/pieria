package dev.alvo.pieria.harvesters.html;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AddressGuardTests {

  @ParameterizedTest
  @ValueSource(strings = {"127.0.0.1", "0.0.0.0", "10.1.2.3", "172.16.0.1", "192.168.1.1", "169.254.169.254", "100.64.0.1",
    "100.127.255.255", "224.0.0.1", "::1", "fc00::1", "fd12::1", "fe80::1", "::ffff:127.0.0.1"})
  void blocks(String ip) throws Exception {
    assertThat(AddressGuard.isBlocked(InetAddress.getByName(ip))).isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {"8.8.8.8", "2606:4700:4700::1111", "100.128.0.1", "100.63.255.255"})
  void allowsPublic(String ip) throws Exception {
    assertThat(AddressGuard.isBlocked(InetAddress.getByName(ip))).isFalse();
  }

  @Test
  void refusesALoopbackHost() {
    assertThatThrownBy(() -> new AddressGuard(false).accept("127.0.0.1")).isInstanceOf(BlockedAddressException.class);
  }

  @Test
  void refusesABracketedIpv6Host() {
    assertThatThrownBy(() -> new AddressGuard(false).accept("[::1]")).isInstanceOf(BlockedAddressException.class);
  }

  @Test
  void aNameThatDoesNotResolveIsReported() {
    assertThatThrownBy(() -> new AddressGuard(false).accept("does-not-exist.invalid"))
      .isInstanceOf(BlockedAddressException.class).hasMessageContaining("resolve");
  }

  @Test
  void allowPrivateAdmitsLoopback() {
    assertThatCode(() -> new AddressGuard(true).accept("127.0.0.1")).doesNotThrowAnyException();
  }
}
