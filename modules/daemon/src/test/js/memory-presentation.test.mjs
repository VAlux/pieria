import { test } from "node:test";
import assert from "node:assert/strict";
import { memoryPresentation } from "../../main/resources/static/js/console/memory-presentation.js";

test("trace calls decode wrapped arguments without inventing an outcome", () => {
  const view = memoryPresentation({ type: "event", payload: JSON.stringify({
    source: "trace", tool: "Bash", status: "unknown", exit_code: 0,
    command: JSON.stringify({ command: "./gradlew test", description: "Run tests" })
  }) });
  assert.equal(view.subtypeLabel, "Tool call");
  assert.equal(view.title, "Run tests");
  assert.equal(view.preview, "./gradlew test");
  assert.equal(view.statusLabel, "Outcome unknown");
  assert.equal(view.exitCode, 0);
});

test("non-shell tools expose a path and preserve full arguments for the drawer", () => {
  const view = memoryPresentation({ type: "event", payload: {
    source: "trace", tool: "Write", command: 'Write {"file_path":"src/App.java","content":"<script>example</script>"}'
  } });
  assert.equal(view.title, "src/App.java");
  assert.equal(view.preview, "src/App.java");
  assert.equal(view.args.content, "<script>example</script>");
});

test("failure evidence is retained without treating error digests as messages", () => {
  const view = memoryPresentation({ type: "event", content: "`./gradlew test` failed (exit 1): Compilation failed",
    payload: { source: "trace", command: "./gradlew test", status: "failure", exit_code: 1, error_digest: "abc123" } });
  assert.equal(view.failure, "Compilation failed");
  assert.equal(view.statusLabel, "Failed");
});

test("legacy trace keys, recipes and open-ended subtypes stay distinct", () => {
  assert.equal(memoryPresentation({ type: "event", topicKey: "trace:outcome:test" }).subtype, "tool-call");
  assert.equal(memoryPresentation({ type: "instruction", payload: { source: "trace" } }).subtype, "trace-recipe");
  assert.equal(memoryPresentation({ type: "event", payload: { subtype: "release-note" } }).subtypeLabel, "release note");
  assert.equal(memoryPresentation({ type: "event", content: "trace:outcome:test" }).subtype, "");
});

test("invalid payloads preserve ordinary content and fall back to store time", () => {
  for (const payload of [null, "null", "[]", "bad JSON", "42"]) {
    const view = memoryPresentation({ type: "fact", content: "Original content", createdAt: "2026-09-07", payload });
    assert.equal(view.preview, "Original content");
    assert.equal(view.dateLabel, "Stored");
    assert.equal(view.date, "2026-09-07");
  }
});
