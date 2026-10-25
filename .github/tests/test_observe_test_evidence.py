"""Offline observer contract tests. No Android, Gradle, network, or workflow execution."""

import importlib.util
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import time
import unittest
from unittest import mock


SCRIPT = Path(__file__).resolve().parents[1] / "test-evidence" / "observe.py"
SPEC = importlib.util.spec_from_file_location("test_evidence_observer", SCRIPT)
observer = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(observer)
FAILURE = "io.github.localica.SomeTest > example[emulator-5554 - 16] FAILED"


@unittest.skipUnless(os.name == "posix", "Observer targets POSIX CI runners")
class ObserveTestEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.output = self.root / "evidence"

    def argv(self, source, *arguments, lane="native"):
        return [sys.executable, str(SCRIPT), "--output", str(self.output), "--sha", "a" * 40,
                "--api", "36", "--lane", lane, "--", sys.executable, "-u", "-c", source,
                *arguments]

    def run_child(self, source, *arguments, **kwargs):
        return subprocess.run(self.argv(source, *arguments, **kwargs), capture_output=True, timeout=12)

    def manifest(self):
        return json.loads((self.output / "manifest.json").read_text())

    def events(self):
        return [json.loads(line) for line in (self.output / "events.jsonl").read_text().splitlines()]

    def wait_for(self, predicate, process, timeout=5):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if predicate():
                return
            if process.poll() is not None:
                self.fail("Observer ended before expected live evidence")
            time.sleep(0.01)
        self.fail("Timed out waiting for live evidence")

    def start(self, source):
        process = subprocess.Popen(self.argv(source), stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        def cleanup():
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=8)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=2)
            process.stdout.close()
            process.stderr.close()
        self.addCleanup(cleanup)
        return process

    def test_success_keeps_binary_stdout_stderr_and_does_not_claim_test_pass(self):
        result = self.run_child("import os; os.write(1, b'out\\x00\\xff\\n'); os.write(2, b'err\\r\\n')")
        self.assertEqual(0, result.returncode)
        self.assertEqual(b"out\x00\xff\n", result.stdout)
        self.assertEqual(b"err\r\n", result.stderr)
        raw = (self.output / "command.log").read_bytes()
        self.assertIn(result.stdout, raw)
        self.assertIn(result.stderr, raw)
        self.assertEqual(len(result.stdout) + len(result.stderr), len(raw))
        manifest = self.manifest()
        self.assertEqual("completed", manifest["status"])
        self.assertEqual(0, manifest["child_returncode"])
        self.assertEqual("not_determined", manifest["test_verdict"])
        self.assertIsNone(manifest["authoritative_xml"]["counts"])
        self.assertEqual([], self.events())

    def test_exit_seven_is_preserved_without_failure_lines(self):
        result = self.run_child("import sys; print('setup failed'); sys.exit(7)")
        self.assertEqual(7, result.returncode)
        self.assertEqual(7, self.manifest()["child_returncode"])
        self.assertEqual("failed", self.manifest()["status"])
        self.assertEqual(0, self.manifest()["observed_failure_lines"])

    def test_first_failure_flushes_before_child_end_and_later_output_continues(self):
        release = self.root / "release"
        source = ("from pathlib import Path; import time\n"
                  f"print({FAILURE!r}, flush=True)\n"
                  f"while not Path({str(release)!r}).exists(): time.sleep(.02)\n"
                  "print('later output', flush=True)\nraise SystemExit(7)")
        process = self.start(source)
        events_file = self.output / "events.jsonl"
        self.wait_for(lambda: events_file.exists() and events_file.stat().st_size > 0, process)
        self.assertIsNone(process.poll())
        event = self.events()[0]
        self.assertEqual("io.github.localica.SomeTest", event["class"])
        self.assertEqual("example", event["method"])
        self.assertEqual(1, event["stream_line"])
        self.assertEqual("stdout", event["stream"])
        self.assertEqual("a" * 40, event["sha"])
        self.assertEqual("36", event["api"])
        self.assertTrue(event["provisional"])
        self.assertTrue(event["utc"].endswith("Z"))
        self.assertIn(FAILURE.encode(), (self.output / "command.log").read_bytes())
        release.touch()
        stdout, stderr = process.communicate(timeout=5)
        self.assertEqual(7, process.returncode)
        self.assertIn(b"later output\n", stdout)
        self.assertIn(b"::error::Provisional test failure", stderr)
        self.assertEqual(event["command_id"], self.manifest()["command_id"])

    def test_ansi_real_agp_variant_on_stderr_and_duplicate_observations(self):
        line = ("2026-10-25T16:42:01.3242619Z io.github.anastaci1a.localica.SearchImeMotionTest > "
                "nonemptyNativeBackPreservesTheQueryAndEasesItsRetainedPanel[emulator-5554 - 16] "
                "\x1b[31mFAILED \x1b[0m")
        result = self.run_child(f"import sys; print({line!r}, file=sys.stderr); print({line!r}, file=sys.stderr)")
        self.assertEqual(0, result.returncode) # Observations do not rewrite child exit status.
        self.assertEqual(2, len(self.events()))
        self.assertEqual([1, 2], [event["observation_index"] for event in self.events()])
        self.assertEqual("stderr", self.events()[0]["stream"])
        self.assertEqual("nonemptyNativeBackPreservesTheQueryAndEasesItsRetainedPanel", self.events()[0]["method"])
        self.assertEqual((line + "\n" + line + "\n").encode(), (self.output / "command.log").read_bytes())
        self.assertNotIn("\x1b", self.events()[0]["parsed_line"])
        self.assertEqual("not_determined", self.manifest()["test_verdict"])

    def test_malformed_prefix_progress_and_skips_are_not_failure_observations(self):
        lines = ["prefix " + FAILURE, FAILURE + " later", "FAILED", "class > method FAILED",
                 FAILURE.replace("FAILED", "SKIPPED"), "emulator-5554 - 16 Tests 1048/1037 completed. (1 failed)",
                 "java.lang.AssertionError: assertion FAILED", "::error::" + FAILURE,
                 "> Task :app:connectedDebugAndroidTest FAILED"]
        result = self.run_child(f"print({chr(10).join(lines)!r})")
        self.assertEqual(0, result.returncode)
        self.assertEqual([], self.events())

    def test_chunked_line_and_final_line_without_newline_are_observed(self):
        source = ("import os, time\n" + f"line={FAILURE.encode()!r}\n" +
                  "os.write(1,line[:17]); time.sleep(.02); os.write(1,line[17:])")
        result = self.run_child(source)
        self.assertEqual(0, result.returncode)
        self.assertEqual(1, len(self.events()))
        self.assertEqual(FAILURE.encode(), result.stdout)

    def test_long_unterminated_lines_are_bounded_and_do_not_hide_later_failure(self):
        result = self.run_child(f"print('x' * 200000); print({FAILURE!r})")
        self.assertEqual(0, result.returncode)
        self.assertEqual(1, len(self.events()))
        self.assertEqual(1, self.manifest()["oversize_lines_not_parsed"])
        self.assertEqual(2, self.events()[0]["stream_line"])
        self.assertEqual(200001, self.events()[0]["stream_byte_offset"])
        self.assertEqual(200001 + len(FAILURE) + 1, len((self.output / "command.log").read_bytes()))

    def test_annotation_escapes_untrusted_lane_without_command_injection(self):
        lane = "native%\r\n::notice::injected"
        result = self.run_child(f"print({FAILURE!r})", lane=lane)
        self.assertEqual(0, result.returncode)
        self.assertIn(b"native%25%0D%0A::notice::injected", result.stderr)
        self.assertEqual(1, len(result.stderr.splitlines()))
        self.assertEqual(lane, self.events()[0]["lane"])

    def test_annotations_start_on_their_own_line_after_partial_stderr(self):
        source = f"import os; os.write(2, {str(FAILURE + chr(10) + 'partial suffix').encode()!r})"
        result = self.run_child(source)
        self.assertEqual(0, result.returncode)
        self.assertIn(b"partial suffix\n::error::", result.stderr)
        self.assertEqual((FAILURE + "\npartial suffix").encode(),
                         (self.output / "command.log").read_bytes())

    def test_stdout_failure_annotation_is_not_joined_to_earlier_stderr_fragment(self):
        source = ("import os,time; os.write(2,b'partial'); time.sleep(.05); "
                  f"os.write(1, {str(FAILURE + chr(10)).encode()!r})")
        result = self.run_child(source)
        self.assertEqual(0, result.returncode)
        self.assertTrue(result.stderr.startswith(b"partial\n::error::"))

    def test_argv_is_literal_and_shell_metacharacters_are_not_executed(self):
        marker = self.root / "must-not-exist"
        argument = f"; touch {marker}"
        result = self.run_child("import sys; print(repr(sys.argv[1]))", argument)
        self.assertEqual(0, result.returncode)
        self.assertIn(argument.encode(), result.stdout)
        self.assertFalse(marker.exists())
        self.assertEqual(argument, self.manifest()["argv"][-1])

    def test_signal_cancellation_keeps_partial_evidence_and_forwards_signal(self):
        process = self.start(f"import time; print({FAILURE!r}, flush=True); time.sleep(60)")
        self.wait_for(lambda: (self.output / "events.jsonl").exists() and bool(self.events()), process)
        process.send_signal(signal.SIGTERM)
        stdout, _ = process.communicate(timeout=8)
        self.assertEqual(128 + signal.SIGTERM, process.returncode)
        manifest = self.manifest()
        self.assertEqual("cancelled", manifest["status"])
        self.assertEqual(signal.SIGTERM, manifest["cancel_signal"])
        self.assertEqual(-signal.SIGTERM, manifest["child_returncode"])
        self.assertIn(FAILURE.encode(), stdout)
        self.assertEqual(1, len(self.events()))

    def test_sigint_cannot_become_success_if_child_handles_it_with_exit_zero(self):
        source = ("import signal, time\n"
                  "signal.signal(signal.SIGINT, lambda *_: exit(0))\n"
                  "print('ready', flush=True)\ntime.sleep(60)")
        process = self.start(source)
        self.wait_for(lambda: (self.output / "command.log").exists() and
                      b"ready" in (self.output / "command.log").read_bytes(), process)
        process.send_signal(signal.SIGINT)
        process.communicate(timeout=8)
        self.assertEqual(130, process.returncode)
        self.assertEqual(0, self.manifest()["child_returncode"])
        self.assertEqual("cancelled", self.manifest()["status"])

    def test_cancellation_after_child_closes_output_still_reaches_child(self):
        source = "import os,time; print('ready', flush=True); os.close(1); os.close(2); time.sleep(60)"
        process = self.start(source)
        self.wait_for(lambda: (self.output / "command.log").exists() and
                      b"ready" in (self.output / "command.log").read_bytes(), process)
        process.terminate()
        process.communicate(timeout=8)
        self.assertEqual(143, process.returncode)
        self.assertEqual("cancelled", self.manifest()["status"])

    def test_ignored_cancellation_is_bounded_and_records_forced_child_exit(self):
        source = ("import signal,time\n"
                  "signal.signal(signal.SIGTERM, signal.SIG_IGN)\n"
                  "print('ready', flush=True)\ntime.sleep(60)")
        process = self.start(source)
        self.wait_for(lambda: (self.output / "command.log").exists() and
                      b"ready" in (self.output / "command.log").read_bytes(), process)
        process.terminate()
        process.communicate(timeout=8)
        self.assertEqual(137, process.returncode)
        self.assertEqual(-signal.SIGKILL, self.manifest()["child_returncode"])
        self.assertEqual("cancelled", self.manifest()["status"])

    def test_child_signal_exit_is_not_green(self):
        result = self.run_child("import os,signal; os.kill(os.getpid(),signal.SIGTERM)")
        self.assertEqual(143, result.returncode)
        self.assertEqual(-15, self.manifest()["child_returncode"])

    def test_existing_directory_is_not_overwritten_or_used_to_launch_child(self):
        self.output.mkdir()
        sentinel = self.output / "manifest.json"
        sentinel.write_text("preserve me")
        result = self.run_child("print('must not execute')")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(b"", result.stdout)
        self.assertEqual("preserve me", sentinel.read_text())
        self.assertFalse((self.output / "command.log").exists())

    def test_launch_failure_records_incomplete_not_a_test_result(self):
        argv = self.argv("pass")
        argv[argv.index("--") + 1:] = [str(self.root / "missing-executable")]
        result = subprocess.run(argv, capture_output=True, timeout=5)
        self.assertEqual(125, result.returncode)
        self.assertEqual("incomplete", self.manifest()["status"])
        self.assertIsNone(self.manifest()["child_returncode"])
        self.assertIn("FileNotFoundError", self.manifest()["observer_error"])

    def test_evidence_write_failure_is_nonzero_and_preserves_partial_manifest(self):
        # Real ENOSPC on a controlled sink, without exhausting the filesystem.
        if not Path("/dev/full").exists():
            self.skipTest("Requires POSIX /dev/full")
        real_open = Path.open
        def open_file(path, *args, **kwargs):
            if path.name == "command.log":
                return real_open(Path("/dev/full"), "wb")
            return real_open(path, *args, **kwargs)
        args = observer.argparse.Namespace(output=str(self.output), sha="a" * 40, api="36", lane="native",
                                          command=[sys.executable, "-c", "print('evidence')"])
        with mock.patch.object(Path, "open", open_file):
            result = observer.observe(args)
        self.assertNotEqual(0, result)
        self.assertEqual("incomplete", self.manifest()["status"])
        self.assertIn("No space left", self.manifest()["observer_error"])

    def test_event_write_failure_cannot_turn_success_green(self):
        if not Path("/dev/full").exists():
            self.skipTest("Requires POSIX /dev/full")
        real_open = Path.open
        def open_file(path, *args, **kwargs):
            if path.name == "events.jsonl":
                return real_open(Path("/dev/full"), "w", encoding="utf-8")
            return real_open(path, *args, **kwargs)
        args = observer.argparse.Namespace(output=str(self.output), sha="a" * 40, api="36", lane="native",
                                          command=[sys.executable, "-c", f"print({FAILURE!r})"])
        with mock.patch.object(Path, "open", open_file):
            result = observer.observe(args)
        self.assertNotEqual(0, result)
        self.assertEqual("incomplete", self.manifest()["status"])
        self.assertIn(FAILURE.encode(), (self.output / "command.log").read_bytes())

    def test_failure_event_precedes_console_write_failure(self):
        class BrokenConsole:
            def write(self, _data):
                raise BrokenPipeError("simulated closed console")
            def flush(self):
                pass
        args = observer.argparse.Namespace(output=str(self.output), sha="a" * 40, api="36", lane="native",
                                          command=[sys.executable, "-c", f"print({FAILURE!r})"])
        console = mock.Mock(buffer=BrokenConsole())
        with mock.patch.object(sys, "stdout", console):
            result = observer.observe(args)
        self.assertNotEqual(0, result)
        self.assertEqual(1, len(self.events()))
        self.assertEqual("incomplete", self.manifest()["status"])

    def test_final_manifest_failure_cannot_turn_zero_child_exit_green(self):
        real_write = observer.write_manifest
        calls = []
        def fail_final(directory, manifest):
            calls.append(manifest["status"])
            if len(calls) == 2:
                raise OSError("simulated final evidence loss")
            real_write(directory, manifest)
        args = observer.argparse.Namespace(output=str(self.output), sha="a" * 40, api="36", lane="native",
                                          command=[sys.executable, "-c", "pass"])
        with mock.patch.object(observer, "write_manifest", fail_final):
            result = observer.observe(args)
        self.assertEqual(125, result)
        self.assertEqual("running", self.manifest()["status"]) # Explicitly unfinished, never a pass.

    def test_parser_memory_is_bounded_and_crlf_line_number_is_stable(self):
        events = []
        parser = observer.LineParser("stdout", lambda *args: events.append(args))
        for _ in range(100):
            parser.feed(b"x" * 16384)
            self.assertLessEqual(len(parser.pending), observer.MAX_LINE)
        parser.feed(b"\r\n" + FAILURE.encode() + b"\r\n")
        self.assertEqual(1, len(events))
        self.assertEqual(2, events[0][2])


if __name__ == "__main__":
    unittest.main()
