package dev.alvo.pieria.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Loads local fixture JSON files. Network and model providers are intentionally outside this path.
 */
public final class EvaluationFixtureLoader {

	private final ObjectMapper objectMapper;

	public EvaluationFixtureLoader(ObjectMapper objectMapper) {
		this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
	}

	public EvaluationFixtureLoader() {
		this(new ObjectMapper());
	}

	public EvaluationFixture load(Path path) throws IOException {
		return objectMapper.readValue(path.toFile(), EvaluationFixture.class);
	}

	public EvaluationFixture loadResource(String resourceName) throws IOException {
		ClassLoader loader = Thread.currentThread().getContextClassLoader();
		try (InputStream in = loader.getResourceAsStream(resourceName)) {
			if (in == null) {
				throw new IOException("fixture resource not found: " + resourceName);
			}
			return objectMapper.readValue(in, EvaluationFixture.class);
		}
	}

	public List<EvaluationFixture> loadDirectory(Path directory) throws IOException {
		try (var stream = Files.list(directory)) {
			return stream
				.filter(path -> path.getFileName().toString().endsWith(".json"))
				.sorted(Comparator.comparing(path -> path.getFileName().toString()))
				.map(this::loadUnchecked)
				.toList();
		}
	}

	private EvaluationFixture loadUnchecked(Path path) {
		try {
			return load(path);
		} catch (IOException e) {
			throw new IllegalArgumentException("failed to load fixture " + path, e);
		}
	}
}
