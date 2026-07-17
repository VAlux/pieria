# Onboarding acceleration evaluation

Onboarding tuning is opt-in and must be measured against the checked-in small, medium, and large
document corpora in `modules/eval/src/test/resources/evaluation/onboarding/corpora.json`. Live runs
use a real throwaway daemon and provider, run each configuration three times, and write reports only
under the ignored `pieria-eval-reports/` directory. Every report records provider/model metadata,
core wall time, structured calls/output tokens, expected-fact coverage, evidence recall@10, and the
unsupported-memory rate. `OnboardingTuningGate` compares medians and encodes the release thresholds.

Run the complete baseline, three independent variants, and combined variant with:

```bash
PIERIA_ONBOARDING_EVAL=1 ./gradlew :eval:test --tests "*OnboardingBenchmarkLiveTests*"
```

The benchmark disables automatic graph enrichment so core measurements cannot race the graph child
task. It snapshots extraction-tier calls and completion tokens before recall, waits for the embedding
outbox before each recall query, and uses deterministic labeled-evidence matching for the three
quality metrics. Each corpus/run uses a fresh profile and staged temporary text directory.

Evaluate these changes independently and then together through the per-profile ingestion overrides:

| Variant | Overrides |
|---|---|
| Baseline | overlap `2`, query cap `0` (established 3–5), candidate cap `0` (unlimited) |
| No overlap | `chunk-overlap-messages = 0` |
| Two queries | `interrogative-queries-per-memory = 2` |
| Candidate cap | `max-extracted-candidates-per-chunk = 12` |
| Combined | all three candidate values |

An individual variant qualifies only when fact coverage and recall@10 each fall by at most two
percentage points, unsupported memories rise by at most one point, and at least one of calls,
structured output tokens, or core wall time improves by ten percent. The combined variant additionally
requires at least 25% lower core wall time and 20% fewer structured tokens. Otherwise retain only the
individually qualifying controls. Production defaults remain baseline until a checked-in comparison
report demonstrates those gates.

The separate `graph-from-extraction` experiment is also off by default. When enabled, unified
extraction may emit at most five entities and five triples per candidate. Unchanged PASS/grounded
fragments are persisted; corrected or missing fragments remain graph orphans. It may become the
default only when entity/edge precision and recall are within three points of separate reminiscence,
core onboarding is no more than 10% slower than deferred extraction, and complete graph enrichment
uses at least 30% fewer structured tokens.
