# T3 alpha timing report (#600)

Status: rejected on 2026-11-10. The fused alpha candidate failed its whole-frame gain gate on the Galaxy A35. This historical report records an unadopted experiment; it does not establish shipped behavior.

## Fresh plain-build ABBA: alpha rejected

The fresh plain-build campaign fails the gain gate. Pair 1 regresses whole-frame p90 by 1.065001 ms; pair 2 improves by 3.715193 ms. The required improvement is at least 0.5 ms in every complete pair. Alpha stops. Protected-exactness engineering and the four review gaps will not resume for this candidate.

| Capture | Frames | p50 ms | p90 ms | p99 ms | >8.33 ms | >33 ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| plain-ab-a1 | 166 | 20.888808 | 31.919807 | 40.337730 | 163 | 9 |
| plain-ab-b1 | 166 | 19.436077 | 32.984808 | 47.259577 | 163 | 16 |
| plain-ba-b2 | 169 | 20.721577 | 30.358769 | 49.558231 | 163 | 13 |
| plain-ba-a2 | 154 | 20.913000 | 34.073962 | 48.251962 | 146 | 16 |

| Matched pair | A0 p90 ms | Alpha p90 ms | Gain ms | Pass |
| --- | ---: | ---: | ---: | --- |
| plain-ab-a1 / plain-ab-b1 | 31.919807 | 32.984808 | −1.065001 | No |
| plain-ba-a2 / plain-ba-b2 | 34.073962 | 30.358769 | 3.715193 | Yes |

Median matched gain is 1.325096 ms. It cannot waive the failed first pair. Candidate p99 regresses by 6.921847 and 1.306269 ms respectively. The 8.3 ms whole-frame target remains unmet. No adoption is justified.

### Why the earlier gain did not survive

The earlier raw gains of 3.278192 and 0.693539 ms appeared only in the superseded profileable campaign. Its control failed and used unequal trace configurations. A second control with identical FrameTimeline-only configuration also failed. Those gains were therefore never a qualified plain-build result.

The plain remeasurement did not reproduce a gain in every pair. This is the bounded meaning of “profileable-only gain.” It does not isolate profileability as the sole cause: the original campaign also changed tracing, and both source arms vary across repetitions. No numeric causal correction can turn the old gains into plain results.

### Plain protocol and receipt review

All four captures used plain, non-profileable, non-debuggable R8 release-type APKs. Schedule: A0, alpha, alpha, A0. Each arm received the same warm-up, speed-profile compilation, renderer selection, calibrated three-cycle paused-player scenario and identical FrameTimeline-only configuration. Frames were never pooled; percentiles use nearest rank per capture.

Both manifest variants have identical DEX bytes for their respective sources. A0 source is ab68d696aaf5736ca315d13f9abce2aa338c0775. Candidate source is 51f59e8520f519c5ba8f963afa13f1090f4bd3a1; its production code is unchanged from a710697ecfed650c4af1cb12c48c6c3f6f49ffaf.

The supplementary frame audit found no missing or ambiguous expected-frame joins. Expected intervals were 8.333333 ms for 161/160/164/148 frames and 16.666667 ms for 5/6/5/6 frames in capture order. The latter were retained. Screenrecord was off throughout. Thermal status was 0; battery temperatures were 29.3–31.2 °C.

FrameTimeline-only captures qualify the bounded whole-frame gain comparison, not Main/RT/GPU segments, native raster costs or final performance acceptance. Correctness remains incomplete: protected-complement failures and all four review gaps are unresolved.

### Artifact identities

| Plain APK | SHA-256 |
| --- | --- |
| A0 | 7113fcdcc7143cfae650ed2dfabcb68c9fdc746f8cb3cce095b69be5053293fd |
| Alpha | ba4f43163dfc7234bd8b1ea4acd2f71df10dd13c2918e107021105b00178916d |

The private evidence packet retains the frozen schedule, scripts, configuration, per-capture traces, installed-byte attestations, badging, dexopt, calibrated input and endpoint receipts. Device settings and baseline APK were restored; the phone was released after the final capture.
