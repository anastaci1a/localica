# Bounded first-failure follow-on diagnostics

The live observer/publisher pair still emits and uploads its original provisional
failure event immediately. It does not wait for a stack trace. It now follows that
alert with at most one diagnostic event/immutable packet for the **first failure**.
Deploy observe.py and publish.mjs together: an older publisher intentionally rejects
unknown event types rather than silently claiming complete monitoring.

Collection is same-stream only, starts after the matched failure line, and closes
at the first unfamiliar line, EOF, 32 entries, 4096 retained UTF-8 bytes, or five
seconds (the selector loop checks the deadline every 100ms). The observer's existing
64KiB source-line bound still applies. Missing diagnostics produce no extra upload.
Only ASCII Java exception type identifiers and validated Java/Kotlin stack frames
are reconstructed. Exception messages, expected/actual values, arbitrary surrounding
logs, environment, argv, paths and URLs are not copied into live packets. This is
not a generic raw-log redactor and is not intended to defend against intentionally
malicious test code encoding secrets as syntactically valid identifiers.

The follow-on has event type `test_failure_context`, references observation_index=1
and the same command/SHA/API/lane/stream, and sets messages_omitted=true. The publisher
revalidates every field, rejects duplicates/mismatch, and places it in the optional
`failure_contexts` array of a subsequent packet. No failure count is incremented.
The original packet format is unchanged. The existing **six artifacts total** cap
includes this follow-on, so a failure-heavy run has one fewer failure-batch slot.
Cap exhaustion and upload uncertainty retain the pending packet locally and report
monitoring_degraded, with no retries or false completeness.

Tests remain the sole verdict source. Child exit codes, test selections, raw local
command.log, and authoritative XML are unchanged. Diagnostic upload errors cannot
turn failed tests green. An observer IO failure still fails closed under the
existing observer/supervisor contract. Publication is diagnostic, not release proof.

## Known limit

AGP sometimes prints only an exception name/message and one JUnit frame. Because
free-form messages are deliberately omitted, such a packet improves failure type
visibility but may still require final XML for the exact assertion. In particular,
replaying API26 run40686778656 yields java.lang.AssertionError plus
org.junit.Assert.fail(Assert.java:89), not its expected/actual color values.

## Offline verification

Run `python -m unittest discover -s .github/tests -p 'test_*py'` and
`node --test .github/tests/test_publish_test_evidence.mjs` for this isolated fixture.
There are 33 Python and 22 Node tests, including a real subprocess handshake that
withholds diagnostics until upload1, then withholds exit7 until upload2. This proves
live ordering without timing sleeps, preserves the nonzero exit, and confirms the
secret-bearing exception message stays local. Additional tests cover timeout, EOF,
line/byte caps, stream isolation, oversized source lines, duplicate/malformed events,
plain Error/Exception names, and identifier grammar consistency.
