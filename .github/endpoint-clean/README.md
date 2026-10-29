# Clean endpoint correctness validation

Run from the separate clean harness checkout:

```sh
bash .github/endpoint-clean/run-clean.sh "$PWD/source" "$PWD/reviewed-harness" "$PWD/clean-evidence" 26
```

Use API 26 or 36; one hosted disposable emulator per matrix job. Source must be
exact commit `afebeba6d459e97743e6e2dd0bb14f91510db2ca`, tree
`ad0a4c9f29232bf2916198f915ea6e4eb2fb2f16`. Reviewed support must be clean at
`e3ac4f31ed506b55f39e9fa806010106ea0965bd`. Install source publisher npm dependencies
before running. Source, support and new evidence directories must be independent
and non-nested. No checkout/ref mutation is performed. The baseline source's
original package guard is sourced once and restored on exit/cancellation.

Two identical trials C1/C2 each precompile and run only the three original
PopupShadowContinuityTest methods with the original observer/supervisor/publisher.
The pinned external module supplies reviewed source-clean, native XML, monitor,
APK-drift and cancellation/archive helpers. Every selected method must pass,
without skips. Same-SHA and before/after APK identity gates remain strict; APKs,
raw XML, logs, HTML, and additional output are retained even on failure. Fresh
state clears only installed owned debug and debug.test packages on the emulator.

Success requires all 62 named PNGs (32 utility, 30 nested), including both profile
and field exports. Headers/dimensions, original paths and SHA256 are recorded;
native pixel assertions remain the image correctness check. Diagnostic source
files/wrappers and NativeSuiteCost native log tags are forbidden. There is no
required timing probe. Raw method/command durations are original receipts only:
this produces **zero measurement trials**, no speedup comparison and no release
certification. Any failure stops before the next trial; no bypass/retry.

Offline tests:

```sh
python3 -m unittest discover -s .github/endpoint-clean -p 'test_run_clean.py' -v
bash -n .github/endpoint-clean/run-clean.sh
```
