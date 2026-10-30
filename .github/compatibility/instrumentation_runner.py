"""Bind private compatibility APK checks and invocations to the archived candidate runner."""
import json
import re
import sys
from pathlib import Path

SUPPORTED = frozenset({
    'androidx.test.runner.AndroidJUnitRunner',
    'io.github.anastaci1a.localica.NavigationRandomOwnedSession',
})


def validate(value):
    if not isinstance(value, str) or value not in SUPPORTED:
        raise ValueError('Unreviewed compatibility instrumentation runner')
    return value


def from_source(text):
    matches = re.findall(r'^\s*testInstrumentationRunner\s*=\s*"([^"\n]+)"\s*$', text, re.M)
    if len(matches) != 1:
        raise ValueError('Require exactly one literal candidate instrumentation runner')
    return validate(matches[0])


def from_provenance(path):
    return validate(json.loads(Path(path).read_text())['instrumentation_runner'])


if __name__ == '__main__':
    if len(sys.argv) != 2:
        raise SystemExit('Usage: instrumentation_runner.py provenance.json')
    print(from_provenance(sys.argv[1]))
