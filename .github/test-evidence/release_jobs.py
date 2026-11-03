"""Require complete verification jobs before publishing an exact-commit APK."""
import json
import sys

REQUIRED = {'build', 'device-tests-summary', 'compatibility (26)', 'compatibility (36)',
            'distribution-smoke (26)', 'distribution-smoke (36)'}


def verify(jobs):
    if any(job['name'] == 'device-tests-risk-only-summary' for job in jobs):
        raise ValueError('Risk-only verification cannot authorize a release')
    successful = {job['name'] for job in jobs if job['conclusion'] == 'success'}
    missing = REQUIRED - successful
    if missing:
        raise ValueError('Missing successful release verification: ' + ', '.join(sorted(missing)))


if __name__ == '__main__':
    try:
        with open(sys.argv[1]) as stream:
            verify(json.load(stream)['jobs'])
    except (OSError, ValueError, KeyError, TypeError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
