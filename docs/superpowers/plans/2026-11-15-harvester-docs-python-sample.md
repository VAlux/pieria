# Harvesters Phase 9: Developer Docs and a Python Sample Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let someone outside the project write a harvester. This phase adds:
- `docs/HARVESTERS.md`, a protocol reference and tutorial;
- a sample `notes` harvester in Python, standard library only, that passes the conformance kit inside `./gradlew test`.

**Architecture:**
- **The sample** lives in `harvest-examples/notes/`, in one file: `notes_harvester.py`.
  - It speaks protocol v1 directly, with no SDK, so the protocol is in plain view.
  - It reports every Markdown or text file under a folder as a `document`, in path order. It checkpoints as it goes, resumes from the cursor, and finds deletions with `seen` plus `sweep`.
  - The daemon already skips a document whose `version` is unchanged, so the harvester needs no change tracking of its own.
- **Tests, in two layers:**
  - A stdlib `unittest` suite beside the script.
  - A JUnit class in `:harvester-sdk` that runs that suite and then the conformance kit against the sample. It is skipped when `python3` isn't on `PATH`.
- **`docs/HARVESTERS.md`** is the single document an author needs. The phase doc stays the design record.

**Tech Stack:** Python 3.9+ (standard library only), Java 25 and JUnit 5 for the wrapper test, Markdown.

**Spec:** `docs/phases/phase-16-knowledge-harvesters.md`. The relevant parts are:
- "Developer experience";
- "Protocol v1";
- "Security";
- "Delivery phases", item 9;
- every block under "Amendments since approval". These record what phases 1–8 actually built, and the reference must match them.

**Settled with the user for this phase:** the sample reads a local folder of notes, so it needs no network and no secrets, and the kit can run it in CI.

**Sources of truth the reference must agree with.** Read them, don't paraphrase from memory:
- **Records:** `modules/harvester-sdk/.../protocol/HarvestRecord.java` (fields and validation) and `RecordCodec.java` (wire names).
- **Limits:** `ProtocolLimits.java`.
- **Envelope:** `RequestCodec.java`.
- **Commands and exit codes:** `HarvesterRunner.java`.
- **Manifest:** `manifest/ManifestParser.java` (keys, `command` and `entrypoint` rules), `Capability.java` and `FieldKind.java`.
- **Child process:** `process/HarvesterProcess.java` (the environment kept, the stdin write, the stderr cap and backpressure).
- **The kit:** `conformance/ConformanceKit.java` (check names and what each asserts) and `ConformanceMain.java` (flags).
- **The daemon's side:**
  - `modules/daemon/.../harvest/HarvestRunService.java` (records, limits, timeouts, checkpoints, sweeps and the mass-delete guard);
  - `HarvestScheduler.java` and `HarvestSchedule.java` (backoff, `retryAfter`, `needs_auth`);
  - `HarvestItemIngestor.java` (what `version` and thread turns mean for re-extraction);
  - `HarvestAuthorizationService.java` (`authorize`);
  - `HarvesterInstaller.java` (package layout, zip, `--replace` and pinning);
  - `application.properties`, the `pieria.harvest.*` defaults.

## Global Constraints

- **Process:**
  - Work on branch `harvesters-phase-9`.
  - `./gradlew test` must pass before every commit.
  - Never run `nativeCompile`, `nativeDist` or `deployLocal`.
- **The sample:**
  - Python standard library only, and it must run on 3.9.
  - No network calls, and nothing written outside the process's stdout and stderr.
  - It never follows symlinks, and never reads a file outside `directory`.
- **Accuracy:**
  - Every number in `docs/HARVESTERS.md` (limits, timeouts, the guard share, backoff) is copied from the source file named above.
  - Every command and flag named in it exists: `pieria harvester install <dir|zip> [--replace] [--yes]`, `pieria source add|run --dry-run|check|authorize|…`, and `./gradlew :harvester-sdk:conformanceJar`.
- **Production code:** no Java production changes. A finding that seems to need one is a ruling, not a silent edit.
- **Style:**
  - Docs follow the repo's existing Markdown style: sentence-case headings, short paragraphs, and tables for references.
  - Python follows PEP 8, with type hints on public functions.

