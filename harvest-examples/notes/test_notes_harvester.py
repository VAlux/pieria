"""Tests for the sample notes harvester. Run with: python3 -m unittest discover -s harvest-examples/notes -v"""

import io
import json
import os
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import notes_harvester  # noqa: E402


def envelope(config, cursor=None, limits=None):
    request = {"protocol": 1, "config": config, "secrets": {}, "limits": limits or {}}
    if cursor is not None:
        request["cursor"] = cursor
    return (json.dumps(request) + "\n").encode("utf-8")


def run(argv, stdin_bytes):
    stdout = io.BytesIO()
    stderr = io.StringIO()
    code = notes_harvester.main(argv, io.BytesIO(stdin_bytes), stdout, stderr)
    records = [json.loads(line) for line in stdout.getvalue().decode("utf-8").splitlines() if line.strip()]
    return code, records, stderr.getvalue()


def kinds(records):
    return [r["type"] for r in records if r["type"] != "progress"]


def documents(records):
    return [r for r in records if r["type"] == "document"]


class NotesHarvesterTests(unittest.TestCase):

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def write(self, relative, content):
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        if isinstance(content, bytes):
            path.write_bytes(content)
        else:
            path.write_text(content, encoding="utf-8")
        return path

    def harvest(self, cursor=None, limits=None, **config):
        return run(["harvest"], envelope({"directory": str(self.root), **config}, cursor, limits))

    def notes(self, count):
        for i in range(count):
            self.write(f"n{i}.md", f"# Note {i}\nbody {i}\n")
        return [f"n{i}.md" for i in range(count)]

    def test_harvest_reports_each_note_then_sweeps(self):
        self.write("a.md", "# A\n")
        self.write("b/c.txt", "plain")
        self.write("b/d.markdown", "# D\n")

        code, records, _ = self.harvest()

        self.assertEqual(code, 0)
        self.assertEqual([d["id"] for d in documents(records)], ["a.md", "b/c.txt", "b/d.markdown"])
        rest = [r for r in records if r["type"] != "progress"]
        self.assertEqual(rest[-2], {"type": "checkpoint", "cursor": {}})
        self.assertEqual(rest[-1], {"type": "sweep"})
        first = documents(records)[0]
        self.assertEqual(first["format"], "markdown")
        self.assertEqual(first["metadata"], {"path": "a.md"})
        self.assertTrue(first["url"].startswith("file://"))
        self.assertTrue(first["updatedAt"].endswith("Z"))
        self.assertEqual(documents(records)[1]["format"], "text")

    def test_title_from_first_heading_else_file_name(self):
        self.write("a.md", "intro\n#   Runbook  \nmore")
        self.write("notes.txt", "# not a heading in text")

        _, records, _ = self.harvest()

        self.assertEqual([d["title"] for d in documents(records)], ["Runbook", "notes"])

    def test_version_tracks_the_content(self):
        path = self.write("a.md", "one")
        _, first, _ = self.harvest()
        path.write_text("one", encoding="utf-8")
        _, same, _ = self.harvest()
        path.write_text("two", encoding="utf-8")
        _, changed, _ = self.harvest()

        self.assertEqual(documents(first)[0]["version"], documents(same)[0]["version"])
        self.assertNotEqual(documents(first)[0]["version"], documents(changed)[0]["version"])

    def test_hidden_symlinked_and_other_files_are_skipped(self):
        self.write("keep.md", "kept")
        self.write(".obsidian/x.md", "hidden folder")
        self.write(".hidden.md", "hidden file")
        self.write("pic.png", b"\x89PNG")
        outside = tempfile.TemporaryDirectory()
        self.addCleanup(outside.cleanup)
        target = Path(outside.name) / "secret.md"
        target.write_text("outside", encoding="utf-8")
        if hasattr(os, "symlink") and sys.platform != "win32":
            os.symlink(target, self.root / "link.md")
            os.symlink(outside.name, self.root / "linkdir")

        _, records, _ = self.harvest()

        self.assertEqual([d["id"] for d in documents(records)], ["keep.md"])

    def test_custom_extensions(self):
        self.write("a.md", "md")
        self.write("x.org", "org")

        _, records, _ = self.harvest(extensions=["org"])

        self.assertEqual([d["id"] for d in documents(records)], ["x.org"])

    def test_undecodable_empty_and_oversized_notes(self):
        self.write("bad.txt", b"\xff\xfe ok")
        self.write("empty.md", "")
        self.write("big.md", "x" * 2000)

        code, records, stderr = self.harvest(maxFileBytes=1000)

        self.assertEqual(code, 0)
        by_id = {d["id"]: d for d in documents(records)}
        self.assertIn("ok", by_id["bad.txt"]["text"])
        self.assertIn("�", by_id["bad.txt"]["text"])
        self.assertEqual(by_id["empty.md"]["text"], "")
        self.assertNotIn("big.md", by_id)
        self.assertIn("over maxFileBytes", stderr)

    def test_item_limit_checkpoints_without_sweep(self):
        ids = self.notes(5)

        code, records, _ = self.harvest(limits={"maxItemsPerRun": 2})

        self.assertEqual(code, 0)
        self.assertEqual([d["id"] for d in documents(records)], ids[:2])
        self.assertEqual(kinds(records)[-1], "checkpoint")
        self.assertEqual(records[-1]["cursor"], {"after": ids[1]})
        self.assertNotIn("sweep", kinds(records))

    def test_resume_marks_earlier_notes_seen_before_sweeping(self):
        ids = self.notes(5)

        _, records, _ = self.harvest(cursor={"after": ids[1]})

        order = kinds(records)
        self.assertLess(order.index("seen"), order.index("document"))
        self.assertEqual(next(r for r in records if r["type"] == "seen")["ids"], ids[:2])
        self.assertEqual([d["id"] for d in documents(records)], ids[2:])
        self.assertEqual(order[-1], "sweep")

    def test_seen_is_sent_in_chunks(self):
        ids = self.notes(3)
        old_limit = notes_harvester.MAX_SEEN_IDS
        notes_harvester.MAX_SEEN_IDS = 2
        self.addCleanup(setattr, notes_harvester, "MAX_SEEN_IDS", old_limit)

        _, records, _ = self.harvest(cursor={"after": ids[2]})

        self.assertEqual([r["ids"] for r in records if r["type"] == "seen"], [ids[:2], ids[2:]])

    def test_checkpoints_every_twenty_five(self):
        self.notes(30)

        _, records, _ = self.harvest()

        checkpoints = [r["cursor"] for r in records if r["type"] == "checkpoint"]
        self.assertEqual(checkpoints, [{"after": sorted(f"n{i}.md" for i in range(30))[24]}, {}])

    def test_char_limit_lets_the_first_note_through(self):
        self.notes(3)

        _, records, _ = self.harvest(limits={"maxCharsPerRun": 1})

        self.assertEqual(len(documents(records)), 1)
        self.assertNotIn("sweep", kinds(records))

    def test_progress_counts_the_pass(self):
        self.notes(3)

        _, records, _ = self.harvest()

        self.assertEqual([(r["done"], r["total"]) for r in records if r["type"] == "progress"], [(1, 3), (2, 3), (3, 3)])

    def test_check_accepts_a_folder_and_emits_nothing(self):
        code, records, _ = run(["check"], envelope({"directory": str(self.root)}))

        self.assertEqual(code, 0)
        self.assertEqual(records, [])

    def test_missing_directory_is_a_config_error(self):
        code, records, _ = run(["check"], envelope({"directory": "/no/such/folder"}))

        self.assertEqual(code, 1)
        self.assertEqual(len(records), 1)
        self.assertEqual(records[0]["type"], "error")
        self.assertEqual(records[0]["class"], "config")
        self.assertIn("does not exist or is not a folder", records[0]["message"])

    def test_bad_config_values_are_config_errors(self):
        for config in ({"directory": str(self.root), "extensions": "md"}, {"directory": str(self.root), "maxFileBytes": 0}, {}):
            code, records, _ = run(["harvest"], envelope(config))
            self.assertEqual(code, 1, config)
            self.assertEqual(records[-1]["class"], "config", config)

    def test_bad_envelope_and_unknown_command(self):
        code, records, _ = run(["harvest"], b"not json\n")
        self.assertEqual(code, 2)
        self.assertEqual(records[0]["class"], "config")
        self.assertIn("bad envelope", records[0]["message"])

        code, records, _ = run(["harvest"], (json.dumps({"protocol": 2, "config": {}}) + "\n").encode())
        self.assertEqual(code, 2)

        code, records, stderr = run(["login"], b"")
        self.assertEqual(code, 2)
        self.assertEqual(records, [])
        self.assertIn("check|harvest", stderr)

    def test_closed_stdout_exits_quietly(self):
        class Closed(io.BytesIO):
            def write(self, data):
                raise BrokenPipeError()

        self.notes(2)
        stderr = io.StringIO()

        code = notes_harvester.main(["harvest"], io.BytesIO(envelope({"directory": str(self.root)})), Closed(), stderr)

        self.assertEqual(code, 1)
        self.assertNotIn("Traceback", stderr.getvalue())


if __name__ == "__main__":
    unittest.main()
