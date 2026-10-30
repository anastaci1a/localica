# Frozen workflow oracle

These four files are exact Git blobs from `1888c9a990363cdce3a6b48790d680c09454e002`, the sole parent of setup extraction `1a523385bd411a0ad23785988c9ad9085f93a9b8`. `provenance.json` records their original paths, blob IDs and full-file SHA256 values. They are independent pre-extraction inputs, not regenerated from the current action. CI needs no historical Git objects or network to read them.

`test_focused_workflows.py` expands the current setup action at its original position and compares complete decoded workflow documents, including triggers, jobs, matrices, commands, conditions and artifacts. The sole equivalence normalization adds `bash -e {0}` to implicit Ubuntu run steps; it does not rewrite command text or other fields. Negative mutations pin selectors, profiles, commands, retention and failure/status sensitivity. Comments and YAML presentation are outside decoded equality; fixture byte hashes remain checked separately.

This is an offline source-expansion contract, not proof of hosted composite post-step timing, shell execution, emulator state, supervision delivery or native outcomes. Do not refresh the frozen originals to make a changed workflow pass without a separately reviewed policy decision.