## Review Focus

1. **A harvester resumed from a mid-pass cursor must not sweep away the items it skipped.** The sample emits `seen` for every file at or before the cursor before it sweeps, and the docs state the rule. Pinned in Task 1, with `test_resume_marks_earlier_notes_seen_before_sweeping`, and by the kit's `sweep-is-complete` in Task 2.
2. **A note that is not valid UTF-8, is empty, is huge, or has a path over the id limit.**
   - Undecodable bytes are replaced, so the run never crashes.
   - Empty files are reported with empty text.
   - Files over `maxFileBytes`, or whose relative path is over `MAX_ID_CHARS`, are skipped with a line on stderr.
   - Text over `MAX_TEXT_CHARS` is cut.
   - Pinned in Task 1.
3. **Symlinks and hidden entries.**
   - A symlink to a file or folder outside `directory` is never read.
   - Dot-files and dot-folders such as `.git` and `.obsidian` are skipped.
   - Pinned in Task 1.
4. **The daemon closes stdout,** on a cancel or a limit kill. The sample exits promptly and quietly, with no traceback storm. Pinned by the kit's `exits-when-stdout-closes` in Task 2, where the fixture is large enough that the check runs rather than being skipped.
5. **The docs' claims drift from the code.** Every limit, default and error-class reaction in `HARVESTERS.md` matches its source. Task 3 ends with a checklist step that greps each number against its source file.

---

### Task 1: The sample `notes` harvester

**Files:**
- Create, in `harvest-examples/notes/`:
  - `harvester.toml`
  - `notes_harvester.py`
  - `test_notes_harvester.py`
  - `README.md`

**Interfaces:**
- **Produces `harvester.toml`:**
  - `id = "notes"`, `name = "Notes folder"`, `version = "1"`, `protocol = 1`
  - `command = ["python3", "./notes_harvester.py"]`, `entrypoint = "./notes_harvester.py"`
  - `capabilities = ["incremental", "deletes"]`
  - `network = []`
  - **Fields:**
    - `directory`: string, required, label `Notes folder`, hint `An absolute path; every Markdown and text file under it is read.`
    - `extensions`: list, label `File types`, hint `Default .md, .markdown and .txt.`
    - `maxFileBytes`: int, default `1000000`, label `Largest file to read, in bytes`
- **Produces `notes_harvester.py`:**
  - `main(argv: list[str], stdin: BinaryIO, stdout: BinaryIO, stderr: TextIO) -> int`, with `if __name__ == "__main__": sys.exit(main(sys.argv[1:], sys.stdin.buffer, sys.stdout.buffer, sys.stderr))`.
  - **Constants**, mirroring `ProtocolLimits`:
    - `MAX_ENVELOPE_BYTES = 1_048_576`
    - `MAX_ID_CHARS = 512`
    - `MAX_TITLE_CHARS = 1000`
    - `MAX_URL_CHARS = 2048`
    - `MAX_TEXT_CHARS = 2_000_000`
    - `MAX_SEEN_IDS = 1000`
    - `CHECKPOINT_EVERY = 25`
