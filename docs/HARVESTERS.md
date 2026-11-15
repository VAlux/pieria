# Writing a harvester

A harvester brings knowledge into Pieria from somewhere else: a website, an issue tracker, a chat,
a folder of notes. This guide is the reference for building one. It covers the package, the
manifest and protocol v1, how the daemon treats what a harvester reports, and how to test one with
the conformance kit. It ends with a walk through a complete sample written in Python.

The design behind all of this is in [phase-16-knowledge-harvesters.md](phases/phase-16-knowledge-harvesters.md).
Where the two differ, the code wins; this guide follows the code.

## What a harvester is

A harvester is a separate program, in any language. The daemon starts it, writes one request line
to its stdin, and reads JSON records from its stdout, one per line. The daemon stays the only
writer to the memory store, and does all extraction itself. A harvester never touches the store and
never calls a model; it only reports what it finds.

A configured instance of a harvester is a **source**: a harvester plus its settings, credentials,
target profile and schedule. One harvester can back many sources, such as two Jira sites.

**Trust.** A harvester runs with your user's permissions and is not sandboxed. Installing one is an
explicit act. `pieria harvester install` shows the manifest and the network hosts it declares, and
asks before it installs. The install pins the SHA-256 of the manifest and of the entrypoint, and the
daemon checks both before every launch.

**Harvested text is untrusted.** It comes from other people and the open web, so the daemon labels
it and limits what it can become:
- harvested memories are only ever `fact` or `event`;
- their keys are namespaced to the source, so they can't supersede what you established yourself;
- recall marks them as untrusted.

## Try it

The repository ships a sample, [`harvest-examples/notes`](../harvest-examples/notes). It turns a
folder of Markdown and text files into documents.

```bash
pieria harvester install ./harvest-examples/notes          # shows the manifest and asks first
pieria source add notes --id my-notes --set directory=/absolute/path/to/notes
pieria source run my-notes --dry-run                        # counts the work without calling the model
pieria source run my-notes                                  # a real run; or `source resume` to schedule it
```

New sources start paused, so nothing expensive happens until you ask. `pieria source --help` lists
the other commands, and the console's Sources page (`/console/p/<profile>/sources`) does the same.

## The package

A harvester is a directory, or a `.zip` of one, holding `harvester.toml` and the program it runs.
`pieria harvester install <dir|zip>` copies it under the daemon's data directory, in
`harvesters/<id>/`. After any change to the manifest or the entrypoint, install again with
`--replace`. The daemon refuses to start a harvester whose files no longer match their pins, and
`pieria harvester doctor` reports which ones don't.

### The manifest

| Key | Required | Meaning |
|---|---|---|
| `id` | yes | Stable identifier, such as `notes`: lowercase letters, digits, `-` and `_`, starting with a letter or digit, at most 64 characters. Sources name the harvester by it. |
| `name` | yes | Display name. |
| `version` | yes | Your release version, shown to users. |
| `protocol` | yes | Must be `1`. |
| `command` | yes | The program and its arguments, as an array of strings. The daemon appends the subcommand (`check`, `harvest` or `authorize`). |
| `entrypoint` | when `command[0]` is a bare interpreter | The `./` script the install pins. It must appear in `command`. |
| `capabilities` | no | Any of `incremental`, `deletes` and `oauth-device-code`; see below. |
| `network` | no | Hosts the harvester talks to, shown to the user at install. It is informational only; nothing enforces it. |
| `[[config]]` | no | The settings a source asks for. The CLI's prompts and the console's forms are built from these. |

**The rules for `command`:**
- `command[0]` is either a bare program name found on `PATH`, such as `python3` or `node`, or a `./` path inside the harvester's directory. Absolute paths are refused, because they break when an install moves.
- No entry may contain a `..` segment.
- A `./` program is its own entrypoint. A bare interpreter must name its script in `entrypoint`, or the install would pin nothing that matters.

**Capabilities.** Each one is a promise:

