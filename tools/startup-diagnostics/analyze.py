"""Analyze external startup measurements; framestats are not presented-pixel timings."""
import argparse
import csv
import json
import math
import re
from pathlib import Path

PHASES = {
    "dispatchDelay": ("IntendedVsync", "Vsync"),
    "startDelay": ("Vsync", "HandleInputStart"),
    "input": ("HandleInputStart", "AnimationStart"),
    "animation": ("AnimationStart", "PerformTraversalsStart"),
    "layout": ("PerformTraversalsStart", "DrawStart"),
    "draw": ("DrawStart", "SyncQueued"),
    "syncWait": ("SyncQueued", "SyncStart"),
    "sync": ("SyncStart", "IssueDrawCommandsStart"),
    "issue": ("IssueDrawCommandsStart", "SwapBuffers"),
    "completion": ("SwapBuffers", "FrameCompleted"),
    "total": ("IntendedVsync", "FrameCompleted"),
}
INVALID = (1 << 63) - 1


def percentiles(values):
    ordered = sorted(values)
    if not ordered:
        return {"count": 0, "p50Ms": None, "p95Ms": None, "p99Ms": None}
    return {"count": len(ordered), **{
        f"p{p}Ms": ordered[max(0, math.ceil(len(ordered) * p / 100) - 1)] / 1e6
        for p in (50, 95, 99)}}


def frames(text, start_ms, end_ms):
    """Read each window's ring; reject flagged, unfinished and out-of-window rows."""
    header = None
    result = {}
    future = set()
    captured_at = None
    rejected = {"flagged": 0, "unfinished": 0, "outsideWindow": 0, "duplicate": 0, "futureCompletion": 0}
    for line in text.splitlines():
        if line.startswith("# capturedAtNanos="):
            captured_at = int(line.split("=", 1)[1])
            continue
        if line.startswith("Flags,") and "IntendedVsync" in line and "FrameCompleted" in line:
            header = next(csv.reader([line.rstrip(",")]))
            continue
        if header is None or not line or not line[0].isdigit() or "," not in line:
            continue
        cells = next(csv.reader([line.rstrip(",")]))
        if len(cells) != len(header):
            continue
        try:
            row = dict(zip(header, map(int, cells)))
        except ValueError:
            continue
        if row["Flags"] != 0:
            rejected["flagged"] += 1
            continue
        begin, end = row["IntendedVsync"], row["FrameCompleted"]
        if not 0 < begin < end < INVALID:
            rejected["unfinished"] += 1
            continue
        if not start_ms * 1e6 <= begin < end_ms * 1e6:
            rejected["outsideWindow"] += 1
            continue
        # GPU completion fields can change after a live snapshot. Identify the CPU
        # traversal instead, retain the latest valid completion, and never count it twice.
        key = (begin, row.get("DrawStart") or row.get("FrameTimelineVsyncId") or end)
        if captured_at is not None and end > captured_at:
            rejected["futureCompletion"] += 1
            future.add(key)
            continue
        if key in result:
            rejected["duplicate"] += 1
        result[key] = row
    rejected["futureCompletionFrames"] = len(future)
    rejected["futureCompletionRecovered"] = len(future.intersection(result))
    rejected["futureCompletionUnresolved"] = len(future.difference(result))
    return list(result.values()), rejected


def summarize(text, milestones, window_ms=10_000):
    start = milestones["launchUptimeMs"]
    rows, rejected = frames(text, start, start + window_ms)
    durations = {}
    for phase, (left, right) in PHASES.items():
        values = [row[right] - row[left] for row in rows
                  if 0 < row.get(left, 0) <= row.get(right, 0) < INVALID]
        durations[phase] = percentiles(values)
    deadlines = [row for row in rows if row.get("FrameDeadline", 0) > row["IntendedVsync"]
                 and row["FrameDeadline"] < INVALID]
    return {
        "windowMs": window_ms, "frames": len(rows), "rejected": rejected, "phases": durations,
        "over16_7ms": sum(row["FrameCompleted"] - row["IntendedVsync"] > 16_700_000 for row in rows),
        "deadlineMisses": sum(row["FrameCompleted"] > row["FrameDeadline"] for row in deadlines) if deadlines else None,
        "deadlineFrames": len(deadlines),
        "tail": "gfxinfo ring snapshot; incomplete tail and buffer loss cannot be certified",
        "longestFrameMs": max((row["FrameCompleted"] - row["IntendedVsync"] for row in rows), default=0) / 1e6,
    }