- **Behaviour:**
  - **Usage.** Any `argv` other than exactly `check` or `harvest` prints `usage: notes_harvester.py check|harvest` to stderr and returns `2`.
  - **Envelope.**
    - It reads one line from stdin, at most `MAX_ENVELOPE_BYTES`.
    - Empty input, invalid JSON, or `protocol != 1` writes `{"type":"error","class":"config","message":"bad envelope: <reason>"}` and returns `2`.
  - **Config.**
    - `directory` must be a string naming an existing folder. Otherwise it fails `config`: `directory <value> does not exist or is not a folder`.
    - `extensions` must be a list of strings. Each is matched case-insensitively, and a missing leading dot is added.
    - `maxFileBytes` must be a positive int.
    - Any config failure writes one `error` record and returns `1`.
  - **`check`** validates the config and lists the folder, emits nothing else, and returns `0`.
  - **Notes.** The walk is `os.walk(directory, followlinks=False)`, sorted by relative POSIX path.
    - It skips any path segment starting with `.`, any symlink, any file with an unlisted extension, and any file whose relative path is longer than `MAX_ID_CHARS`.
    - A file larger than `maxFileBytes` is skipped with the stderr line `skipping <path>: <n> bytes is over maxFileBytes`.
  - **Document per note:**
    - `id`: the relative POSIX path.
    - `version`: the SHA-256 hex of the bytes.
    - `title`: the first line matching `^#\s+(.+)` in a Markdown file, stripped, else the file name without its extension, then cut to `MAX_TITLE_CHARS`.
    - `url`: `Path.as_uri()`. A URL longer than `MAX_URL_CHARS` skips the file, with the stderr line `skipping <path>: its file URL is too long`.
    - `updatedAt`: the mtime in UTC, ISO-8601 with `Z`.
    - `format`: `markdown` for `.md` and `.markdown`, else `text`.
    - `text`: UTF-8 with `errors="replace"`, cut to `MAX_TEXT_CHARS`.
    - `metadata`: `{"path": <id>}`.
  - **A run.** It reads `cursor.after` (absent or null means the start).
    1. **Resuming.** When `after` is set, it first emits `seen` for every note id `<= after` that still exists, in chunks of `MAX_SEEN_IDS`.
    2. **Reporting.** It reports notes `> after` in order. Each document is followed by `progress {done, total}`, where `total` is the number of notes `> after`. Every `CHECKPOINT_EVERY` documents it writes `checkpoint {"after": <last id>}`.
    3. **Limits.**
       - It stops before the document that would exceed `limits.maxItemsPerRun`.
       - It stops before the document that would push the run's text characters over `limits.maxCharsPerRun`, unless that is the run's first document.
       - On a limit stop it writes `checkpoint {"after": <last reported id>}` and returns `0`, without sweeping.
    4. **A completed pass** writes `checkpoint {}`, then `sweep`, and returns `0`.
  - **Output.**
    - Every record is one JSON line, written and flushed at once.
    - A `BrokenPipeError` or `OSError` on stdout returns `1` silently. It replaces `sys.stdout` with `os.devnull` first, so the interpreter's shutdown flush can't print a traceback.
  - **Unexpected exceptions** write the traceback to stderr, try one `{"type":"error","class":"fatal","message":<str(e)>}`, and return `1`.

- [ ] **Step 1: Write the failing tests in `test_notes_harvester.py`**

The tests use `unittest`, a `tempfile.TemporaryDirectory` of notes, and a helper `run(command, config, cursor=None, limits=None) -> (code, records, stderr)`. The helper calls `main` with `io.BytesIO` streams and decodes each stdout line as JSON. Tests:
- `test_harvest_reports_each_note_then_sweeps`:
  - Notes: `a.md`, `b/c.txt`, `b/d.markdown`.
  - The documents' ids are `["a.md", "b/c.txt", "b/d.markdown"]`.
  - The last two non-progress records are `checkpoint {"cursor": {}}` and `sweep`.
  - The exit code is `0`.
- `test_title_from_first_heading_else_file_name`: `# Runbook` gives `Runbook`, and `notes.txt` gives `notes`.
- `test_version_tracks_the_content`: rewriting a file with the same bytes keeps `version`; changing a byte changes it.
- `test_hidden_symlinked_and_other_files_are_skipped`:
  - Skipped: `.obsidian/x.md`, `.hidden.md`, `pic.png`, and a symlink `link.md` to a file outside the folder.
  - The last case is skipped on Windows with `unittest.skipIf`.
- `test_custom_extensions`: `extensions=["org"]` reads only `x.org`.
- `test_undecodable_empty_and_oversized_notes`:
  - Bytes `b"\xff\xfe ok"` give text containing `ok` and `�`.
  - An empty file gives `text == ""`.
  - A 2,000-byte file with `maxFileBytes=1000` is absent, and stderr contains `over maxFileBytes`.
