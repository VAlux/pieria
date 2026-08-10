package dev.alvo.pieria.tools.os;

import java.util.Locale;

public enum OsFamily {
  MAC,
  WINDOWS,
  LINUX;

  public static OsFamily detect() {
    return fromOsName(osName());
  }

  public static String osName() {
    return System.getProperty("os.name", "");
  }

  public static String osArch() {
    return System.getProperty("os.arch", "");
  }

  static OsFamily fromOsName(String osName) {
    String os = osName.toLowerCase(Locale.ROOT);

    if (os.contains("mac")) {
      return MAC;
    }

    if (os.contains("win")) {
      return WINDOWS;
    }

    return LINUX;
  }
}