def analyze(root):
    trials = []
    for file in sorted(root.glob("*/milestones.json")):
        milestones = json.loads(file.read_text())
        snapshots = sorted(file.parent.glob("gfxinfo-*.txt")) + [file.parent / "gfxinfo.txt"]
        raw = "\n".join(snapshot.read_text() for snapshot in snapshots)
        window_ms = milestones.get("windowMs", 10_000)
        trial = {"path": str(file.parent), "milestones": milestones,
                 "first5s": summarize(raw, milestones, 5_000), "full": summarize(raw, milestones, window_ms)}
        missing = []
        for key in ("firstFiveSecondInputAttempt", "navigationContentObserved", "dragScrollEventObserved",
                    "flingScrollEventObserved", "frameSamplerStopped"):
            if not milestones.get(key):
                missing.append(key)
        if not trial["full"]["frames"]:
            missing.append("frames")
        if milestones.get("frameSamplerFailure"):
            missing.append("frameSamplerFailure")
        input_missing = [key for key in missing if key != "navigationContentObserved"]
        trial["inputCoverage"] = {"status": "partial" if input_missing else "observed", "missing": input_missing}
        trial["coverage"] = {"status": "partial" if missing else "observed", "missing": missing,
                             "meaning": "harness observations only; not performance acceptance"}
        trace = file.parent / "trace.txt"
        if trace.exists() and "pid" in milestones:
            text = trace.read_text()
            trial["scheduling"] = scheduling(text, (file.parent / "threads.txt").read_text(),
                                             int(milestones["pid"]), milestones["launchUptimeMs"], window_ms)
            trial["inputDelivery"] = input_delivery(text, int(milestones["pid"]), milestones)
            trial["sections"] = sections(text, trial["scheduling"]["threads"],
                                          milestones["launchUptimeMs"], window_ms)
        trials.append(trial)
    by_mode = {trial["milestones"]["mode"]: trial for trial in trials}
    gap = None
    if "cold" in by_mode and "warm" in by_mode:
        cold = by_mode["cold"]["milestones"]
        gap = by_mode["warm"]["milestones"]["launchUptimeMs"] - (cold["launchUptimeMs"] + cold.get("windowMs", 10_000))
    return {"percentile": "nearest rank, per trial; no pooled frame distribution",
            "physicalAcceptance": "open", "observedWarmGapAfterColdWindowMs": gap, "trials": trials}


def input_delivery(trace, pid, milestones):
    """Match target Main touch delivery to injected event-time intervals, not pixels."""
    pattern = re.compile(r"-(\d+)\s+(?:\(.*?\)\s+)?\[\d+\].*?\s(\d+\.\d+):\s+tracing_mark_write:\s+B\|(\d+)\|deliverInputEvent src=0x1002 eventTimeNano=(\d+) id=(\S+)")
    launch = milestones["launchUptimeMs"]
    end = launch + milestones.get("windowMs", 15_000)
    events, seen = [], set()
    for line in trace.splitlines():
        match = pattern.search(line)
        if match is None:
            continue
        tid, at, owner, event_time, identity = match.groups()
        at_ms, event_ns = float(at) * 1000, int(event_time)
        key = (identity, event_ns)
        if int(tid) != pid or int(owner) != pid or not launch <= at_ms < end or key in seen:
            continue
        if at_ms * 1e6 < event_ns:
            continue
        seen.add(key)
        events.append({"id": identity, "eventUptimeMs": event_ns / 1e6,
                       "mainDeliveryUptimeMs": at_ms, "queueMs": (at_ms * 1e6 - event_ns) / 1e6})
    result = []
    for action in milestones.get("inputs", []):
        first = launch + action["requestedMs"]
        last = launch + action["injectionReturnedMs"]
        matched = [event for event in events if first <= event["eventUptimeMs"] <= last]
        result.append({"kind": action["kind"], "requestedMs": action["requestedMs"],
                       "matchedEvents": len(matched), "queue": percentiles([round(event["queueMs"] * 1e6) for event in matched]),
                       "events": matched})
    return {"meaning": "injected event time to target Main deliverInputEvent; includes dispatch and scheduling; not selection, content or presented-pixel latency",
            "unmatchedMeaning": "no matched delivery observed inside this window; not proof of dropped input", "actions": result}


