# v0.33.19 gallery update investigation

Date: 2026-11-09, America/New_York. Status: investigation closed by the lead; no application change.

## Decision

The severe v0.33.19 regression was not reproduced on the matched five-column fixture. Cold v0.33.19 was faster than cold v0.33.18. The lead treated the smaller warm differences as run variation and closed the release-regression question. Existing row-entry cost belongs to #406 and the performance architecture work.

The 8.3 ms whole-frame target remains unmet in both versions. These results neither certify that target nor establish statistical equivalence. No #635 ablation, production fix, new regression test, release or CI dispatch followed the closure decision.

## Method and identities

The source revisions were v0.33.18, `aad90026f9fd9c9d40a95fe9aabb5e92ff4d8e77`, and v0.33.19, `96283e29f1c179c0521133808c87ff3287661116`. Both original GitHub release APKs were downloaded and their release hashes verified. The draft status of v0.33.19 did not change the tested asset.

| APK | Version code | SHA-256 |
| --- | --- | --- |
| localica-v0.33.18-debug.apk | 108 | `7a1c24c82449aeebf0a09277f3660571ac5d71ba1882814a22c6589f92e173ab` |
| localica-v0.33.19-debug.apk | 109 | `73bdd6c788ef4018d6a1632a05c50ca8a9e81ab3e170773f44d9d00a07c0eae0` |

Despite their filenames, both APKs are R8 distribution builds and non-debuggable. Badging and manifest receipts were retained. Testing used a leased Galaxy A35, Android 16, 1080×2340, configured for 120 Hz. Renderer None, manual brightness 2, airplane mode, disabled Wi-Fi/Bluetooth and existing accessibility services were held unchanged. Screen recording stayed off.

The library contained 362 tracks, including 240 distinct embedded JPEGs at 1024–3000 pixels. Deterministic variants used all eighteen shared generated cover fixtures. All 240 on-device audio files matched the fixture manifest. The unfiltered root was saved as Gallery with five columns and verified across a process restart before either update.

The actual update chain was a warmed, speed-profile-compiled v0.33.17 installation, then an in-place v0.33.18 update, then an in-place v0.33.19 update. Android refused a non-debuggable downgrade even with retained data, so the dedicated test package was reseeded beforehand. Separate performance-package data and grants were preserved.

Cold captures started before first launch after each update. No compilation was forced on the new version before its cold capture. Both cold arms reported `speed-profile/install-spqr` before launch and after the first minute; that status alone does not establish effective hot-path compilation. The scripted windows lasted about 61 seconds.

Warm captures followed ProfileInstaller INSTALL_PROFILE result 1, SAVE_PROFILE result 12 and successful forced `speed-profile` compilation. The live process and previously traversed viewports warmed image caches before repetitions. This does not guarantee that every catalog image remained resident or that all decodes and JIT work stopped.

Each fling arm used six alternating calibrated drags: x=540, y=1850↔650, duration 110 ms, followed by 1200 ms waits. Pre-capture gallery witnesses required five artwork x positions, 40/244/448/652/856, and the first fixture album. Cold post-capture witnesses independently confirmed five columns. All six valid arms passed input checks; maximum observed event scheduling error was 0.634 ms, below the 2 ms limit.

Perfetto recorded FrameTimeline, scheduling, graphics, view, ART, database and disk activity. No screenshots or UIAutomator commands ran inside the valid trace windows. Scripted boundary clocks separated launch/navigation, settled gallery, fling, settling tail, player open and player idle. Pre-capture battery temperatures were 31.1–31.6 °C; thermal status was zero throughout.

Percentiles below are nearest rank, `ceil(n × p)`, independently per capture and phase. Frames were never pooled. Authoritative summaries were recomputed from raw FrameTimeline rows; inherited `analysis.csv` quantile rounding is not authoritative. Counts use whole-frame durations greater than 8.333333 ms and 33.0 ms. All times below are milliseconds.

## Valid cold arms

Only `root18-cold` and `root19-cold` supply the cold comparison. Launch includes the delayed Home-to-Library navigation. The settling-tail window includes completion of the last fling and pending image/reveal work; its frames are not an idle-loop finding. Player open windows include subsequent rest.

| Arm / window | Frames | p50 | p90 | p99 | Maximum | >8.33 ms | >33 ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| v0.33.18, whole capture | 421 | 46.584 | 106.889 | 197.202 | 231.076 | 419 | 284 |
| v0.33.18, launch (7.68 s) | 187 | 17.266 | 49.286 | 100.966 | 210.216 | 185 | 55 |
| v0.33.18, initial-gallery-idle (6.07 s) | 0 | — | — | — | — | 0 | 0 |
| v0.33.18, gallery-flings (19.64 s) | 215 | 57.194 | 139.255 | 200.962 | 231.076 | 215 | 211 |
| v0.33.18, after-fling-idle (12.08 s) | 9 | 40.161 | 59.695 | 59.695 | 59.695 | 9 | 9 |
| v0.33.18, player-open (6.18 s) | 10 | 77.312 | 154.570 | 162.234 | 162.234 | 10 | 9 |
| v0.33.18, player-idle (10.08 s) | 0 | — | — | — | — | 0 | 0 |
| v0.33.19, whole capture | 494 | 42.833 | 82.221 | 147.842 | 188.030 | 492 | 327 |
| v0.33.19, launch (7.75 s) | 207 | 18.376 | 49.499 | 78.520 | 188.030 | 205 | 54 |
| v0.33.19, initial-gallery-idle (6.08 s) | 0 | — | — | — | — | 0 | 0 |
| v0.33.19, gallery-flings (18.71 s) | 265 | 48.852 | 96.198 | 147.842 | 163.995 | 265 | 259 |
| v0.33.19, after-fling-idle (12.08 s) | 10 | 32.472 | 33.170 | 39.271 | 39.271 | 10 | 2 |
| v0.33.19, player-open (6.18 s) | 12 | 61.165 | 129.245 | 154.239 | 154.239 | 12 | 12 |
| v0.33.19, player-idle (10.08 s) | 0 | — | — | — | — | 0 | 0 |

