#!/usr/bin/env python3
"""A sample Pieria harvester: every Markdown and text file under a folder, as one document each.

It speaks harvester protocol v1 directly, with only the standard library, so the whole protocol is
visible in one file. See docs/HARVESTERS.md for the reference this follows.

The daemon starts it as `python3 ./notes_harvester.py check|harvest`, writes one JSON request line
to stdin, and reads JSON records from stdout, one per line.
"""

from __future__ import annotations

import hashlib
import json
import os
import re
import sys
import traceback
from datetime import datetime, timezone
from pathlib import Path, PurePosixPath
from stat import S_ISREG
from typing import BinaryIO, Iterator, Optional, TextIO

# Protocol v1 limits, from ProtocolLimits.java. A record over any of them fails the whole run.
MAX_ENVELOPE_BYTES = 1_048_576
MAX_ID_CHARS = 512
MAX_TITLE_CHARS = 1000
MAX_URL_CHARS = 2048
MAX_TEXT_CHARS = 2_000_000
MAX_SEEN_IDS = 1000

# How often to tell the daemon "everything before this is done". A checkpoint is the cursor the next
# run starts from, and it stands even if this run later fails.
CHECKPOINT_EVERY = 25

DEFAULT_EXTENSIONS = (".md", ".markdown", ".txt")
MARKDOWN = (".md", ".markdown")
DEFAULT_MAX_FILE_BYTES = 1_000_000
HEADING = re.compile(r"^#\s+(.+)$", re.MULTILINE)
USAGE = "usage: notes_harvester.py check|harvest"

EXIT_OK = 0
EXIT_FAILED = 1
EXIT_USAGE = 2


class HarvestError(Exception):
    """A failure the daemon should see as an `error` record. `error_class` decides how it retries."""

    def __init__(self, error_class: str, message: str):
        super().__init__(message)
        self.error_class = error_class


def utf16_len(text: str) -> int:
    """Length as the daemon counts it: Java's String.length(), in UTF-16 code units. An emoji is 2."""
    return len(text.encode("utf-16-le", "surrogatepass")) // 2


def cut_utf16(text: str, limit: int) -> str:
    """At most `limit` UTF-16 code units, never splitting a character in two."""
    if utf16_len(text) <= limit:
        return text
    return text.encode("utf-16-le", "surrogatepass")[: limit * 2].decode("utf-16-le", "ignore")


class StdoutClosed(Exception):
    """The daemon stopped reading. Stop at once; there is no one left to tell."""


class Output:
    """Writes one record per line and flushes it, so the daemon sees each record as it happens."""

    def __init__(self, stream: BinaryIO):
        self.stream = stream

    def emit(self, record: dict) -> None:
        line = json.dumps(record, ensure_ascii=False, separators=(",", ":")) + "\n"
        try:
            self.stream.write(line.encode("utf-8"))
            self.stream.flush()
        except (BrokenPipeError, OSError) as e:
            raise StdoutClosed() from e


def read_envelope(stdin: BinaryIO) -> dict:
    """The request is one JSON line: protocol, config, secrets, cursor and limits."""
    line = stdin.readline(MAX_ENVELOPE_BYTES + 2)
    if not line:
        raise HarvestError("config", "bad envelope: stdin was empty")
    if line.endswith(b"\n"):
        line = line[:-1]
    # The limit is on the line itself, not counting its newline.
    if len(line) > MAX_ENVELOPE_BYTES:
        raise HarvestError("config", "bad envelope: over the size limit")
    try:
        request = json.loads(line)
    except ValueError as e:
        raise HarvestError("config", f"bad envelope: {e}") from e
    if not isinstance(request, dict) or not isinstance(request.get("config"), dict):
        raise HarvestError("config", "bad envelope: config must be an object")
    if request.get("protocol") != 1:
        raise HarvestError("config", f"bad envelope: unsupported protocol version: {request.get('protocol')}")
    return request