| Capability | Declare it when… |
|---|---|
| `incremental` | `harvest` emits `checkpoint` records and continues from the cursor it is given. |
| `deletes` | `harvest` emits `tombstone`, or `seen` and `sweep`. Emitting `tombstone` or `sweep` without it is a conformance failure. |
| `oauth-device-code` | the harvester implements `authorize`; see [Sign-in with a device code](#sign-in-with-a-device-code). |

### Config fields

Each `[[config]]` table has `key`, `kind` and `label`, and optionally `hint`, `required`
(`true`/`false`), `default` and, for an enum, `options`.

| `kind` | Value in `config` | Notes |
|---|---|---|
| `string` | string | |
| `int` | integer | |
| `bool` | `true`/`false` | |
| `enum` | one of `options` | |
| `list` | array of strings | The CLI splits on commas; the console takes one item per line. |
| `secret` | — | Never in `config`. The value is stored in the daemon's secret store (`harvesters/secrets.json`, mode 0600) and arrives in the request's `secrets`. A secret can't have a default. |

The daemon checks required fields and value kinds when a source is added or updated. Your
harvester still validates its own settings in `check`, because only it knows what they mean.

## The protocol (v1)

### Commands and exit codes

The daemon runs `<command…> <subcommand>`:

| Subcommand | What it does | Records it may emit |
|---|---|---|
| `check` | Validates the config and credentials. Returning `0` means they work. | Only `secret_update`, and one `error` on failure. |
| `harvest` | Reports the source's items, from the cursor, within the limits. | Anything except `device_code`. |
| `authorize` | Signs the user in. Sent only to harvesters that declare `oauth-device-code`. | At most one `device_code`, any `secret_update`s, and one `error` on failure. |

| Exit code | Meaning |
|---|---|
| `0` | Done. A `harvest` that exits `0` lets its `sweep` take effect. |
| `1` | Failed. Report why in an `error` record before exiting. |
| `2` | Usage error: an unknown subcommand, or a request the harvester can't read. |

An unknown subcommand should print a usage line to stderr and exit `2` without writing to stdout.

### The request

The daemon writes exactly one line of JSON to stdin, ending in `\n`, of at most 1 MiB (1,048,576
bytes), then closes stdin:

```json
{"protocol":1,
 "config":{"directory":"/home/me/notes"},
 "secrets":{"token":"…"},
 "cursor":{"after":"b/c.md"},
 "limits":{"maxItemsPerRun":500,"maxCharsPerRun":2000000}}
```

- `config` is always an object: the source's settings.
- `secrets` holds only the secrets the manifest declares. Secrets never travel in argv or the environment.
- `cursor` is whatever your last `checkpoint` said, exactly, or absent on a first run or after `pieria source update --reset-cursor`.
- `limits` holds `maxItemsPerRun` and `maxCharsPerRun` when the source sets them. Either may be absent.

A request that isn't valid, or has a `protocol` other than `1`, should produce an `error` with class
`config`, starting `bad envelope:`, and exit `2`.

### Records

Every record is one line of JSON with a `type`, written to stdout and flushed at once. A line may
be at most 16 MiB (16,777,216 bytes). A record that breaks any rule below is a protocol violation,
and the daemon fails the run as `fatal`.

| `type` | Fields | Meaning |
|---|---|---|
| `document` | `id`, `version`?, `title`, `url`, `author`?, `updatedAt`, `format`, `text`, `metadata`? | A page or other single body of text. `format` is `markdown` or `text`. |
| `thread` | `id`, `title`, `url`, `turns`, `metadata`? | A conversation, such as a ticket with comments. Each turn is `{id, author, timestamp, text}`. |
| `tombstone` | `id` | The source says this item is gone. |
| `seen` | `ids` | These items still exist; used ahead of a `sweep`. |
| `sweep` | — | Every live item was reported in this run, so anything else is gone. |
| `checkpoint` | `cursor` | Everything before this record is complete. `cursor` is any JSON value except `null`, returned on the next run; to start over next time, send `{}`. |
| `progress` | `done`, `total`? | How far the run has got, for progress displays. |
| `secret_update` | `name`, `value` | Replaces a stored secret, such as a rotated refresh token. |
| `device_code` | `verificationUri`, `userCode`, `message`?, `expiresIn` | A sign-in code for the user; `authorize` only. |
| `error` | `class`, `message`, `retryAfter`? | The run failed, and why. |

`?` marks an optional field. Timestamps (`updatedAt`, turn `timestamp`) are ISO-8601 instants, such
as `2026-11-15T09:30:00Z`.

**Limits** (from `ProtocolLimits`). Every character count, here and in `maxCharsPerRun`, is in UTF-16 code units, as Java's `String.length()` counts them: a character outside the Basic Multilingual Plane, such as most emoji, counts as 2. Count the same way, or a title or a run that fits by your measure can be refused.

| Limit | Value |
|---|---|
| `id` (and each `seen` id, each turn id), a document's `version`, a `metadata` key | 512 characters |
| `title` | 1,000 characters |
| `url`, `verificationUri` | 2,048 characters |
| `author` | 256 characters |
| a document's `text`, or the sum over a thread's turns | 2,000,000 characters |
| turns per thread | 5,000; at least one |
| ids per `seen` | 1,000; at least one |
| a `checkpoint`'s cursor, as JSON | 900,000 characters |
| `metadata` | 32 entries; keys up to 512 and values up to 1,024 characters |
| an `error` message | 2,000 characters (longer is cut) |
| a `device_code` `message` | 2,000 characters |
| `userCode` | 64 characters |
| `expiresIn` | 1 to 3,600 seconds |

Further rules:
- `id`, `title`, `url`, `text` and `turns` are required; `id` and `url` must not be blank.
- Turn ids must be unique within a thread, and a turn's `author` must not be blank.
- `verificationUri` must be `https://`.
- A `secret_update`'s `name` and `value` must not be blank.
- `metadata` is an object whose values are all strings.
- Every line must be valid UTF-8.
- `retryAfter` and `progress` counts must not be negative.

### The rules of a run

- **One record at a time.** The daemon reads the next line only when it has dealt with the last one, which for an item means extracting and storing it. Blocking on a write is how a harvester is told to slow down, so write each record as it is ready and don't buffer.
- **Checkpoints.** When the daemon reads a `checkpoint`, everything before it is already stored. It saves the cursor, and the next run receives it. A checkpoint from a run that later fails still stands, so a run that dies halfway resumes where it got to. Checkpoint regularly; the bundled harvesters checkpoint every 25 items.
- **Deletions, two ways:**
  - A **`tombstone`** names one item the source says is gone. Use it when the source reports deletions, as a change feed does. A tombstone for an item the daemon never stored is ignored.
  - A **`sweep`** says the run reported every live item, either as an item or by id in `seen`. Everything else the source holds is then deleted. A sweep:
    - must be the last record apart from `progress`;
    - takes effect only if the run exits `0`;
    - must cover every live item *whatever cursor the run started from*. A run that resumed mid-pass must name the items before its cursor in `seen`.
  - A run cut short by a limit must not sweep.
- **The mass-delete guard.** A sweep that would delete more than 20% of the source's items (and more than 2 of them), or every item, is held back. Nothing is deleted, and the source is flagged. A revoked permission or an outage would otherwise wipe it. The user lets the next complete sweep through with `pieria source accept-sweep <id>`, or the console's *Allow these deletions*.
- **Limits.** Honour `maxItemsPerRun` and `maxCharsPerRun`: stop before the item that would exceed them, checkpoint, and exit `0`. The daemon enforces both, and fails a run that goes over them as `fatal`.
- **Timeouts.** A `harvest` run is killed after 6 hours. `check` is killed after 60 seconds. `authorize` must emit its code within 60 seconds.
- **Stdout closing means stop.** When the daemon cancels a run, or shuts down, it closes your stdout. A failed write is the signal to exit at once, quietly.
- **Stderr is for people.** The daemon keeps up to 64 KiB of it, removes every secret value it knows, and stores the last 4,000 characters with the run, where the console's run history shows it. `pieria source runs <id>` shows each run's error message.

### Error classes

Report a failure with one `error` record, then exit `1`.

| `class` | Use it when… | What the daemon does |
|---|---|---|
| `auth` | the credentials are missing, wrong, expired or lack a permission. | Moves the source to `needs_auth`. It stops running until the user fixes its credentials (or signs in again) and resumes it. |
| `rate_limited` | the source asked you to wait. Set `retryAfter` in seconds. | Waits at least `retryAfter`, and the failure counts toward back-off. |
| `transient` | the source is unreachable, timed out, or answered with something broken. | Backs off and retries. |
| `config` | a setting is wrong in a way only the user can fix. | Backs off. `check` reports it to the user. |
| `fatal` | anything else. | Backs off. |

After a failure, the next run waits the source's interval times 2^(consecutive failures), capped at
24 hours (`pieria.harvest.max-backoff`), and never less than `retryAfter`. A success resets the count.

**The child's environment.** Only `PATH`, `HOME`, `SystemRoot`, `USERPROFILE`, `TEMP`, `TMP` and the
`HTTP(S)_PROXY`/`NO_PROXY` variables (both cases) are passed on. The working directory is the
harvester's install directory.

## Items and edits

- **Ids must be stable across runs.** The daemon tracks each item by `id`. A different id for the same thing looks like one item deleted and another added.
- **Unchanged items cost nothing.** The daemon fingerprints each item and skips it when nothing it extracts from has changed:
  - for a document: the source's mode, the `title`, the `url` and the `text`;
  - for a thread: the same, but for every turn.

  So reporting every item on every run is correct, just slower to read. The optional `version` is for your own bookkeeping; the daemon doesn't use it.
- **Threads grow cheaply.** A thread whose earlier turns are unchanged only has its new turns extracted. An edited or removed earlier turn re-extracts the whole thread. Order turns oldest first, and keep their ids stable.
- **An edited item re-extracts it.** Memories the new text no longer supports are retired, but stay visible to recall as of an earlier time. A deleted item takes its memories with it.
- **Timestamps are when the text was written.** The daemon resolves relative dates ("yesterday", "next month") against `updatedAt` and each turn's `timestamp`, so send the real ones, not the time of the run.
- **Mode.** In `full` mode the daemon extracts memories. In `raw` mode it only makes the text searchable, with no model calls. Mode belongs to the source, not to your harvester.

## Sign-in with a device code

A harvester that declares `oauth-device-code` can sign the user in itself, the way the bundled
`teams` harvester does with Microsoft.

1. The user runs `pieria source authorize <id>`, or *Sign in* in the console.
2. The daemon runs your harvester's `authorize` with the source's config, and any secrets it already has.
3. Your harvester asks the provider for a code, then emits one `device_code`: `verificationUri`, `userCode`, the provider's `message`, and `expiresIn`. It must do so within 60 seconds.
4. The CLI or console shows the message and the code, and the user signs in in a browser. Meanwhile your harvester polls the provider.
5. On success, emit `secret_update` with the credential, such as a refresh token, then exit `0`. The daemon saves it and moves a `needs_auth` source back to `active`. Exiting `0` without saving anything is reported as a failure.
6. On failure, emit `error`, for example `auth` "the sign-in was declined", and exit `1`.

The daemon kills `authorize` 30 seconds after the code expires. A second `authorize`, removing the
source, or stopping the daemon also kills it.

Later runs receive the credential in `secrets`. If the provider rotates it on use, emit
`secret_update` with the new value as early as possible in `check` or `harvest`, before anything
can fail, so a run that dies halfway still leaves a working credential behind.

## Testing with the conformance kit

The conformance kit runs your harvester the way the daemon does, several times, and checks what it
reports. It works with a harvester in any language.

```bash
./gradlew :harvester-sdk:conformanceJar
java -jar modules/harvester-sdk/build/libs/harvester-conformance.jar \
  --manifest path/to/harvester.toml \
  --config config.json \
  --secrets secrets.json \
  --invalid-config bad-config.json \
  --timeout-seconds 60
```

Each `.json` file holds one object. Only `--manifest` and `--config` are required. Give it a source
with enough items for several checkpoints, so the resume and stdout checks have something to work on.
The kit exits `0` when no check fails, `1` when one does, and `2` for a usage error.

| Check | What it asserts | Usual reason it fails |
|---|---|---|
| `check-accepts-valid-config` | `check` exits `0` with the valid config and emits only `secret_update`. | `check` emits other records, or fails on a good config. |
| `check-rejects-invalid-config` | `check` fails with an `auth` or `config` error on the invalid config. Skipped without `--invalid-config`. | It exits `0`, or fails as `fatal`. |
| `harvest-completes` | A full run exits `0` within the timeout and breaks no protocol rule. | A crash, a hang, or an invalid record. |
| `secrets-stay-private` | No secret value appears in any record other than `secret_update`, or in stderr. Skipped with no secrets. | A secret in an error message or a URL. |
| `harvest-emits-items` | The full run reports at least one item. | The test source is empty. |
| `capabilities-declared` | `tombstone` and `sweep` appear only with `deletes`, and `incremental` comes with checkpoints. | A missing capability, or no checkpoint. |
| `ids-stable-across-runs` | Two full runs report identical items. | Ids or text derived from the clock or random values. |
| `resume-loses-nothing` | Resuming from the last checkpoint succeeds, and resuming from earlier ones reports every item that followed them. Needs `incremental`. | A cursor that skips ahead, or a resumed run that can't read its own cursor. |
| `sweep-is-complete` | Any `sweep` is the last record apart from `progress`, and its run reported every item the full run found. | A resumed run that sweeps without `seen` for the items before its cursor. |
| `limit-respected` | Runs limited to 1 item, and to 1 character, stay within the limit. They don't sweep, they make progress and leave a checkpoint, and the next run continues. | Ignoring `limits`, sweeping after a limit stop, or stalling. |
| `exits-when-stdout-closes` | The harvester exits within a few seconds of its stdout closing. Skipped when it finishes before that. | Swallowing the write error and carrying on. |

## Writing one in Java

The `harvester-sdk` module (`modules/harvester-sdk`) has the protocol records, a runner, and the
kit as a library. Implement `Harvester`:

```java
public final class MyHarvester implements Harvester {
  public static void main(String[] args) {
    HarvesterRunner.main(new MyHarvester(), args);
  }

  @Override
  public void check(HarvestRequest request, RecordSink sink) throws HarvesterException { … }

  @Override
  public void harvest(HarvestRequest request, RecordSink sink) throws HarvesterException { … }
}
```

`HarvesterRunner` reads and validates the request, gives `check` a sink that accepts only
`secret_update`, and turns a `HarvesterException(ErrorClass, message, retryAfterSeconds)` into an
`error` record and exit `1`. It scrubs secret values from error messages, exits when stdout closes,
and validates every record as you emit it. `authorize` has a default that fails as `config`
"this harvester has no sign-in"; override it for `oauth-device-code`. In a test, run
`ConformanceKit.run(ConformanceTarget.fromManifest(path), options)` and assert `report.passed()`.

The bundled harvesters in `modules/harvesters` (`html`, `jira`, `confluence` and `teams`) are
complete examples. The SDK isn't published to a repository yet; build its jar from source with
`./gradlew :harvester-sdk:jar`, and put Jackson 3 (`tools.jackson.core:jackson-databind`) on the classpath beside it.

## A walk through the sample

[`harvest-examples/notes/notes_harvester.py`](../harvest-examples/notes/notes_harvester.py) is
about 260 lines of standard-library Python. It speaks the protocol directly, without an SDK. Its
manifest runs `["python3", "./notes_harvester.py"]` and names `./notes_harvester.py` as the
entrypoint, because `python3` is a bare interpreter.

- **`main`** checks the subcommand, printing the usage line and returning `2` for anything but `check` or `harvest`. It reads the request, runs the command, and turns every failure into one `error` record. A `HarvestError` carries its class; anything unexpected is `fatal`, with the traceback on stderr. A failed write to stdout becomes `StdoutClosed`, and `main` returns `1` without another word.
- **`read_envelope`** reads one line of at most 1 MiB. It refuses anything that isn't an object with `"protocol": 1` and a `config` object, as `config` "bad envelope: …".
- **`Config`** validates `directory`, `extensions` and `maxFileBytes`, raising `config` errors the user can act on.
- **`list_notes`** walks the folder without following symlinks, skips dot-files and dot-folders and other file types, and sorts by the path relative to the folder. That path is the item's id, so it is stable across runs.
- **`document`** builds one `document`:
  - The title is the first `# ` heading, or the file name.
  - `updatedAt` is the file's modification time, so dates in the note resolve correctly.
  - The text is decoded as UTF-8, with undecodable bytes replaced, and cut to the protocol's limit. Lengths are counted in UTF-16 code units by `utf16_len`, as the daemon counts them, and `cut_utf16` never splits a character.
  - Files over `maxFileBytes` are skipped with a note on stderr.
- **Reading errors.** `list_notes` passes `os.walk` an `onerror` that fails the run as `transient`: a folder that can't be listed would otherwise drop out, and the sweep would delete its notes. A single note that can't be read is skipped with a line on stderr and named in `seen`, so its memories survive; one deleted during the run is just skipped. A file name that isn't valid UTF-8 can't be an id, so it is skipped too.
- **`harvest`** is the run:
  1. If the request has a cursor `{"after": <id>}`, it first emits `seen` with every note up to that id, in chunks of 1,000.
  2. It reports the notes after the cursor in order, each followed by `progress`, with a `checkpoint {"after": <id>}` every 25.
  3. If a limit stops it, it checkpoints where it got to and returns, without sweeping.
  4. Otherwise it names any notes it couldn't read in `seen`, ends the pass with `checkpoint {}`, so the next run starts over, then sends `sweep`.
- **`Output.emit`** writes one JSON line and flushes, so each record reaches the daemon as it happens.

The sample's own tests, in `test_notes_harvester.py`, cover each of these rules. `./gradlew test`
runs them and the conformance kit against the sample whenever `python3` is on `PATH`.