- `test_item_limit_checkpoints_without_sweep`: 5 notes with `maxItemsPerRun=2` give 2 documents, a final `checkpoint {"after": <second id>}`, and no `sweep`.
- `test_resume_marks_earlier_notes_seen_before_sweeping`:
  - With cursor `{"after": <second id>}`, a `seen` record with the first two ids comes before the first document.
  - The documents are notes 3–5, and the run ends with a sweep.
- `test_char_limit_lets_the_first_note_through`: `maxCharsPerRun=1` gives exactly one document.
- `test_missing_directory_is_a_config_error`: `check` with `directory` `/no/such` gives exit `1` and one record `{"type":"error","class":"config",…}`.
- `test_bad_envelope_and_unknown_command`:
  - The stdin `not json` gives exit `2` and an error record with class `config`.
  - `argv=["login"]` gives exit `2`, and `check|harvest` on stderr.
- `test_closed_stdout_exits_quietly`: a stdout whose `write` raises `BrokenPipeError` gives exit `1`, and stderr holds no `Traceback`.

- [ ] **Step 2: Run them and see them fail**

Run: `python3 -m unittest discover -s harvest-examples/notes -v`
Expected: ERROR, `ModuleNotFoundError: No module named 'notes_harvester'`.

- [ ] **Step 3: Implement `notes_harvester.py` and write `harvester.toml`**

Write it as a tutorial reader would want it: short top-level functions in the order a run uses them (`read_envelope`, `parse_config`, `list_notes`, `document`, `harvest`, `emit`), each with a one-line docstring naming the protocol rule it follows.

- [ ] **Step 4: Run them and see them pass**

Run: `python3 -m unittest discover -s harvest-examples/notes -v`
Expected: OK.

- [ ] **Step 5: Write `harvest-examples/notes/README.md`**

It explains:
- what the sample does;
- how to run its tests;
- how to install it (`pieria harvester install ./harvest-examples/notes`);
- how to add a source (`pieria source add notes --id my-notes --set directory=/abs/path`, then `pieria source run my-notes --dry-run`);
- that on Windows `command` may need `python` instead of `python3`.

It links to `docs/HARVESTERS.md`.