class Config:
    """The source's settings, validated. A bad setting is a `config` error, which the user fixes."""

    def __init__(self, raw: dict):
        directory = raw.get("directory")
        if isinstance(directory, str) and directory.strip() and not Path(directory).is_absolute():
            # A relative path would be read against the harvester's own install folder.
            raise HarvestError("config", f"directory must be an absolute path: {directory}")
        if not isinstance(directory, str) or not directory.strip() or not Path(directory).is_dir():
            raise HarvestError("config", f"directory {directory} does not exist or is not a folder")
        self.root = Path(directory).resolve()

        extensions = raw.get("extensions")
        if extensions is None:
            extensions = list(DEFAULT_EXTENSIONS)
        if not isinstance(extensions, list) or not all(isinstance(e, str) and e.strip() for e in extensions):
            raise HarvestError("config", "extensions must be a list of file types, such as .md")
        self.extensions = {e.strip().lower() if e.strip().startswith(".") else "." + e.strip().lower() for e in extensions}

        max_bytes = raw.get("maxFileBytes", DEFAULT_MAX_FILE_BYTES)
        if not isinstance(max_bytes, int) or isinstance(max_bytes, bool) or max_bytes < 1:
            raise HarvestError("config", "maxFileBytes must be a positive whole number")
        self.max_file_bytes = max_bytes


def list_notes(config: Config, stderr: TextIO) -> list[tuple[str, Path]]:
    """Every note as (id, path), sorted by id. The id is the path relative to the folder, so it stays
    the same across runs. Symlinks are never followed, so nothing outside the folder is read, and
    hidden entries such as .git or .obsidian are skipped."""
    def unreadable(error: OSError) -> None:
        # Leaving a folder out would make the sweep delete its notes, so the run fails instead.
        where = Path(error.filename).relative_to(config.root).as_posix() if error.filename else "the folder"
        raise HarvestError("transient", f"could not read {where}: {error.strerror or error}")

    notes = []
    for current, folders, files in os.walk(config.root, followlinks=False, onerror=unreadable):
        folders[:] = [f for f in folders if not f.startswith(".") and not (Path(current) / f).is_symlink()]
        for name in files:
            path = Path(current) / name
            if name.startswith(".") or path.is_symlink() or path.suffix.lower() not in config.extensions:
                continue
            note_id = PurePosixPath(path.relative_to(config.root).as_posix()).as_posix()
            try:
                note_id.encode("utf-8")
            except UnicodeEncodeError:
                printable = note_id.encode("utf-8", "backslashreplace").decode("utf-8")
                stderr.write(f"skipping {printable}: its name is not valid UTF-8\n")
                continue
            if utf16_len(note_id) > MAX_ID_CHARS:
                stderr.write(f"skipping {note_id[:80]}...: its path is too long to be an id\n")
                continue
            notes.append((note_id, path))
    notes.sort(key=lambda note: note[0])
    return notes


def document(note_id: str, path: Path, config: Config, stderr: TextIO) -> Optional[dict]:
    """One `document` record, or None when the note can't be one. The daemon fingerprints the title,
    URL and text itself and skips a document that hasn't changed, so reporting an unchanged note costs
    nothing. `version` is optional; here it is a hash of the bytes, for anyone reading the records."""
    # Opened without following a symlink: the note may have been swapped for one since it was listed,
    # and a link could point outside the folder. Where O_NOFOLLOW is missing, check just before.
    if path.is_symlink():
        raise OSError(f"{note_id} became a symlink")
    with os.fdopen(os.open(path, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0)), "rb") as handle:
        stat = os.fstat(handle.fileno())
        if not S_ISREG(stat.st_mode):
            stderr.write(f"skipping {note_id}: it is no longer a regular file\n")
            return None
        if stat.st_size > config.max_file_bytes:
            stderr.write(f"skipping {note_id}: {stat.st_size} bytes is over maxFileBytes\n")
            return None
        url = path.as_uri()
        if utf16_len(url) > MAX_URL_CHARS:
            stderr.write(f"skipping {note_id}: its file URL is too long\n")
            return None
        data = handle.read(config.max_file_bytes + 1)
    text = cut_utf16(data.decode("utf-8", errors="replace"), MAX_TEXT_CHARS)
    markdown = path.suffix.lower() in MARKDOWN
    heading = HEADING.search(text) if markdown else None
    title = (heading.group(1).strip() if heading else "") or path.stem or note_id
    return {
        "type": "document",
        "id": note_id,
        "version": hashlib.sha256(data).hexdigest(),
        "title": cut_utf16(title, MAX_TITLE_CHARS),
        "url": url,
        # When the text was written, so dates in it like "yesterday" resolve correctly.
        "updatedAt": datetime.fromtimestamp(stat.st_mtime, tz=timezone.utc).isoformat().replace("+00:00", "Z"),
        "format": "markdown" if markdown else "text",
        "text": text,
        "metadata": {"path": note_id},
    }


def chunks(ids: list[str], size: int) -> Iterator[list[str]]:
    for start in range(0, len(ids), size):
        yield ids[start:start + size]


