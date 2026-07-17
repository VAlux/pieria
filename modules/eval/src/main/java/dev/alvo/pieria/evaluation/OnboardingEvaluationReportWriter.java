package dev.alvo.pieria.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;

/** Writes onboarding experiment reports outside tracked build outputs. */
public final class OnboardingEvaluationReportWriter {

  public Path write(OnboardingEvaluationReport report, Path outputDirectory) throws IOException {
    Files.createDirectories(outputDirectory);
    String timestamp = DateTimeFormatter.ISO_INSTANT.format(report.generatedAt()).replace(':', '-');
    Path output = outputDirectory.resolve("onboarding-" + timestamp + ".json");
    new ObjectMapper().findAndRegisterModules().writerWithDefaultPrettyPrinter()
      .writeValue(output.toFile(), report);
    return output;
  }
}
