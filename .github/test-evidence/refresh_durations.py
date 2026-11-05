#!/usr/bin/env python3
"""Rebuild class-durations.csv from full-run `device-reports-api-<api>-shard-<n>` artifacts.

Usage: refresh_durations.py --output class-durations.csv RUN_DIR [RUN_DIR ...]
Each RUN_DIR holds the downloaded artifacts of one complete run, for example from
`gh run download RUN --pattern 'device-reports-api-*' -D RUN_DIR`. A class's cost is the median of its
per-run totals, so passing several runs smooths noise. Every class of every API must appear in each run
exactly once (a missing shard report would silently skew the balance), and the output uses the exact
columns that full_partition.py `shards` reads: api,class,tests,total_seconds,skipped.
The profile is only a scheduling hint: the summary job still proves every method ran exactly once.
"""
import argparse
import csv
import re
import statistics
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

COLUMNS = ['api', 'class', 'tests', 'total_seconds', 'skipped']


def run_totals(run_dir):
    """Return {(api, class): [tests, seconds, skipped]} for one run directory."""
    totals, shards = {}, {}
    for report in sorted(Path(run_dir).glob('device-reports-api-*-shard-*')):
        match = re.fullmatch(r'device-reports-api-(26|36)-shard-(\d+)', report.name)
        if not match or not report.is_dir():
            raise ValueError('Unexpected report folder: ' + report.name)
        api = match.group(1)
        shards.setdefault(api, set()).add(match.group(2))
        files = sorted(report.rglob('*.xml'))
        if not files:
            raise ValueError('No XML in ' + report.name)
        for path in files:
            raw = path.read_bytes()
            if b'<!DOCTYPE' in raw.upper() or b'<!ENTITY' in raw.upper():
                raise ValueError('XML entity declaration refused: ' + str(path))
            for case in ET.fromstring(raw).iter('testcase'):
                if case.find('failure') is not None or case.find('error') is not None:
                    raise ValueError('Failed case in a timing source: ' + case.get('classname', '?') + '#' + case.get('name', '?'))
                key = (api, case.get('classname'))
                row = totals.setdefault(key, [0, 0.0, 0])
                row[0] += 1
                row[1] += float(case.get('time', '0'))
                row[2] += case.find('skipped') is not None
    if set(shards) != {'26', '36'} or len({len(v) for v in shards.values()}) != 1:
        raise ValueError('Incomplete or uneven shard reports: ' + str({k: sorted(v) for k, v in shards.items()}))
    return totals


def merge(runs):
    merged = {}
    for key in sorted(set().union(*runs)):
        rows = [run[key] for run in runs if key in run]
        merged[key] = (max(r[0] for r in rows), round(statistics.median(r[1] for r in rows), 3), max(r[2] for r in rows))
    return merged


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('runs', type=Path, nargs='+')
    args = parser.parse_args()
    try:
        merged = merge([run_totals(run) for run in args.runs])
    except (OSError, ValueError, ET.ParseError) as error:
        print('Duration refresh rejected: ' + str(error), file=sys.stderr)
        return 2
    with args.output.open('w', newline='') as stream:
        writer = csv.writer(stream, lineterminator='\n')
        writer.writerow(COLUMNS)
        for (api, cls), (tests, seconds, skipped) in sorted(merged.items()):
            writer.writerow([api, cls, tests, seconds, skipped])
    return 0


if __name__ == '__main__':
    sys.exit(main())