Both settled gallery windows and both 10.08-second player idle windows had exactly zero app frames: 0 frames/s. There was no continuous invalidation loop in those observed windows. The cold fling p90 changed from 139.255 to 96.198 ms.

## Valid warm arms

These are `root18-warm-entry`, `root18-warm-cached`, `root19-warm-entry` and `root19-warm-cached`. The actual order was A,A,B,B, not an ABBA acceptance campaign. The names distinguish repetitions, not a claim of zero cache misses.

| Arm | Frames | p50 | p90 | p99 | Maximum | >8.33 ms | >33 ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| v0.33.18, warm entry | 263 | 45.438 | 82.371 | 107.089 | 128.965 | 263 | 258 |
| v0.33.18, warm cached | 249 | 47.442 | 79.280 | 101.844 | 110.959 | 249 | 247 |
| v0.33.19, warm entry | 249 | 47.056 | 82.996 | 107.434 | 115.544 | 249 | 246 |
| v0.33.19, warm cached | 244 | 49.542 | 87.098 | 169.171 | 186.405 | 244 | 238 |

The two warm p90 differences, v0.33.19 minus v0.33.18, were +0.624 and +7.819 ms. The larger second-run tail, including its 186.405 ms maximum, is retained. These sequential observations do not isolate a small code effect from process history, cache state and run variation. The lead accepted them as insufficient evidence of a severe regression.

## Exclusions and corrections

- Initial captures produced empty trace files. Their zero-frame outputs were invalidated. Final captures require device CLOSE_WRITE acknowledgement, nonzero bytes and a SHA-256 receipt.
- The initial two-JPEG fixture was withdrawn as evidence for a realistic new-picture workload, despite its distinct URI cache keys. The final fixture has 240 distinct JPEG contents.
- Early navigation could miss the first Library tap. Retapping the tab could reset a filter. A text-only helper could also tap the Library heading instead of its tab.
- Earlier distinct-cover arms therefore measured Home or one-column Lists. Their timings are excluded from the five-column comparison, including the earlier `final18-cold` attempt.
- The isolated `verified-v19-gallery-entry` trace verified the density effect but had no valid matched v0.33.18 arm. It is not part of the final comparison.
- Its proposed cached repetition failed the first-item witness and did not create a usable trace. No timing was inferred from that failure.
- One early exploratory update capture included an additional read-only settings query during recording. It is excluded from the final comparison.

The final root-view witnesses and restart check supersede those attempts. Invalid and exploratory artifacts remain identified in private evidence. No failed attempt was silently substituted or pooled.

## ART and baseline-profile hypothesis

Both APKs contain `assets/dexopt/baseline.prof` and `baseline.profm`, register ProfileInstaller and have profile dex checksums matching their APK dex ZIP entries. Profile source is identical across releases.

| Profile inventory | v0.33.18 | v0.33.19 |
| --- | ---: | ---: |
| baseline.prof bytes | 4955 | 5034 |
| baseline.profm bytes | 581 | 582 |
| Profile class entries | 4685 | 4755 |
| Hot-method entries | 18463 | 18765 |
| Dex method IDs | 39465 | 39772 |

A remaining hypothesis is that post-update ART/JIT state, together with incomplete effective coverage from the wildcard baseline profile, explains the initially worse experience. Existing row-entry composition, layout, decode and upload costs can become more visible before warm-up. This is a hypothesis for #406, not an established release-specific cause.

The evidence does not show a missing or corrupt packaged profile. Sampled large app methods that appeared in exploratory startup JIT traces were explicitly marked hot/startup/post-startup in the packaged profile. JIT activity alone therefore does not prove those methods were omitted. Effective runtime compilation coverage still requires separate measurement.

The artwork loader, image/reveal code, library schema and application startup source are unchanged between the two release revisions. New tag-batch startup reads execute on IO. This reseeded library had no retained tag batch or configured online account, so conditional recovery or synchronization workloads are not excluded universally.

## Disposition and limits

The lead closed the severe-regression question and stopped further analysis and the planned #635 ablation. Existing gallery row-entry and baseline-profile work remains under #406 and the performance architecture program. No application files changed, and no fix commit or failing regression test is claimed.

This was a reproduction study, not full performance acceptance. It lacks counterbalanced pairs, a non-profileable control pair, direct per-item timing and a trace-measured row-distance cadence. Interpret under performance-standard §§G1, G3, T3, T7, T9 and M1–M5. Existing architectural costs and the whole-frame budget remain unresolved.

At 2026-11-10 01:59:11 UTC, the A35 was restored and released. Animation scales were 0.5, brightness manual 2, radios unchanged, stay-awake disabled and screenrecord off. The test package was force-stopped, the phone slept, and the lease was removed. Agent-97 was notified first, followed by the queued holders. Raw traces, fixture references and machine-specific receipts remain outside Git.
