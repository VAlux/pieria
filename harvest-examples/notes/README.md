# Sample harvester: a notes folder

`notes_harvester.py` turns every Markdown and text file under a folder into a Pieria document. It
is written for people learning to build a harvester: one Python file, standard library only, that
speaks the harvester protocol directly. [docs/HARVESTERS.md](../../docs/HARVESTERS.md) is the
reference it follows and walks through it.

What it does on each run:

- Reports every `.md`, `.markdown` and `.txt` file (or the `extensions` you set), in path order.
  The id is the path relative to the folder, so it is stable across runs. The daemon fingerprints
  each note's title, URL and text and skips any that hasn't changed, so reporting every note on
  every run costs nothing extra.
- Checkpoints every 25 notes, and resumes from the last checkpoint after a limit or a failure.
- When a pass is complete, sends `sweep`, so notes you deleted are forgotten. A resumed run first
  names the notes before its cursor in `seen`, so they aren't swept away.
- Never follows symlinks, and skips hidden files and folders such as `.git` and `.obsidian`.

## Test it

```bash
python3 -m unittest discover -s harvest-examples/notes -v
```

`./gradlew test` runs these tests and the conformance kit against the sample, when `python3` is on
`PATH`.

## Use it

```bash
pieria harvester install ./harvest-examples/notes      # shows the manifest and asks first
pieria source add notes --id my-notes --set directory=/absolute/path/to/notes
pieria source run my-notes --dry-run                    # counts the work without calling the model
pieria source resume my-notes                           # new sources start paused
```

After editing `notes_harvester.py` or `harvester.toml`, install again with `--replace`: the daemon
checks both against the checksums it pinned at install and refuses to run a changed harvester.

On Windows the interpreter is often `python` rather than `python3`; change the first entry of
`command` in `harvester.toml` to match.
