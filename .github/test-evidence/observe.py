#!/usr/bin/env python3
"""Observe one argv command without changing its tests or treating logs as a test verdict.

Usage: observe.py --output NEW_DIRECTORY --sha SHA --api 26 --lane native -- COMMAND ARGS

The POSIX runner tees child stdout/stderr bytes to their original console streams and
command.log (arrival order across streams is not guaranteed). Only parser copies lose
ANSI escapes. Each anchored AGP ``class > method[device] FAILED`` line immediately
produces a provisional JSONL observation and an escaped GitHub annotation on stderr.
Repeated lines remain observations, not unique failed-test counts. Final XML remains
owned by the test runner: this tool never invents a pass from silence or exit zero.
Output directories must be new, avoiding stale evidence and accidental overwrites.
No shell, retries, test selection, environment mutation, networking or audio capture.
"""

import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import selectors
import signal
import subprocess
import sys
import time
import uuid


MAX_LINE = 64 * 1024
READ_SIZE = 16 * 1024
CANCEL_GRACE_SECONDS = 5.0
ANSI = re.compile(r"\x1b(?:\[[0-?]*[ -/]*[@-~]|\][^\x07\x1b]*(?:\x07|\x1b\\))")
FAILURE = re.compile(
    r"^(?:\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z )?(?P<class>[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)+)\s+>\s+"
    r"(?P<display>[^>\r\n]{1,512}?)\s+FAILED\s*$"
)


def utc_now():
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def parse_failure(raw):
    """Parse only a complete anchored AGP line; never edit the raw evidence bytes."""
    clean = ANSI.sub("", raw.decode("utf-8", errors="replace")).rstrip("\r\n")
    match = FAILURE.fullmatch(clean)
    if not match:
        return None
    display = match["display"]
    method = display.split("[", 1)[0].rstrip()
    if not method:
        return None
    return {"class": match["class"], "method": method, "display_name": display,
            "parsed_line": clean}


def escape_annotation(text):
    # Percent first: otherwise already-escaped sequences can become command delimiters.
    return text.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def write_manifest(directory, manifest):
    temporary = directory / "manifest.json.tmp"
    with temporary.open("w", encoding="utf-8") as handle:
        json.dump(manifest, handle, ensure_ascii=True, indent=2)
        handle.write("\n")
        handle.flush()
        os.fsync(handle.fileno())
    temporary.replace(directory / "manifest.json")


def signal_child(child, sig):
    if child is not None:
        try:
            os.killpg(child.pid, sig)
        except ProcessLookupError:
            pass


class LineParser:
    """Bounded per-stream framing. Oversize lines are ignored until their next newline."""
    def __init__(self, stream, emit):
        self.stream = stream
        self.emit = emit
        self.pending = bytearray()
        self.discarding = False
        self.line_number = 0
        self.byte_offset = 0
        self.line_offset = 0
        self.oversize_lines = 0

    def feed(self, data):
        for fragment in data.splitlines(keepends=True):
            # Split on LF only; a lone CR is terminal progress, not another source line.
            self._fragment(fragment)

    def _fragment(self, fragment):
        # bytes.splitlines also splits CR; preserve it in the same pending logical line.
        self.byte_offset += len(fragment)
        end = fragment.endswith(b"\n")
        if not self.discarding:
            if len(self.pending) + len(fragment) > MAX_LINE:
                self.pending.clear()
                self.discarding = True
                self.oversize_lines += 1
            else:
                self.pending.extend(fragment)
        if end:
            self.finish_line()

    def finish_line(self):
        self.line_number += 1
        if not self.discarding:
            result = parse_failure(bytes(self.pending))
            if result:
                self.emit(result, self.stream, self.line_number, self.line_offset)
        self.pending.clear()
        self.discarding = False
        self.line_offset = self.byte_offset

    def finish(self):
        if self.pending or self.discarding:
            self.finish_line()


