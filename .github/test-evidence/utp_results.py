"""Observed assumption reasons from AGP's UTP test-result.pb (#669).

UTP builds this record from the instrumentation status stream, so an assumption's reason survives
when logd drops the per-test logcat entries that carry it. Only the fields used here are decoded
(com.google.testing.platform.proto.api.core.TestResultProto):
TestSuiteResult.test_result = 3 -> TestResult { test_case = 1, test_status = 2, error = 3 },
TestCase { test_class = 1, test_package = 2, test_method = 3 }, Error { error_message = 1,
error_type = 2, stack_trace = 3 }. Anything malformed fails closed.
"""
from pathlib import Path

ASSUMPTION_TYPES = ('org.junit.AssumptionViolatedException', 'org.junit.internal.AssumptionViolatedException')
MAX_BYTES = 64*1024*1024


def _varint(data, index):
    value = shift = 0
    while True:
        if index >= len(data) or shift > 63:
            raise ValueError('Malformed test-result.pb varint')
        byte = data[index]
        index += 1
        value |= (byte & 0x7f) << shift
        shift += 7
        if byte < 0x80:
            return value, index


def fields(data):
    """Yield (number, wire type, value) for one message; length-delimited values stay bytes."""
    index = 0
    while index < len(data):
        key, index = _varint(data, index)
        number, wire = key >> 3, key & 7
        if wire == 0:
            value, index = _varint(data, index)
        elif wire == 1 or wire == 5:
            size = 8 if wire == 1 else 4
            value, index = data[index:index+size], index+size
        elif wire == 2:
            size, index = _varint(data, index)
            value, index = data[index:index+size], index+size
        else:
            raise ValueError('Unsupported test-result.pb wire type')
        if number == 0 or index > len(data):
            raise ValueError('Malformed test-result.pb field')
        yield number, wire, value


def _strings(message):
    return {number: value.decode('utf-8') for number, wire, value in fields(message) if wire == 2}


def assumption_reasons(path):
    """Map 'package.Class#method' to the first message line of each assumption error in one file."""
    path = Path(path)
    if path.is_symlink() or path.stat().st_size > MAX_BYTES:
        raise ValueError('Invalid test-result.pb input')
    observed = {}
    for number, wire, result in fields(path.read_bytes()):
        if number != 3 or wire != 2:
            continue
        case = error = None
        for inner, inner_wire, value in fields(result):
            if inner == 1 and inner_wire == 2:
                case = _strings(value)
            elif inner == 3 and inner_wire == 2:
                error = _strings(value)
        if case is None or error is None or error.get(2) not in ASSUMPTION_TYPES:
            continue
        identity = case.get(2, '')+'.'+case.get(1, '')+'#'+case.get(3, '')
        prefix = error[2]+': '
        lines = (error.get(1) or error.get(3) or '').splitlines()
        reason = lines[0][len(prefix):] if lines and lines[0].startswith(prefix) else None
        observed.setdefault(identity, []).append(reason)
    return observed
