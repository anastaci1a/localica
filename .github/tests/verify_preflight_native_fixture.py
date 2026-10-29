"""Independent exact XML check for the tiny native protocol fixture."""
import json
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

PREFIX = 'io.github.anastaci1a.localica.'
RISK = {PREFIX+'PreflightRiskOneTest#riskOneExecutes',
        PREFIX+'PreflightRiskTwoTest#riskTwoExecutes'}
REMAINDER = {PREFIX+'PreflightRemainderTest#remainderExecutes'}

def require(condition, message):
    if not condition:
        raise ValueError(message)

def check(folder, expected):
    files = sorted(folder.rglob('*.xml'))
    require(files, 'No native XML: '+str(folder))
    observed = []
    for path in files:
        root = ET.parse(path).getroot()
        require(root.tag == 'testsuite', 'Unexpected XML root')
        cases = root.findall('testcase')
        require(int(root.get('tests')) == len(cases), 'Mismatched count')
        for key in ('failures', 'errors', 'skipped'):
            require(int(root.get(key, '0')) == 0, 'Nonpassing suite')
        for case in cases:
            require(not any(case.find(k) is not None for k in ('failure', 'error', 'skipped')), 'Nonpassing case')
            observed.append(case.attrib['classname']+'#'+case.attrib['name'])
    require(len(observed) == len(set(observed)), 'Duplicate method')
    require(set(observed) == expected, 'Missing or extra method: '+str(observed))
    return observed

root = Path(sys.argv[1])
mode = sys.argv[2]
if mode == 'option':
    risk = check(root/'option-risk', RISK)
    remaining = check(root/'option-remainder', REMAINDER)
else:
    require(mode == 'union', 'Unknown check mode')
    risk = check(root/'preflight-reports/app/build/outputs/androidTest-results', RISK)
    remaining = check(root/'app/build/outputs/androidTest-results', REMAINDER)
    receipt = json.loads((root/'preflight-reports/complete-union.json').read_text())
    require(receipt['status'] == 'complete_union' and receipt['executed_once'] == 3 and receipt['skipped'] == 0, 'Invalid receipt')
    require(set(receipt['preflight']) == RISK and set(receipt['remaining']) == REMAINDER, 'Invalid receipt identities')
require(not set(risk) & set(remaining), 'Overlapping phases')
print(json.dumps({'mode': mode, 'preflight': sorted(risk), 'remainder': sorted(remaining), 'executed_once': 3, 'skipped': 0}))
