"""Validate exact method selectors against a fresh, complete source inventory."""
import json
from pathlib import Path
import re
import sys


def narrow(inventory, requested):
    methods = requested.split(',')
    pattern = r'io\.github\.anastaci1a\.localica\.[A-Za-z_][A-Za-z0-9_]*#[A-Za-z_][A-Za-z0-9_]*'
    if not requested or len(requested) > 8192 or len(methods) > 100 or len(methods) != len(set(methods)):
        raise ValueError('Require 1..100 unique method selectors, maximum 8192 characters')
    if any(not re.fullmatch(pattern, method) for method in methods):
        raise ValueError('Require literal qualified Class#method selectors')
    if not set(methods) <= set(inventory['runtime_methods']):
        raise ValueError('Requested method is not declared in the selected source classes')
    classes = {method.split('#')[0] for method in methods}
    if classes != set(inventory['selectors']):
        raise ValueError('Every affected class must have an explicitly selected method')
    result = dict(inventory)
    result['runtime_methods'] = sorted(methods)
    result['methods'] = sorted(methods)
    result['method_count'] = len(methods)
    result['classes'] = []
    for source in inventory['classes']:
        entry = dict(source)
        entry['methods'] = [m for m in source['methods'] if m in methods]
        entry['declarations'] = [m for m in source['declarations'] if m['id'] in methods]
        result['classes'].append(entry)
    result['selection'] = 'Explicit source-validated methods; not a complete class run'
    return result


if __name__ == '__main__':
    file = Path(sys.argv[1])
    selected = narrow(json.loads(file.read_text()), sys.argv[2])
    file.write_text(json.dumps(selected, indent=2) + '\n')
