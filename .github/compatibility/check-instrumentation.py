#!/usr/bin/env python3
"""Reject silent adb success, skips, crashes, incomplete runs, and unexpected tests."""
import re
import sys
from pathlib import Path
text = Path(sys.argv[1]).read_text()
expected = int(sys.argv[2])
codes = [int(n) for n in re.findall(r'^INSTRUMENTATION_STATUS_CODE: (-?\d+)\s*$', text, re.M)]
assert codes.count(1) == expected and codes.count(0) == expected, codes
assert len(codes) == 2 * expected and all(n in (0, 1) for n in codes), codes
assert re.findall(r'^INSTRUMENTATION_CODE: (-?\d+)\s*$', text, re.M) == ['-1']
assert re.search(rf'^OK \({expected} tests?\)\s*$', text, re.M), text
assert not re.search(r'INSTRUMENTATION_(?:FAILED|ABORTED)|FAILURES!!!|Process crashed|shortMsg=|AssumptionViolatedException', text, re.I), text
