"""Collect paired process-cold/warm trials on an explicitly disposable emulator."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import time
import uuid
from analyze import analyze

APP = "io.github.anastaci1a.localica.debug"
TEST = APP + ".test"
PREFIX = "io.github.anastaci1a.localica."


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--pairs", type=int, default=5)
    parser.add_argument("--artwork", choices=("embedded", "none"), default="embedded")
    parser.add_argument("--columns", type=int, choices=range(1, 6), default=5)
    parser.add_argument("--trace", action="store_true")
    parser.add_argument("--admit-disposable-emulator", action="store_true", required=True,
                        help="Authorizes clearing the target app's data on this owned emulator")
    args = parser.parse_args()
    if not args.serial.startswith("emulator-") or not 1 <= args.pairs <= 20:
        parser.error("Require an emulator serial and 1..20 pairs")
    os.umask(0o002)
    args.output.mkdir(parents=True, exist_ok=False)
    (args.output / "invocation.json").write_text(json.dumps(vars(args), default=str, indent=2) + "\n")

    def adb(*command, timeout=180, allow_failure=False):
        completed = subprocess.run(["adb", "-s", args.serial, *command], text=True,
                                   capture_output=True, timeout=timeout)
        if completed.returncode and not allow_failure:
            raise RuntimeError(f"adb failed ({completed.returncode}): {completed.stdout}{completed.stderr}")
        return completed.stdout + completed.stderr

    def instrument(name, options, record, timeout=600):
        command = ["shell", "am", "instrument", "-w", "-r", "-e", "startupDiagnostics", "disposable-emulator"]
        for key, value in options.items():
            command += ["-e", key, str(value)]
        result = adb(*command, TEST + "/" + PREFIX + name, timeout=timeout, allow_failure=True)
        record.write_text(result)
        success = "INSTRUMENTATION_CODE: 0" in result if name == "StartupProcessDiagnostics" else "OK (1 test)" in result
        if not success or "INSTRUMENTATION_FAILED" in result:
            raise RuntimeError(result)
        return result

    if adb("shell", "getprop", "ro.kernel.qemu").strip() != "1":
        raise RuntimeError("Not an emulator; no data cleared")
    inventory = adb("shell", "pm", "list", "instrumentation")
    (args.output / "instrumentation-inventory.txt").write_text(inventory)
    for name in ("NavigationRandomOwnedSession", "StartupProcessDiagnostics"):
        if TEST + "/" + PREFIX + name not in inventory:
            raise RuntimeError(f"Missing registered runner: {name}; no data cleared")
    (args.output / "device.txt").write_text(adb("shell", "getprop", "ro.build.fingerprint") +
                                            adb("shell", "wm", "size") + adb("shell", "wm", "density") +
                                            adb("shell", "dumpsys", "display"))
    (args.output / "reset.txt").write_text(adb("shell", "pm", "clear", APP))
    instrument("NavigationRandomOwnedSession", {"columns": args.columns, "artwork": args.artwork,
               "class": PREFIX + "StartupDiagnosticsSeedTest#seedDisposableEmulator"}, args.output / "seed.txt")
    adb("shell", "am", "force-stop", APP)
    for pair in range(1, args.pairs + 1):
        label = f"pair-{pair}-{uuid.uuid4().hex[:8]}"
        started = time.monotonic()
        host_load_before = os.getloadavg()
        log = subprocess.Popen(["adb", "-s", args.serial, "logcat", "-T", "1", "-v", "threadtime"],
                               stdout=(args.output / f"{label}-logcat.txt").open("w"), stderr=subprocess.STDOUT)
        try:
            instrument("StartupProcessDiagnostics", {"label": label,
                "fixture": f"synthetic-{args.artwork}-{args.columns}columns", "trace": str(args.trace).lower()},
                args.output / f"{label}-instrumentation.txt")
        finally:
            log.terminate()
            log.wait(timeout=15)
            # Retain partial evidence even if the controller failed.
            (args.output / f"{label}-pull.txt").write_text(adb("pull",
                f"/sdcard/Android/data/{TEST}/files/startup-diagnostics/{label}", str(args.output), allow_failure=True))
        receipt = analyze(args.output / label)
        receipt["elapsedSeconds"] = time.monotonic() - started
        receipt["hostLoadAverageBefore"] = host_load_before
        receipt["hostLoadAverageAfter"] = os.getloadavg()
        receipt["hostCpuCount"] = os.cpu_count()
        (args.output / f"{label}-summary.json").write_text(json.dumps(receipt, indent=2) + "\n")
        print(json.dumps(receipt), flush=True)


if __name__ == "__main__":
    main()