def observe(args):
    directory = Path(args.output)
    child = None
    cancelled = None
    cancelled_at = None
    observer_error = None
    owned_directory = False
    child_returncode = None
    previous_handlers = {}
    start = utc_now()
    manifest = {
        "schema_version": 1, "command_id": str(uuid.uuid4()), "sha": args.sha,
        "api": args.api, "lane": args.lane, "argv": args.command,
        "started_utc": start, "ended_utc": None, "status": "running",
        "child_returncode": None, "observer_exit_status": None,
        "observed_failure_lines": 0, "first_failure_utc": None,
        "observations_provisional": True,
        "authoritative_xml": {"status": "not_collected", "counts": None},
        "test_verdict": "not_determined", "observer_error": None,
        "cancel_signal": None, "oversize_lines_not_parsed": 0,
    }
    parsers = {}
    stderr_at_line_start = True

    def cancel(sig, _frame):
        nonlocal cancelled, cancelled_at
        if cancelled is None:
            cancelled, cancelled_at = sig, time.monotonic()
        signal_child(child, sig)

    try:
        directory.mkdir(parents=True, exist_ok=False)
        owned_directory = True
        write_manifest(directory, manifest)
        with (directory / "command.log").open("xb") as command_log, \
                (directory / "events.jsonl").open("x", encoding="utf-8") as events, \
                selectors.DefaultSelector() as selector:
            annotations = []

            def emit(result, stream, line_number, byte_offset):
                now = utc_now()
                manifest["observed_failure_lines"] += 1
                if manifest["first_failure_utc"] is None:
                    manifest["first_failure_utc"] = now
                event = {"event": "test_failure_observed", "utc": now,
                         "command_id": manifest["command_id"], "sha": args.sha,
                         "api": args.api, "lane": args.lane, "provisional": True,
                         "observation_index": manifest["observed_failure_lines"],
                         "stream": stream, "stream_line": line_number,
                         "stream_byte_offset": byte_offset, **result}
                events.write(json.dumps(event, ensure_ascii=True) + "\n")
                events.flush()
                annotation = ("::error::Provisional test failure " + result["class"] + " > " +
                              result["display_name"] + " (lane=" + args.lane + ", API=" + args.api +
                              ", command=" + manifest["command_id"] + ")")
                annotations.append(escape_annotation(annotation[9:]))

            def flush_annotations():
                nonlocal stderr_at_line_start
                for annotation in annotations:
                    prefix = "" if stderr_at_line_start else "\n"
                    sys.stderr.buffer.write((prefix + "::error::" + annotation + "\n").encode())
                    sys.stderr.buffer.flush()
                    stderr_at_line_start = True
                annotations.clear()

            for sig in (signal.SIGINT, signal.SIGTERM, signal.SIGHUP):
                previous_handlers[sig] = signal.signal(sig, cancel)
            child = subprocess.Popen(args.command, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                     start_new_session=True)
            # A signal can arrive during spawn, before the child handle is assigned.
            if cancelled is not None:
                signal_child(child, cancelled)
            for stream, pipe, console in (("stdout", child.stdout, sys.stdout.buffer),
                                          ("stderr", child.stderr, sys.stderr.buffer)):
                parsers[stream] = LineParser(stream, emit)
                selector.register(pipe, selectors.EVENT_READ, (stream, console))
            while selector.get_map() or child.poll() is None:
                if cancelled_at is not None and time.monotonic() - cancelled_at >= CANCEL_GRACE_SECONDS:
                    signal_child(child, signal.SIGKILL)
                for key, _ in selector.select(timeout=0.1):
                    stream, console = key.data
                    data = os.read(key.fd, READ_SIZE)
                    if not data:
                        selector.unregister(key.fileobj)
                        key.fileobj.close()
                        parsers[stream].finish()
                        flush_annotations()
                        continue
                    command_log.write(data)
                    command_log.flush()
                    # Flush failure evidence before a potentially blocked console sink.
                    parsers[stream].feed(data)
                    console.write(data)
                    console.flush()
                    if stream == "stderr":
                        stderr_at_line_start = data.endswith(b"\n")
                    flush_annotations()
            child_returncode = child.wait()
    except Exception as error:
        observer_error = type(error).__name__ + ": " + str(error)
        signal_child(child, signal.SIGTERM)
        if child is not None:
            try:
                child_returncode = child.wait(timeout=CANCEL_GRACE_SECONDS)
            except subprocess.TimeoutExpired:
                signal_child(child, signal.SIGKILL)
                child_returncode = child.wait()
    finally:
        for sig, previous in previous_handlers.items():
            signal.signal(sig, previous)
        if child is not None:
            for pipe in (child.stdout, child.stderr):
                if pipe is not None:
                    pipe.close()

    # Preserve a nonzero child outcome; never turn observer/cancellation failure into green.
    exit_status = (128 - child_returncode if child_returncode is not None and child_returncode < 0
                   else child_returncode or 0)
    if cancelled is not None:
        exit_status = exit_status or 128 + cancelled
    if observer_error is not None:
        exit_status = exit_status or 125
    manifest.update({"ended_utc": utc_now(), "child_returncode": child_returncode,
                     "observer_exit_status": exit_status, "observer_error": observer_error,
                     "cancel_signal": cancelled,
                     "oversize_lines_not_parsed": sum(p.oversize_lines for p in parsers.values()),
                     "status": "incomplete" if observer_error else "cancelled" if cancelled else
                               "failed" if child_returncode else "completed"})
    try:
        # Never overwrite an existing output directory if startup refused to create it.
        if owned_directory:
            write_manifest(directory, manifest)
    except Exception as error:
        exit_status = exit_status or 125
        observer_error = observer_error or "Manifest finalization failed: " + str(error)
    if observer_error:
        try:
            print("Test observer incomplete: " + observer_error, file=sys.stderr, flush=True)
        except OSError:
            pass
    return exit_status


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True)
    parser.add_argument("--sha", required=True)
    parser.add_argument("--api", required=True)
    parser.add_argument("--lane", required=True)
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args(argv)
    if not args.command or args.command[0] != "--" or len(args.command) < 2:
        parser.error("Provide a command argv after --")
    args.command = args.command[1:]
    return observe(args)


if __name__ == "__main__":
    sys.exit(main())
