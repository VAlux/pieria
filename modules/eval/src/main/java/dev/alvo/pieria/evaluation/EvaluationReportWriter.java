package dev.alvo.pieria.evaluation;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/**
 * Writes reports into a caller-selected local output directory. The repository ignores
 * {@code pieria-eval-reports/} for ad hoc runs.
 */
public final class EvaluationReportWriter {

	public static final Path DEFAULT_OUTPUT_DIRECTORY = Path.of("pieria-eval-reports");

	private final ObjectMapper objectMapper;

	public EvaluationReportWriter(ObjectMapper objectMapper) {
		SimpleModule module = new SimpleModule();
		module.addSerializer(Instant.class, new JsonSerializer<>() {
			@Override
			public void serialize(Instant value, JsonGenerator generator, SerializerProvider serializers)
				throws IOException {
				generator.writeString(DateTimeFormatter.ISO_INSTANT.format(value));
			}
		});
		this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper")
			.copy()
			.registerModule(module);
	}

	public EvaluationReportWriter() {
		this(new ObjectMapper());
	}

	public Path write(EvaluationReport report, Path outputDirectory) throws IOException {
		Files.createDirectories(outputDirectory);
		String timestamp = DateTimeFormatter.ISO_INSTANT.format(report.generatedAt())
			.replace(':', '-');
		Path file = outputDirectory.resolve("evaluation-" + timestamp + ".json");
		objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), report);
		return file;
	}
}