- [ ] **Step 6: Commit** (`./gradlew test` first; it doesn't touch Python yet)

```bash
git add docs/superpowers/plans/2026-11-15-harvester-docs-python-sample.md harvest-examples
git commit -m "Add a sample Python harvester that reads a notes folder"
```

---

### Task 2: Run the sample and the conformance kit in `./gradlew test`

**Files:**
- Create: `modules/harvester-sdk/src/test/java/dev/alvo/pieria/harvester/conformance/PythonSampleTests.java`

**Interfaces:**
- Consumes:
  - `ConformanceTarget.fromManifest(Path)`
  - `ConformanceKit.run(ConformanceTarget, ConformanceOptions)`
  - `ConformanceOptions(ObjectNode config, Map<String,String> secrets, ObjectNode invalidConfig, Duration timeout)`
  - `ConformanceReport.passed()` and `render()`
- **Produces `class PythonSampleTests`:**
  - It is annotated `@EnabledIf("pythonAvailable")`.
  - `static boolean pythonAvailable()` runs `python3 --version` and is true on exit 0 within 10 s, else false. It also catches `IOException`.
  - `SAMPLE = Path.of("../../harvest-examples/notes")`. Gradle runs the tests with the module directory as the working directory, as `BundledHarvestersTests` relies on.

- [ ] **Step 1: Write the tests**

- `sampleUnitTestsPass`: `python3 -m unittest discover -s <SAMPLE absolute>` exits `0` within 120 s. On failure, the assertion message carries the combined output.
- `samplePassesTheConformanceKit`:
  - It writes 60 notes into a `@TempDir`, `note-00.md` to `note-59.md`, each `# Note n\n` followed by 2,000 characters of text. That is enough for checkpoints mid-run and for the stdout-close check to fire.
  - It runs the kit with config `{"directory": <tmp>}`, no secrets, invalid config `{"directory": <tmp>/missing}`, and a 60 s timeout.
  - It asserts `report.passed()`, with `report.render()` as the description.
  - It also asserts the rendered report contains `exits-when-stdout-closes` and `PASS`, on the same line. If the kit skips the check because the sample finished too fast, raise the fixture size rather than accepting the skip.

- [ ] **Step 2: Run them**

Run: `./gradlew :harvester-sdk:test --tests '*PythonSampleTests'`
Expected: PASS. A failing kit check is a sample bug; fix it in `notes_harvester.py`, with a `unittest` case that reproduces it first.

- [ ] **Step 3: Check the skip path**

Run `./gradlew :harvester-sdk:test --tests '*PythonSampleTests'` with `PATH` lacking `python3`, for example `env PATH=/usr/bin/false ./gradlew …` (if Gradle itself needs `PATH`, simulate it instead). Note the outcome in the ledger.
Expected: the class is skipped, not failed.

- [ ] **Step 4: Commit**

```bash
git add modules/harvester-sdk/src/test
git commit -m "Run the Python sample and the conformance kit in the test suite"
```

---

### Task 3: `docs/HARVESTERS.md`

**Files:**
- Create: `docs/HARVESTERS.md`

**Sections, in this order.** Each section's content is fixed below; its wording is yours.

1. **What a harvester is.**
   - A separate process, in any language, that the daemon starts and feeds one request on stdin, and that answers with JSON lines on stdout.
   - It never touches the store or calls a model.
   - It runs with the user's permissions, unsandboxed. It is pinned by checksum at install, and the network hosts it declares are shown before install.
2. **Try it.** Install the sample, add a source, dry-run it and run it, using the README's commands.
3. **The package.**
   - A directory or `.zip` holding `harvester.toml` and the program.
   - A manifest reference table: `id`, `name`, `version`, `protocol`, `command`, `entrypoint`, `capabilities`, `network` and `[[config]]`.
   - The rules from `ManifestParser`:
     - `command[0]` is a bare program found on `PATH`, or a `./` path;
     - a bare interpreter needs `entrypoint`, naming its `./` script;
     - no `..` segments.
   - A table of field kinds: string, secret, int, bool, enum (`options`) and list, each with `required`, `default` and `hint`. A secret can't have a default.
   - Each capability and what declaring it promises.
   - Install, `--replace`, and why every edit to the entrypoint or manifest needs a reinstall: the pins are checked before each launch.
4. **The protocol (v1).**
   - **The commands:**
     - `check`, which validates and may emit only `secret_update`;
     - `harvest`;
     - `authorize`, sent only to `oauth-device-code` harvesters, which may emit one `device_code`, any `secret_update`s, and on failure one `error`.
   - **Exit codes:** `0`, `1` and `2`.
   - **The envelope:** `protocol`, `config`, `secrets` (only the declared secret fields), `cursor` and `limits`. It is one line, newline-terminated, at most 1 MiB.
   - **A record table:** `document`, `thread`, `tombstone`, `seen`, `sweep`, `checkpoint`, `progress`, `secret_update`, `device_code` and `error`, with every field, which are required, and their limits from `ProtocolLimits`.
   - **The rules:**
     - one record per line, flushed;
     - backpressure: the daemon reads the next line only when it is ready;
     - a checkpoint means everything before it is stored, and a failed run's checkpoints stand;
     - `sweep` is last apart from `progress`, takes effect only on exit 0, and must cover every live item, as an item or in `seen`, whatever cursor the run started from;
     - the mass-delete guard: share `0.2`, floor `2`, and a sweep that would empty the source; `pieria source accept-sweep`.
   - **Error classes,** and what the daemon does for each:
     - `auth` moves the source to `needs_auth`;
     - `rate_limited` honours `retryAfter`;
     - `transient` and `fatal` back off exponentially, up to `max-backoff` 24 h, with jitter;
     - `config`.
     - A protocol violation fails the run `fatal`.
   - **Limits:** the daemon enforces `maxItemsPerRun` and `maxCharsPerRun` and fails a run that exceeds them. The run timeout is 6 h; `check` and `authorize` code delivery time out at 60 s.
   - **The child environment:** only the variables in `KEPT_ENVIRONMENT`, no secrets in argv or the environment, and stderr captured up to a cap and scrubbed of secret values.
   - **Stdout closed means stop.**
5. **Items and edits.**
   - Stable ids.
   - `version` lets the daemon skip unchanged items at no cost.
   - A thread whose earlier turns are unchanged only appends; an edited or removed turn re-extracts the thread.
   - Timestamps are when the text was written, so relative dates resolve correctly.
   - `tombstone` versus `seen`/`sweep`.
   - Harvested text is untrusted: types are coerced to fact or event, and keys are namespaced.
6. **Sign-in with a device code.** The `authorize` flow end to end, from both sides, citing phase 7's amendment: `pieria source authorize`, the 30 s grace past expiry, and `needs_auth` → `active`.
7. **Testing with the conformance kit.**
   - `./gradlew :harvester-sdk:conformanceJar`, then `java -jar modules/harvester-sdk/build/libs/harvester-conformance.jar --manifest … --config … [--secrets …] [--invalid-config …] [--timeout-seconds n]`.
   - A table of every check name in `ConformanceKit`, with what it asserts and the common reasons it fails.
8. **Writing one in Java.** `Harvester`, `HarvesterRunner.main`, and the `check`/`harvest`/`authorize` defaults. Point at the bundled harvesters in `modules/harvesters` as examples.
9. **A walk through the sample.** Follow `notes_harvester.py` top to bottom: envelope, config, listing, a document, limits, checkpoints, resume with `seen`, and the sweep. Quote short excerpts by function name.

- [ ] **Step 1: Write `docs/HARVESTERS.md`**

- [ ] **Step 2: Check every number against its source**

For each limit, default and timeout in the document, run a `grep` for it in the named source file. List the pairs in the ledger. Expected: every value is found, unchanged.

- [ ] **Step 3: Check every command exists**

Run `./gradlew :cli:test --tests '*SourceCommandTests'` (green already), and `grep` `SourceCommand.java` and `HarvesterCommand.java` for each subcommand the document names.
Expected: all present.

- [ ] **Step 4: Commit** (`./gradlew test` first)

```bash
git add docs/HARVESTERS.md
git commit -m "Document how to write a harvester"
```

---

### Task 4: Close out the phase

**Files:**
- Modify:
  - `docs/phases/phase-16-knowledge-harvesters.md`: the status line becomes all nine phases complete, plus an amendment bullet, "Developer docs and a Python sample, settled in phase 9"
  - `README.md`: in "Bring in outside knowledge", one sentence linking `docs/HARVESTERS.md` and `harvest-examples/notes`
  - `AGENTS.md`: in the `harvester-sdk` bullet, add that `docs/HARVESTERS.md` is the author-facing reference and must change with the protocol

- [ ] **Step 1: Write the three edits**

The amendment covers:
- the sample's design: reporting everything and letting `version` dedupe, `seen` on resume, and skipping symlinks and dot entries;
- the two test layers, and the skip when `python3` is absent;
- that `HARVESTERS.md` and the SDK move together.

- [ ] **Step 2: Run the suite and commit**

Run: `./gradlew test`
Expected: PASS.

```bash
git add docs README.md AGENTS.md
git commit -m "Close out the knowledge-harvesters phases"
```

---

## Manual verification (yours, after `./gradlew :daemon:deployLocal`)

1. Run `pieria harvester install ./harvest-examples/notes`. It shows the manifest, with no network hosts, and installs.
2. **Add a source:**
   1. Run `pieria source add notes --id my-notes --set directory=$HOME/notes`; the check passes.
   2. Run `pieria source run my-notes --dry-run`, then a real run.
   3. Recall finds a note's fact, labelled `untrusted · notes · …`.
3. **Delete a note** and run again: its memories are forgotten. Delete most of them: the guard holds the sweep back, and `pieria source accept-sweep my-notes` lets the next run through.
4. **Follow the tutorial** in `docs/HARVESTERS.md` from a clean checkout. Every command works as written.
