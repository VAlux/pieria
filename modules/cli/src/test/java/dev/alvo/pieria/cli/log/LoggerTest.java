package dev.alvo.pieria.cli.log;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LoggerTest {

  private Logger logger = new Logger();

  @BeforeEach
  void setUp() {
    this.logger = new Logger();
  }

  @Test
  void testInterpolation() {
    String patternEnd = "This is a test: {}";
    String patternMiddle = "This is a {} test:{}";
    String patternStart = "{} This is a test";
    String patternStartEnd = "{} This is a test: {}";
    String patternStartMiddleEnd = "{} This is a {} test: {}";
    String patternDoubleStart = "{}{} This is a test";
    String patternDoubleEnd = "This is a test:{}{}";

    String actualPatternEnd = logger.interpolate(patternEnd, "first");
    String actualPatternMiddle = logger.interpolate(patternMiddle, "first", "second");
    String actualPatternStart = logger.interpolate(patternStart, "first");
    String actualPatternStartEnd = logger.interpolate(patternStartEnd, "first", "second");
    String actualPatternStartMiddleEnd = logger.interpolate(patternStartMiddleEnd, "first", "second", "third");
    String actualPatternDoubleStart = logger.interpolate(patternDoubleStart, "first", "second");
    String actualPatternDoubleEnd = logger.interpolate(patternDoubleEnd, "first", "second");

    String expectedPatternEnd = "This is a test: first";
    String expectedPatternMiddle = "This is a first test:second";
    String expectedPatternStart = "first This is a test";
    String expectedPatternStartEnd = "first This is a test: second";
    String expectedPatternStartMiddleEnd = "first This is a second test: third";
    String expectedPatternDoubleStart = "firstsecond This is a test";
    String expectedPatternDoubleEnd = "This is a test:firstsecond";

    Assertions.assertEquals(expectedPatternEnd, actualPatternEnd);
    Assertions.assertEquals(expectedPatternMiddle, actualPatternMiddle);
    Assertions.assertEquals(expectedPatternStart, actualPatternStart);
    Assertions.assertEquals(expectedPatternStartEnd, actualPatternStartEnd);
    Assertions.assertEquals(expectedPatternStartMiddleEnd, actualPatternStartMiddleEnd);
    Assertions.assertEquals(expectedPatternDoubleStart, actualPatternDoubleStart);
    Assertions.assertEquals(expectedPatternDoubleEnd, actualPatternDoubleEnd);
  }

}