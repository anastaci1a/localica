# Deferred export control for #600

This branch changes instrumentation only. It is not an authoritative None baseline fixture. The original Stage 0 fixture stays frozen on stage0/600.

Set `uiPerformanceDeferExport=true` and the full `uiPerformanceInstrumentationSource` for the explicit experiment. FrameTiming continues buffering frames in memory through Stop. The test disposes its scene and closes its ActivityScenario before report/JSON export, then constructs and admits the next scene normally. Actions and recorded intervals remain the same; lifecycle/export order deliberately differs.

Deferred receipts carry `stage0-parity-deferred-v1`, which the authoritative Stage 0 validator rejects. App source must match the reported target build source; installed APK hashes bind the separately compiled instrumentation to the experimental source. The historical pre-tag control still requires the exact dba7f120 source and is never admitted as a schema-2 baseline.
