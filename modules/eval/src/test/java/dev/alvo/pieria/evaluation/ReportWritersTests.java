package dev.alvo.pieria.evaluation;

import dev.alvo.pieria.evaluation.EvaluationReport.ConversationReport;
import dev.alvo.pieria.evaluation.EvaluationReport.Latency;
import dev.alvo.pieria.evaluation.EvaluationReport.QueryReport;
import dev.alvo.pieria.evaluation.EvaluationReport.Summary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReportWritersTests {

	@Test
	void writesAJsonReportThatReadsBackUnchanged(@TempDir Path directory) throws IOException {
		EvaluationReport report = report();
		EvaluationReportWriter writer = new EvaluationReportWriter();

		Path json = writer.write(report, directory);

		assertThat(json).exists().hasFileName("evaluation-2026-08-11T10-00-00Z.json");
		assertThat(writer.read(json)).isEqualTo(report);
	}

	@Test
	void rendersASelfContainedHtmlPageFromTheReport(@TempDir Path directory) throws IOException {
		EvaluationReport report = report();

		Path html = new HtmlReportWriter().write(report, directory);
		String page = Files.readString(html);

		assertThat(html).hasFileName("evaluation-2026-08-11T10-00-00Z.html");
		// Summary, category breakdown, and per-question detail all land on the page.
		assertThat(page)
			.contains("50.0%")                              // faithfulness 1 of 2
			.contains("75.0%")                              // hit rate
			.contains("0.750")                              // MRR
			.contains("2 — temporal")
			.contains("4 — single-hop")
			.contains("conv-1")
			.contains("What is the name of Caroline&#39;s dog?")
			.contains("Biscuit")
			.contains("Caroline adopted a rescue dog named Biscuit")
			.contains("qwen3:8b");                          // model metadata
		// No external assets: the page must open straight from disk.
		assertThat(page).doesNotContain("http://").doesNotContain("https://");
	}

	@Test
	void reRendersAPreviouslyWrittenReportFromJsonAlone(@TempDir Path directory) throws IOException {
		Path json = new EvaluationReportWriter().write(report(), directory);
		Path expected = directory.resolve("evaluation-2026-08-11T10-00-00Z.html");
		String direct = new HtmlReportWriter().render(report());

		HtmlReportWriter.main(json.toString());

		assertThat(expected).exists();
		assertThat(Files.readString(expected)).isEqualTo(direct);
	}

	@Test
	void multiRunSummaryPoolsEveryRunsQuestions() {
		ConversationReport miss = conversation(false, 0.0, 0.0);
		ConversationReport hit = conversation(true, 1.0, 1.0);

		Summary summary = BenchmarkRunner.summarize(List.of(List.of(miss), List.of(hit)));

		// Two runs of one question each: half faithful, and the per-category score sees both.
		assertThat(summary.conversations()).isEqualTo(1);
		assertThat(summary.questions()).isEqualTo(1);
		assertThat(summary.answerFaithfulness()).isEqualTo(0.5);
		assertThat(summary.byCategory().get(4).questions()).isEqualTo(2);
		// Latency is averaged per run, not summed across them.
		assertThat(summary.latency().ingestionMs()).isEqualTo(1000);
	}

	private static ConversationReport conversation(boolean faithful, double hitRate, double rank) {
		QueryReport query = new QueryReport("q", 4, "expected", "actual", faithful,
			List.of("evidence"), List.of("memory"), hitRate, rank, 10);
		return new ConversationReport("conv-1", 2, 3, faithful ? 1.0 : 0.0, hitRate, rank,
			Latency.of(1000, 200), List.of(query));
	}

	private static EvaluationReport report() {
		QueryReport answered = new QueryReport(
			"What is the name of Caroline's dog?", 4, "Biscuit", "Her dog is called Biscuit.", true,
			List.of("I just adopted a rescue dog named Biscuit last weekend!"),
			List.of("Caroline adopted a rescue dog named Biscuit"), 1.0, 1.0, 420);
		QueryReport missed = new QueryReport(
			"When did Melanie ask about the breed?", 2, "8 May 2023", null, false,
			List.of("That's wonderful! What breed is Biscuit?"), List.of(), 0.5, 0.5, 610);

		ConversationReport conversation = new ConversationReport(
			"conv-1", 6, 12, 0.5, 0.75, 0.75, Latency.of(61_000, 1_030), List.of(answered, missed));

		Summary summary = new Summary(1, 2, 12, 0.5, 0.75, 0.75, Latency.of(61_000, 1_030),
			EvaluationReport.scoreByCategory(EvaluationReport.allQueries(List.of(conversation))));

		return new EvaluationReport(
			Instant.parse("2026-08-11T10:00:00Z"),
			"locomo",
			BenchmarkConfig.parse("--conversations=1", "--sessions=3", "--questions=2"),
			Map.of("provider", "ollama", "extractionModel", "qwen3:8b"),
			summary,
			List.of(conversation));
	}
}
