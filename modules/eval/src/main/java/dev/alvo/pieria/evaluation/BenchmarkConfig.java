package dev.alvo.pieria.evaluation;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Everything one benchmark run needs, parsed from {@code --flag=value} command-line arguments and
 * copied verbatim into the report so a run is reproducible from its own output.
 *
 * <p>A full LoCoMo run is 10 conversations, ~5 900 turns and ~2 000 questions — hours against a local
 * model. The subset knobs exist so a change can be smoke-tested in minutes. <strong>Ingestion
 * dominates the cost and is per-conversation</strong>, so {@link #conversations} and {@link #sessions}
 * are the real dials; {@link #questions} only trims the (much cheaper) recall phase.
 *
 * <p>Zero means "no limit" for {@link #conversations}, {@link #sessions} and {@link #questions}; an
 * empty {@link #conversationIds} / {@link #categories} means "all".
 */
public record BenchmarkConfig(
	String dataset,
	String configFile,
	int conversations,
	List<String> conversationIds,
	int sessions,
	int questions,
	List<Integer> categories,
	int runs,
	int recallLimit,
	String outputDirectory,
	boolean judge,
	boolean dryRun) {

	public static final String DEFAULT_DATASET = "datasets/locomo/locomo10.json";
	public static final String DEFAULT_OUTPUT_DIRECTORY = "pieria-eval-reports";

	public static final String USAGE = """
		usage: BenchmarkRunner [options]

		  --dataset=<path>          LoCoMo dataset file (default: %s)
		  --config=<path>           daemon config file to benchmark against (e.g. your installed
		                            pieria.properties). Without it the run uses the daemon's bundled
		                            defaults, which may not be the pipeline you actually deploy.
		  --conversations=<n|ids>   first n conversations, or a comma-separated sample_id list
		                            (e.g. --conversations=3 or --conversations=conv-26,conv-30)
		  --sessions=<n>            keep only sessions 1..n of each conversation; questions whose
		                            evidence lies in a dropped session are dropped with it
		  --questions=<n>           questions per conversation, sampled evenly across the QA list
		  --categories=<1,2,3,4,5>  LoCoMo question categories to keep (1 multi-hop, 2 temporal,
		                            3 open-domain, 4 single-hop, 5 adversarial)
		  --runs=<n>                repeat the whole benchmark n times and average (default: 1)
		  --recall-limit=<n>        memories requested per recall (default: 10)
		  --out=<dir>               report directory (default: %s)
		  --no-judge                skip the LLM faithfulness pass (structural smoke run)
		  --dry-run                 print the selected slice and exit, without booting the daemon
		  --help                    print this and exit
		""".formatted(DEFAULT_DATASET, DEFAULT_OUTPUT_DIRECTORY);

	public BenchmarkConfig {
		conversationIds = conversationIds == null ? List.of() : List.copyOf(conversationIds);
		categories = categories == null ? List.of() : List.copyOf(categories);
	}

	/** All defaults: the whole dataset, one run, judged. */
	public static BenchmarkConfig defaults() {
		return parse();
	}

	public Path datasetPath() {
		return Path.of(dataset);
	}

	/** The daemon config file to layer over the bundled defaults, or {@code null} for defaults only. */
	public Path configPath() {
		return configFile == null || configFile.isBlank() ? null : Path.of(configFile);
	}

	public Path outputPath() {
		return Path.of(outputDirectory);
	}

	/**
	 * Parses {@code --flag=value} arguments over the defaults. An unknown flag, a malformed value, or
	 * a bare positional argument fails fast with {@link IllegalArgumentException} — a benchmark that
	 * silently ignored a typo'd subset flag would run for hours on the wrong slice.
	 */
	public static BenchmarkConfig parse(String... args) {
		String dataset = DEFAULT_DATASET;
		String configFile = null;
		int conversations = 0;
		List<String> conversationIds = List.of();
		int sessions = 0;
		int questions = 0;
		List<Integer> categories = List.of();
		int runs = 1;
		int recallLimit = 10;
		String out = DEFAULT_OUTPUT_DIRECTORY;
		boolean judge = true;
		boolean dryRun = false;

		for (String raw : args == null ? new String[0] : args) {
			if (raw == null || raw.isBlank()) {
				continue;
			}
			String arg = raw.strip();
			if ("--no-judge".equals(arg)) {
				judge = false;
				continue;
			}
			if ("--dry-run".equals(arg)) {
				dryRun = true;
				continue;
			}
			int eq = arg.indexOf('=');
			if (!arg.startsWith("--") || eq < 0) {
				throw new IllegalArgumentException("unknown argument: " + arg + "\n\n" + USAGE);
			}
			String flag = arg.substring(2, eq);
			String value = arg.substring(eq + 1).strip();
			switch (flag) {
				case "dataset" -> dataset = requireText(value, flag);
				case "config" -> configFile = requireText(value, flag);
				case "conversations" -> {
					// Either a count ("3") or an explicit sample_id list ("conv-26,conv-30").
					requireText(value, flag);
					if (value.chars().allMatch(Character::isDigit)) {
						conversations = positive(value, flag);
						conversationIds = List.of();
					} else {
						conversations = 0;
						conversationIds = splitToList(value);
					}
				}
				case "sessions" -> sessions = positive(value, flag);
				case "questions" -> questions = positive(value, flag);
				case "categories" -> categories = categories(value);
				case "runs" -> runs = positive(value, flag);
				case "recall-limit" -> recallLimit = positive(value, flag);
				case "out" -> out = requireText(value, flag);
				default -> throw new IllegalArgumentException("unknown flag: --" + flag + "\n\n" + USAGE);
			}
		}

		return new BenchmarkConfig(dataset, configFile, conversations, conversationIds, sessions,
			questions, categories, runs, recallLimit, out, judge, dryRun);
	}

	/** {@code true} when this category passes the {@code --categories} filter. */
	public boolean acceptsCategory(int category) {
		return categories.isEmpty() || categories.contains(category);
	}

	/** One-line rendering of the active subset, logged before the expensive phase starts. */
	public String describeSubset() {
		String selected = conversationIds.isEmpty()
			? (conversations == 0 ? "all" : String.valueOf(conversations))
			: String.join(",", conversationIds);
		return "conversations=" + selected
			+ " sessions=" + (sessions == 0 ? "all" : sessions)
			+ " questions=" + (questions == 0 ? "all" : questions)
			+ " categories=" + (categories.isEmpty() ? "all" : categories)
			+ " runs=" + runs
			+ " recallLimit=" + recallLimit
			+ " judge=" + judge;
	}

	private static List<Integer> categories(String value) {
		List<Integer> parsed = new ArrayList<>();
		for (String part : splitToList(value)) {
			parsed.add(positive(part, "categories"));
		}
		if (parsed.isEmpty()) {
			throw new IllegalArgumentException("--categories needs at least one category\n\n" + USAGE);
		}
		return parsed;
	}

	private static List<String> splitToList(String value) {
		List<String> parts = new ArrayList<>();
		for (String part : value.split(",")) {
			String trimmed = part.strip();
			if (!trimmed.isEmpty() && !parts.contains(trimmed)) {
				parts.add(trimmed);
			}
		}
		return parts;
	}

	private static int positive(String value, String flag) {
		int parsed;
		try {
			parsed = Integer.parseInt(value.strip());
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException(
				"--" + flag + " expects a positive integer, got: " + value + "\n\n" + USAGE, e);
		}
		if (parsed <= 0) {
			throw new IllegalArgumentException(
				"--" + flag + " expects a positive integer, got: " + value + "\n\n" + USAGE);
		}
		return parsed;
	}

	private static String requireText(String value, String flag) {
		if (value.isBlank()) {
			throw new IllegalArgumentException("--" + flag + " must not be blank\n\n" + USAGE);
		}
		return value;
	}
}
