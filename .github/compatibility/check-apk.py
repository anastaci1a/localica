#!/usr/bin/env python3
import re
import sys
from pathlib import Path
badging = Path(sys.argv[1]).read_text()
kind, code = sys.argv[2:4]
package = re.search(r"^package: name='([^']+)' versionCode='([^']*)'", badging, re.M)
assert package, badging
assert package[1] == 'io.github.anastaci1a.localica.debug' + ('.test' if kind == 'test' else '')
if kind == 'app':
    assert package[2] == code
else:
    manifest = Path(sys.argv[4]).read_text()
    block = re.search(r"E: instrumentation.*?(?=\n\s*E:|\Z)", manifest, re.S)
    assert block, manifest
    assert re.search(r'android:name\([^)]*\)="androidx.test.runner.AndroidJUnitRunner"', block[0])
    assert re.search(r'android:targetPackage\([^)]*\)="io.github.anastaci1a.localica.debug"', block[0])
