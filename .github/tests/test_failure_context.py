"""Offline bounded, same-stream diagnostic contract tests."""
import importlib.util
import json
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('observer', Path(__file__).resolve().parents[1] / 'test-evidence/observe.py')
o = importlib.util.module_from_spec(spec)
spec.loader.exec_module(o)

class FailureContextTest(unittest.TestCase):
    def setUp(self):
        self.events = []
        self.clock = 0
        self.c = o.FailureContext(self.events.append, now=lambda: self.clock)
        self.c.start({'observation_index': 1, 'stream': 'stdout'})

    def line(self, value, stream='stdout'):
        self.c.line(value.encode(), stream)

    def test_exception_message_and_unrelated_output_never_copied(self):
        self.line('\tjava.lang.AssertionError: bearer SECRET expected:<SECRET>\n')
        self.line('\tat org.junit.Assert.fail(Assert.java:89)\n')
        self.line('token=SECRET\n')
        self.assertNotIn('SECRET', json.dumps(self.events))
        self.assertEqual(self.events[0]['entries'], [
            {'kind':'exception_type','value':'java.lang.AssertionError'},
            {'kind':'frame','value':'org.junit.Assert.fail(Assert.java:89)'}])
        self.assertEqual(self.events[0]['end_reason'], 'boundary')

    def test_other_stream_not_attributed_and_only_first_failure(self):
        self.line('\tjava.lang.AssertionError: hidden', 'stderr')
        self.line('\tjava.lang.IllegalStateException: hidden')
        self.c.finish('eof')
        self.c.start({'observation_index': 2, 'stream':'stdout'})
        self.line('\tjava.lang.AssertionError: hidden')
        self.c.finish('eof')
        self.assertEqual(len(self.events), 1)
        self.assertEqual(len(self.events[0]['entries']), 1)

    def test_deadline_does_not_wait_for_new_line_or_child_exit(self):
        self.line('\tjava.lang.AssertionError: hidden')
        self.clock = 5
        self.c.tick()
        self.assertEqual(self.events[0]['end_reason'], 'timeout')
        self.line('\tat org.junit.Assert.fail(Assert.java:89)')
        self.assertEqual(len(self.events[0]['entries']), 1)

    def test_missing_diagnostics_produces_no_extra_artifact(self):
        self.clock = 5
        self.c.tick()
        self.assertEqual(self.events, [])

    def test_line_cap(self):
        for _ in range(100): self.line('\tat org.junit.Assert.fail(Assert.java:89)')
        self.assertEqual(len(self.events[0]['entries']), 32)
        self.assertEqual(self.events[0]['end_reason'], 'limit')

    def test_plain_error_exception_and_ascii_grammar(self):
        for kind in ['java.lang.Exception', 'java.lang.Error']:
            self.setUp()
            self.line('\t' + kind + ': secret')
            self.c.finish('eof')
            self.assertEqual(self.events[0]['entries'][0]['value'], kind)
        self.setUp()
        self.line('\tjava.lang.AÉException: secret')
        self.assertEqual(self.events, [])

    def test_byte_cap(self):
        self.line('\tjava.lang.AssertionError: hidden')
        self.line('\tat org.' + 'x'*5000 + '.fail(X.java:1)')
        self.assertEqual(len(self.events[0]['entries']), 1)
        self.assertEqual(self.events[0]['end_reason'], 'limit')

    def test_paths_urls_command_sequences_are_not_frames(self):
        for bad in ['\tat org.Test.foo(/SECRET/file.kt:1)', '\tat org.Test.foo(https://secret)', '\t::error::SECRET']:
            self.setUp()
            self.line('\tjava.lang.AssertionError: hidden')
            self.line(bad)
            self.assertEqual(len(self.events[0]['entries']), 1)

    def test_stream_parser_immediate_failure_before_context_and_next_failure(self):
        events=[]
        context=o.FailureContext(events.append)
        def emit(result, stream, line, offset):
            event={'observation_index':len([e for e in events if e.get('failure')])+1,'stream':stream,'failure':True}
            events.append(event); context.start(event)
        p=o.LineParser('stdout', emit, context)
        p.feed(b'example.Test > first FAILED\n')
        self.assertEqual(len(events),1)
        p.feed(b'\tjava.lang.AssertionError: SECRET\n\tat org.junit.Assert.fail(Assert.java:89)\nexample.Test > second FAILED\n')
        self.assertEqual([e.get('failure',False) for e in events],[True,False,True])
        self.assertEqual(events[1]['observation_index'],1)

    def test_oversize_line_closes_context_without_swallowing_next_failure(self):
        events=[]; c=o.FailureContext(events.append)
        c.start({'observation_index':1,'stream':'stdout'})
        failures=[]
        p=o.LineParser('stdout', lambda *args:failures.append(args),c)
        p.feed(b'\tjava.lang.AssertionError: SECRET\n')
        p.feed(b'x'*(o.MAX_LINE+1)+b'\n')
        self.assertEqual(events[0]['end_reason'],'limit')
        p.feed(b'example.Test > second FAILED\n')
        self.assertEqual(len(failures),1)

if __name__ == '__main__': unittest.main()
