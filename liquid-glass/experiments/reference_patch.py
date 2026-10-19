"""Reproduce the conditional #222 patch comparison; no Apple imagery is bundled.

Requires Python 3, numpy and opencv-python-headless; ffprobe on PATH.
Usage: python reference_patch.py /private/path/morph.mp4 > /private/path/result.json
Asset URL/checksum and interpretation: ../optics.md. Does not download anything.
"""
import hashlib
import json
import subprocess
import sys
from pathlib import Path

import cv2
import numpy as np

path = Path(sys.argv[1])
digest = hashlib.sha256(path.read_bytes()).hexdigest()
assert digest == "4feea1c34a97ae1a4853c8f38831b9c7579c739db2c1060b1f4b84d3082d3992", digest
pts = json.loads(subprocess.check_output([
    "ffprobe", "-v", "error", "-select_streams", "v:0", "-show_frames",
    "-show_entries", "frame=best_effort_timestamp_time", "-of", "json", str(path)
]))["frames"]
indices = [65, 80, 95, 225, 240, 255]
frames = {}
cap = cv2.VideoCapture(str(path))
index = 0
while True:
    ok, image = cap.read()
    if not ok:
        break
    if index == 0 or index in indices:
        frames[index] = image.astype(np.float32)
    index += 1
cap.release()
assert index == len(pts) == 323, (index, len(pts))
assert frames[0].shape == (384, 614, 3)
source = frames[0]
targets = [frames[i][50:71, 413:434] for i in indices]


def sample(sigma, dx, dy):
    blurred = cv2.GaussianBlur(source, (0, 0), sigma) if sigma else source
    return cv2.getRectSubPix(blurred, (21, 21), (423.0 + dx, 60.0 + dy))


def tone(a, b):
    a, b = a.reshape(-1, 3), b.reshape(-1, 3)
    da, db = a - a.mean(0), b - b.mean(0)
    gain = np.clip((da * db).sum(0) / np.maximum((da * da).sum(0), 1e-6), 0, 2)
    return gain, b.mean(0) - gain * a.mean(0)


def rmse(a, b):
    return float(np.sqrt(np.mean((a - b) ** 2)))


# Parameters trained previously on frame 65 only (#222); freeze them here.
# Tone may be refit (nuisance) or frozen from training; report BOTH.
models = {
    "identity": (0, 0, 0),
    "blur_only": (3.25, 0, 0),
    "shift_only": (0, .25, 2.5),
    "joint": (3, 0, 2.5),
}
results = {}
for name, parameters in models.items():
    a = sample(*parameters)
    fixed_gain, fixed_bias = tone(a, targets[0])
    per_frame, frozen = [], []
    for target in targets:
        gain, bias = tone(a, target)
        per_frame.append(rmse(a * gain + bias, target))
        frozen.append(rmse(a * fixed_gain + fixed_bias, target))
    results[name] = {
        "sigma_dx_dy": parameters,
        "refit_tone_rmse": per_frame,
        "frozen_tone_rmse": frozen,
        "held_out_mean_refit": float(np.mean(per_frame[1:])),
        "held_out_mean_frozen": float(np.mean(frozen[1:])),
    }

# Known-shift synthetic sign/interpolation control; this validates the estimator,
# not the Apple scene or a physical model. Recover a known grid translation.
known = sample(2, -1, 2.5) * np.array([.8, 1.1, .9]) + np.array([10, 5, 20])
best = (float("inf"), None)
for dx in np.arange(-2, 2.01, .5):
    for dy in np.arange(-3, 3.01, .5):
        a = sample(2, float(dx), float(dy))
        gain, bias = tone(a, known)
        error = rmse(a * gain + bias, known)
        if error < best[0]:
            best = error, (float(dx), float(dy))
assert best[1] == (-1, 2.5) and best[0] < 1e-4, best
identity_error = rmse(sample(0, 0, 0), source[50:71, 413:434])
assert identity_error == 0, identity_error
print(json.dumps({
    "asset_sha256": digest, "opencv": cv2.__version__, "numpy": np.__version__,
    "frame_count": index, "patch_center": [423, 60], "patch_size": 21,
    "frame_indices": indices,
    "presentation_seconds": [float(pts[i]["best_effort_timestamp_time"]) for i in indices],
    "models": results, "synthetic_known_shift": best, "identity_error": identity_error,
    "limits": "Same-scene repeats; no unique normal, depth, IOR, blur kernel or Apple shader recovery."
}, indent=2))
