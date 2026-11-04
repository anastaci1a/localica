#!/usr/bin/env python3
import re
import sys
from pathlib import Path
from instrumentation_runner import validate
runner = validate(sys.argv[5])
badging = Path(sys.argv[1]).read_text()
kind, code = sys.argv[2:4]
package = re.search(r"^package: name='([^']+)' versionCode='([^']*)'", badging, re.M)
assert package, badging
assert package[1] == 'io.github.anastaci1a.localica.debug' + ('.test' if kind == 'test' else '')
if kind == 'app':
    assert package[2] == code
else:
    manifest = Path(sys.argv[4]).read_text()
    blocks = re.findall(r"E: instrumentation.*?(?=\n\s*E:|\Z)", manifest, re.S)
    app = 'io.github.anastaci1a.localica.debug'
    # The opt-in startup diagnostics controller (#406) is the only reviewed extra block.
    # It instruments the test package itself and cannot be reached by the primary runner.
    extra = ('io.github.anastaci1a.localica.StartupProcessDiagnostics', app + '.test')
    found = []
    for block in blocks:
        names = re.findall(r'android:name\([^)]*\)="([^"]+)"', block)
        targets = re.findall(r'android:targetPackage\([^)]*\)="([^"]+)"', block)
        assert len(names) == 1 and len(targets) == 1, manifest
        found.append((names[0], targets[0]))
    assert found.count(extra) <= 1, manifest
    primary = [item for item in found if item != extra]
    assert primary == [(runner, app)], manifest
