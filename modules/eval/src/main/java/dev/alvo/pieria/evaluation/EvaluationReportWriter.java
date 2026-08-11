package dev.alvo.pieria.evaluation;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;

/**
 * Reads and writes benchmark reports as JSON. The written file is the source of truth for a run —
 * {@link HtmlReportWriter} renders it, and {@link #read(Path)} loads it back so a past run can be
 * re-rendered or re-judged without re-driving the daemon.
 *
 * <p>Reports land in a caller-selected directory; the repository ignores {@code pieria-eval-reports/}.
 */
public final class EvaluationReportWriter {

	private final ObjectMapper objectMapper;

	public EvaluationReportWriter() {
		this.objectMapper = new ObjectMapper()
			.registerModule(new JavaTimeModule())
			.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
			.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
	}

	public Path write(EvaluationReport report, Path outputDirectory) throws IOException {
		Files.createDirectories(outputDirectory);
		Path file = outputDirectory.resolve(fileName(report) + ".json");
		objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), report);
		return file;
	}

	public EvaluationReport read(Path reportFile) throws IOException {
		return objectMapper.readValue(reportFile.toFile(), EvaluationReport.class);
	}

	/**
	 * Timestamped base name shared by a run's JSON and HTML files, so the pair sorts together and
	 * stays filesystem-safe on Windows (where {@code :} is illegal in a file name).
	 */
	static String fileName(EvaluationReport report) {
		return "evaluation-"
			+ DateTimeFormatter.ISO_INSTANT.format(report.generatedAt()).replace(':', '-');
	}
}