def scheduling(trace, threads, pid, start_ms, window_ms=10_000):
    """Integrate observed sched_switch intervals, preserving unknown time explicitly."""
    targets = {pid: "Main"}
    for line in threads.splitlines():
        cells = line.split(maxsplit=1)
        if len(cells) == 2 and cells[0].isdigit() and int(cells[0]) != pid:
            tid = int(cells[0])
            label = "RenderThread" if cells[1] == "RenderThread" and "RenderThread" not in targets.values() else f"{cells[1]}:{tid}"
            targets[tid] = label
    begin, end = start_ms / 1000, (start_ms + window_ms) / 1000
    states, totals = {}, {tid: {state: 0.0 for state in ("running", "runnable", "sleeping")} for tid in targets}
    pattern = re.compile(r"\s(\d+\.\d+):\s+sched_switch:\s+.*prev_pid=(\d+).*prev_state=(\S+)\s+==>.*next_pid=(\d+)")

    def transition(tid, at, state):
        if tid not in targets:
            return
        previous = states.get(tid)
        if previous is not None:
            last, kind = previous
            totals[tid][kind] += max(0, min(at, end) - max(last, begin)) * 1000
        states[tid] = (at, state)

    events = 0
    for line in trace.splitlines():
        match = pattern.search(line)
        if match is None:
            continue
        at, prev, state, next_tid = match.groups()
        at = float(at)
        if at > end:
            break
        events += 1
        transition(int(prev), at, "runnable" if state.startswith("R") else "sleeping")
        transition(int(next_tid), at, "running")
    for tid in targets:
        if tid in states:
            transition(tid, end, states[tid][1])
    return {"events": events, "lostEventsMarker": "LOST" in trace,
            "scope": "observed scheduler intervals for threads alive at the final snapshot; sleeping includes waits/idle; not a bottleneck verdict",
            "threads": {targets[tid]: {"tid": tid, **{kind + "Ms": round(value, 3) for kind, value in values.items()},
                                       "unknownMs": round(max(0, window_ms - sum(values.values())), 3)}
                        for tid, values in totals.items()}}


def sections(trace, threads, start_ms, window_ms):
    """Inclusive synchronous atrace slices; nested totals must never be added."""
    labels = {value["tid"]: label for label, value in threads.items()}
    stacks, totals = {}, {label: {} for label in labels.values()}
    pattern = re.compile(r"-(\d+)\s+(?:\(.*?\)\s+)?\[\d+\].*?\s(\d+\.\d+):\s+tracing_mark_write:\s+(.*)")
    begin, end = start_ms / 1000, (start_ms + window_ms) / 1000
    for line in trace.splitlines():
        match = pattern.search(line)
        if match is None:
            continue
        tid, at, event = match.groups()
        tid, at = int(tid), float(at)
        if tid not in labels:
            continue
        stack = stacks.setdefault(tid, [])
        if event.startswith("B|"):
            parts = event.split("|", 2)
            if len(parts) == 3:
                stack.append((at, parts[2]))
        elif (event == "E" or event.startswith("E|")) and stack:
            opened, name = stack.pop()
            elapsed = max(0, min(at, end) - max(opened, begin)) * 1000
            if elapsed:
                entry = totals[labels[tid]].setdefault(name, {"count": 0, "inclusiveMs": 0.0, "longestMs": 0.0})
                entry["count"] += 1
                entry["inclusiveMs"] += elapsed
                entry["longestMs"] = max(entry["longestMs"], elapsed)
    return {"scope": "inclusive wall time, nested slices overlap; not CPU time or additive attribution",
            "unclosedSlices": sum(len(stack) for stack in stacks.values()),
            "threads": {label: [{"name": name, **{key: round(value, 3) for key, value in entry.items()}}
                                 for name, entry in sorted(entries.items(), key=lambda item: -item[1]["inclusiveMs"])[:20]]
                        for label, entries in totals.items()}}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path, help="One pulled cold/warm pair directory")
    args = parser.parse_args()
    print(json.dumps(analyze(args.directory), indent=2))
