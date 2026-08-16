package dev.alvo.pieria.evaluation;

import dev.alvo.pieria.evaluation.EvaluationReport.QueryReport;
import dev.alvo.pieria.model.ModelGateway.AnswerVerdict;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/**
 * Renders an {@link EvaluationReport} into a single self-contained HTML page with Thymeleaf, so a run
 * can be read by a human instead of scrolled through as JSON. The page has no external assets — its
 * CSS is inline and every question is an expandable {@code <details>} block — so it opens straight
 * from disk.
 *
 * <p>The JSON report stays the source of truth: {@link #main} re-renders any previously written
 * report file, which is how an old run gets a page without re-driving the daemon.
 */
public final class HtmlReportWriter {

  private static final String TEMPLATE = "report";

  private final TemplateEngine engine;

  public HtmlReportWriter() {
    ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
    resolver.setPrefix("templates/");
    resolver.setSuffix(".html");
    resolver.setTemplateMode(TemplateMode.HTML);
    resolver.setCharacterEncoding(StandardCharsets.UTF_8.name());
    // One render per process; caching would only hold the template between runs that never happen.
    resolver.setCacheable(false);

    this.engine = new TemplateEngine();
    this.engine.setTemplateResolver(resolver);
  }

  /** Writes the report's HTML rendering next to its JSON, under the same timestamped base name. */
  public Path write(EvaluationReport report, Path outputDirectory) throws IOException {
    Files.createDirectories(outputDirectory);
    Path file = outputDirectory.resolve(EvaluationReportWriter.fileName(report) + ".html");
    Files.writeString(file, render(report), StandardCharsets.UTF_8);
    return file;
  }

  public String render(EvaluationReport report) {
    Context context = new Context();
    context.setVariable("report", report);
    context.setVariable("summary", report.summary());
    context.setVariable("fmt", new Formats());
    return engine.process(TEMPLATE, context);
  }

  /**
   * Display helpers the template calls directly ({@code ${fmt.pct(...)}}). Keeping the formatting
   * here rather than in the report keeps the JSON free of presentation strings.
   */
  public static final class Formats {

    private static final Map<Integer, String> CATEGORY_NAMES = Map.of(
      1, "multi-hop",
      2, "temporal",
      3, "open-domain",
      4, "single-hop",
      5, "adversarial");

    /** {@code 0.6234 -> "62.3%"} */
    public String pct(double value) {
      return String.format(Locale.ROOT, "%.1f%%", value * 100);
    }

    /** {@code 0.6234 -> "0.623"} */
    public String num(double value) {
      return String.format(Locale.ROOT, "%.3f", value);
    }

    /** {@code 63000 -> "1m03s"} */
    public String duration(long millis) {
      return EvaluationRunner.formatDuration(millis);
    }

    /** {@code 0.0713 -> "$0.0713"} — four places, because a subset run costs cents. */
    public String money(double usd) {
      return String.format(Locale.ROOT, "$%.4f", usd);
    }

    /** {@code 2 -> "2 — temporal"} */
    public String category(int category) {
      String name = CATEGORY_NAMES.get(category);
      return name == null ? String.valueOf(category) : category + " — " + name;
    }

    /** Empty rather than {@code "null"} for a question the daemon declined to answer. */
    public String text(String value) {
      return value == null || value.isBlank() ? "—" : value;
    }

    /**
     * The badge label for a question's outcome. An adversarial question reads as "declined" when it
     * passed and "took the bait" when it did not, because "correct" against a trap answer would
     * describe the opposite of what happened.
     */
    public String outcome(QueryReport query) {
      if (query.verdict() == null) {
        return "unjudged";
      }
      if (query.expectAbstention()) {
        return query.correct() ? "declined" : "took the bait";
      }
      return switch (query.verdict()) {
        case CORRECT -> "correct";
        case WRONG -> "wrong";
        case ABSTAINED -> "abstained";
      };
    }

    /** {@code pass} / {@code fail} / neutral, driving the badge colour. */
    public String outcomeClass(QueryReport query) {
      if (query.verdict() == null) {
        return "";
      }
      if (query.correct()) {
        return "pass";
      }
      return query.verdict() == AnswerVerdict.ABSTAINED && !query.expectAbstention() ? "warn" : "fail";
    }

    /** A funnel gate that does not apply (adversarial) or was never judged reads {@code n/a}. */
    public String gate(Boolean value) {
      if (value == null) {
        return "n/a";
      }
      return value ? "yes" : "no";
    }
  }

  /**
   * Re-renders an existing report JSON:
   * <pre>{@code ./gradlew :eval:locomoReport --args="pieria-eval-reports/evaluation-....json"}</pre>
   */
  public static void main(String... args) throws IOException {
    if (args.length != 1 || args[0].isBlank()) {
      System.err.println("usage: HtmlReportWriter <report.json>");
      System.exit(2);
      return;
    }

    Path json = Path.of(args[0]);
    if (!Files.exists(json)) {
      System.err.println("report not found: " + json.toAbsolutePath());
      System.exit(2);
      return;
    }

    EvaluationReport report = new EvaluationReportWriter().read(json);
    Path directory = json.toAbsolutePath().getParent();
    Path html = new HtmlReportWriter().write(report, directory);
    System.out.println("html report: " + html);
  }
}
