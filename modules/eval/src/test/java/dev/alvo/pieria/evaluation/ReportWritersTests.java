package dev.alvo.pieria.evaluation;

import dev.alvo.pieria.evaluation.EvaluationReport.ConversationReport;
import dev.alvo.pieria.evaluation.EvaluationReport.Latency;
import dev.alvo.pieria.evaluation.EvaluationReport.QueryReport;
import dev.alvo.pieria.evaluation.EvaluationReport.Spend;
import dev.alvo.pieria.evaluation.EvaluationReport.Summary;
import dev.alvo.pieria.model.ModelGateway.AnswerVerdict;
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
		// Summary, funnel, category breakdown, and per-question detail all land on the page.
		assertThat(page)
			.contains("33.3%")                              // accuracy: 1 correct of 3
			.contains("50.0%")                              // extraction coverage: 1 of 2 gated
			.contains("100.0%")                             // retrieval recall: 1 of the 1 extracted
			.contains("2 — temporal")
			.contains("4 — single-hop")
			.contains("5 — adversarial")
			.contains("conv-1")
			.contains("What is the name of Caroline&#39;s dog?")
			.contains("Biscuit")
			.contains("Caroline adopted a rescue dog named Biscuit")
			.contains("qwen3:8b");                          // model metadata
		// No external assets: the page must open straight from disk.
		assertThat(page).doesNotContain("http://").doesNotContain("https://");
	}

	@Test
	void labelsAnAdversarialRefusalAsDeclinedRatherThanCorrect() throws IOException {
		String page = new HtmlReportWriter().render(report());

		// "correct" against a trap answer would describe the opposite of what happened.
		assertThat(page).contains(">declined<").doesNotContain(">took the bait<");
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
		ConversationReport miss = conversation(AnswerVerdict.WRONG, false);
		ConversationReport hit = conversation(AnswerVerdict.CORRECT, true);

		Summary summary = BenchmarkRunner.summarize(List.of(List.of(miss), List.of(hit)), Spend.NONE);

		// Two runs of one question each: half correct, and the per-category score sees both.
		assertThat(summary.conversations()).isEqualTo(1);
		assertThat(summary.questions()).isEqualTo(1);
		assertThat(summary.score().accuracy()).isEqualTo(0.5);
		assertThat(summary.byCategory().get(4).questions()).isEqualTo(2);
		// Latency is averaged per run, not summed across them.
		assertThat(summary.latency().ingestionMs()).isEqualTo(1000);
	}

	private static ConversationReport conversation(AnswerVerdict verdict, boolean extracted) {
		QueryReport query = new QueryReport("q", 4, false, "expected", "actual", verdict,
			extracted, extracted, List.of("evidence"), List.of("memory"), List.of("memory"), 10);
		return new ConversationReport("conv-1", 2, 3, EvaluationReport.score(List.of(query)),
			Latency.of(1000, 200), spend(0.25), List.of(query), List.of("memory"));
	}

	@Test
	void spendIsSummedAcrossRunsAndCombinedWithTheJudge() {
		ConversationReport run1 = conversation(AnswerVerdict.CORRECT, true);
		ConversationReport run2 = conversation(AnswerVerdict.CORRECT, true);
		Spend judge = new Spend(List.of(new Spend.TierSpend("synthesis", 4, 1_000, 20, 0.5)),
			1_000, 20, 0.5, true);

		Summary summary = BenchmarkRunner.summarize(List.of(List.of(run1), List.of(run2)), judge);

		// Every repeat is a fresh profile paid for in full, so spend adds up rather than averaging.
		assertThat(summary.pipelineSpend().costUsd()).isEqualTo(0.50);
		assertThat(summary.judgeSpend().costUsd()).isEqualTo(0.50);
		assertThat(summary.spend().costUsd()).isEqualTo(1.00);
		assertThat(summary.spend().priced()).isTrue();
	}

	@Test
	void anUnpricedRunReportsTokensWithoutClaimingItWasFree() {
		String page = new HtmlReportWriter().render(unpricedReport());

		assertThat(page).contains("No per-tier prices are configured").doesNotContain("$0.0000");
	}

	/** A pipeline spend of {@code costUsd}, split over one tier, as the daemon would report it. */
	private static Spend spend(double costUsd) {
		return new Spend(List.of(new Spend.TierSpend("extraction", 6, 7_500, 2_800, costUsd)),
			7_500, 2_800, costUsd, true);
	}

	private static EvaluationReport unpricedReport() {
		EvaluationReport priced = report();
		Summary s = priced.summary();
		Summary unpriced = new Summary(s.conversations(), s.questions(), s.memoriesStored(), s.score(),
			s.latency(), Spend.NONE, Spend.NONE, Spend.NONE, s.byCategory());
		return new EvaluationReport(priced.generatedAt(), priced.benchmark(), priced.config(),
			priced.models(), unpriced, priced.conversations());
	}

	private static EvaluationReport report() {
		QueryReport answered = new QueryReport(
			"What is the name of Caroline's dog?", 4, false, "Biscuit", "Her dog is called Biscuit.",
			AnswerVerdict.CORRECT, true, true,
			List.of("I just adopted a rescue dog named Biscuit last weekend!"),
			List.of("Caroline adopted a rescue dog named Biscuit"),
			List.of("Caroline adopted a rescue dog named Biscuit"), 420);
		// Never extracted, so the retrieval gate does not apply and stays null.
		QueryReport lostAtExtraction = new QueryReport(
			"When did Melanie ask about the breed?", 2, false, "8 May 2023", null,
			AnswerVerdict.ABSTAINED, false, null,
			List.of("That's wonderful! What breed is Biscuit?"), List.of(), List.of("unrelated"), 610);
		// Adversarial: declining is the correct outcome, and neither gate applies.
		QueryReport declined = new QueryReport(
			"What did Melanie realize after her charity race?", 5, true, "self-care is important",
			"I have no memory of Melanie running a charity race.",
			AnswerVerdict.ABSTAINED, null, null,
			List.of("Running that race taught me self-care matters."), List.of(), List.of(), 380);

		List<QueryReport> queries = List.of(answered, lostAtExtraction, declined);
		ConversationReport conversation = new ConversationReport(
			"conv-1", 6, 12, EvaluationReport.score(queries), Latency.of(61_000, 1_030), spend(0.25),
			queries, List.of("Caroline adopted a rescue dog named Biscuit", "unrelated"));

		Spend judgeSpend = new Spend(
			List.of(new Spend.TierSpend("synthesis", 22, 10_700, 110, 0.0286)), 10_700, 110, 0.0286, true);
		Summary summary = new Summary(1, 3, 12, EvaluationReport.score(queries),
			Latency.of(61_000, 1_030),
			conversation.spend(), judgeSpend, Spend.sum(List.of(conversation.spend(), judgeSpend)),
			EvaluationReport.scoreByCategory(EvaluationReport.allQueries(List.of(conversation))));

		return new EvaluationReport(
			Instant.parse("2026-08-11T10:00:00Z"),
			"locomo",
			BenchmarkConfig.parse("--conversations=1", "--sessions=3", "--questions=3"),
			Map.of("provider", "ollama", "extractionModel", "qwen3:8b"),
			summary,
			List.of(conversation));
	}
}