def harvest(request: dict, out: Output, stderr: TextIO) -> None:
    """One run: report the notes after the cursor, in id order, then sweep once the pass is complete.

    A sweep tells the daemon "every live item was reported in this run", so it forgets the rest. A
    run that resumed from a cursor didn't report the notes before it, so it names them in `seen`
    first. A run stopped by a limit checkpoints and doesn't sweep: it hasn't seen everything."""
    config = Config(request["config"])
    cursor = request.get("cursor") or {}
    after = cursor.get("after") if isinstance(cursor, dict) else None
    limits = request.get("limits") or {}
    max_items = limits.get("maxItemsPerRun")
    max_chars = limits.get("maxCharsPerRun")

    notes = list_notes(config, stderr)
    if after is not None:
        earlier = [note_id for note_id, _ in notes if note_id <= after]
        for ids in chunks(earlier, MAX_SEEN_IDS):
            out.emit({"type": "seen", "ids": ids})
    pending = [(note_id, path) for note_id, path in notes if after is None or note_id > after]

    reported = 0
    chars = 0
    last = after
    # Notes that exist but couldn't be read this time: named in `seen` so the sweep keeps them.
    kept: list[str] = []
    for done, (note_id, path) in enumerate(pending, start=1):
        if max_items is not None and reported >= max_items:
            out.emit({"type": "checkpoint", "cursor": {"after": last}})
            return
        try:
            record = document(note_id, path, config, stderr)
        except FileNotFoundError:
            stderr.write(f"skipping {note_id}: it was deleted during the run\n")
            record = None
        except OSError as e:
            stderr.write(f"skipping {note_id}: {e.strerror or e}\n")
            kept.append(note_id)
            record = None
        if record is not None:
            size = utf16_len(record["text"])
            if max_chars is not None and reported > 0 and chars + size > max_chars:
                out.emit({"type": "checkpoint", "cursor": {"after": last}})
                return
            out.emit(record)
            reported += 1
            chars += size
        last = note_id
        out.emit({"type": "progress", "done": done, "total": len(pending)})
        if record is not None and reported % CHECKPOINT_EVERY == 0:
            out.emit({"type": "checkpoint", "cursor": {"after": last}})

    # The pass is complete. The next run starts over; the sweep must be the last record.
    for ids in chunks(kept, MAX_SEEN_IDS):
        out.emit({"type": "seen", "ids": ids})
    out.emit({"type": "checkpoint", "cursor": {}})
    out.emit({"type": "sweep"})


def check(request: dict) -> None:
    """Validate the settings. Returning normally means they work; `check` may only emit secret_update."""
    config = Config(request["config"])
    try:
        os.listdir(config.root)
    except OSError as e:
        raise HarvestError("config", f"directory {config.root} can't be read: {e.strerror or e}") from e


def main(argv: list[str], stdin: BinaryIO, stdout: BinaryIO, stderr: TextIO) -> int:
    """Run one command and return the exit code: 0 done, 1 failed, 2 usage or a bad request."""
    if len(argv) != 1 or argv[0] not in ("check", "harvest"):
        stderr.write(USAGE + "\n")
        return EXIT_USAGE
    out = Output(stdout)
    try:
        try:
            request = read_envelope(stdin)
        except HarvestError as e:
            out.emit({"type": "error", "class": e.error_class, "message": str(e)})
            return EXIT_USAGE
        try:
            if argv[0] == "check":
                check(request)
            else:
                harvest(request, out, stderr)
            return EXIT_OK
        except HarvestError as e:
            out.emit({"type": "error", "class": e.error_class, "message": str(e)})
            return EXIT_FAILED
        except StdoutClosed:
            raise
        except Exception as e:  # noqa: BLE001 - every failure must reach the daemon as an error record
            traceback.print_exc(file=stderr)
            out.emit({"type": "error", "class": "fatal", "message": str(e)[:2000] or type(e).__name__})
            return EXIT_FAILED
    except StdoutClosed:
        return EXIT_FAILED


if __name__ == "__main__":
    code = main(sys.argv[1:], sys.stdin.buffer, sys.stdout.buffer, sys.stderr)
    if code == EXIT_FAILED:
        # If stdout is gone, the interpreter's own flush at exit would fail loudly; point it nowhere.
        try:
            sys.stdout.flush()
        except (BrokenPipeError, OSError):
            os.dup2(os.open(os.devnull, os.O_WRONLY), sys.stdout.fileno())
    sys.exit(code)
