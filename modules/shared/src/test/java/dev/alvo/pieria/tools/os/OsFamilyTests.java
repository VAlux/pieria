package dev.alvo.pieria.tools.os;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OsFamilyTests {

  @Test
  void detectsMacFromOsName() {
    assertThat(OsFamily.fromOsName("Mac OS X")).isEqualTo(OsFamily.MAC);
  }

  @Test
  void detectsWindowsFromOsName() {
    assertThat(OsFamily.fromOsName("Windows 11")).isEqualTo(OsFamily.WINDOWS);
  }

  @Test
  void detectsLinuxFromOsName() {
    assertThat(OsFamily.fromOsName("Linux")).isEqualTo(OsFamily.LINUX);
  }

  @Test
  void fallsBackToLinuxForUnknownOsName() {
    assertThat(OsFamily.fromOsName("SunOS")).isEqualTo(OsFamily.LINUX);
  }

  @Test
  void detectReflectsCurrentJvmOsName() {
    String currentOsName = System.getProperty("os.name", "");
    assertThat(OsFamily.detect()).isEqualTo(OsFamily.fromOsName(currentOsName));
  }
}
