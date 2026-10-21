"""Renderer-independent acceptance of a frozen reconstruction experiment.

Measurements are RGB RMSE in 0..255, measured before or after quantization as
specified by the immutable epoch. This gate cannot validate visual correctness;
review and evidence are separate required inputs. It never edits old ledgers.
"""
from __future__ import annotations
import argparse
import json
import math
from pathlib import Path

ERROR_RATIO = .995
WEIGHTS = {'glass': .7, 'rim': .3}


def metric(score: dict, key: str) -> float:
    value = score[key]
    value = value['rmse'] if isinstance(value, dict) else value
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or value < 0:
        raise ValueError(f'Invalid RMSE for {key}: {value!r}')
    return float(value)


def error(score: dict) -> float:
    return math.sqrt(sum(weight * metric(score, key)**2 for key, weight in WEIGHTS.items()))


def evaluate(previous: dict, candidate: dict, *, visual_review: bool,
             replay_verified: bool, source_support_verified: bool, policy: str = 'nonregressive') -> dict:
    """Require matching named observations and an identical measurement epoch.

    epoch is a content digest of references, sources, masks, registration,
    metric definitions and sampling protocol; not of the changing model.
    Optional foreground/shadow errors become non-regression guards when present
    in the incumbent. Removing them is not permitted. Crops are advisory.
    """
    if policy not in ('nonregressive', 'relative'):
        raise ValueError('Unknown improvement policy')
    if not previous.get('epoch') or previous['epoch'] != candidate.get('epoch'):
        raise ValueError('Epoch mismatch: remeasure source/protocol changes separately')
    before, after = previous['observations'], candidate['observations']
    if not before or set(before) != set(after):
        raise ValueError('Active observation identities must match exactly')
    reports = {}
    for name in sorted(before):
        old, new = before[name], after[name]
        E0, E1 = error(old), error(new)
        guards = {key: metric(new, key) <= metric(old, key)
                  for key in ('glass', 'foreground', 'shadow') if key in old}
        # Zero residual is terminal, not an infinite source of successful ties.
        improved = E0 > 0 and E1 < E0
        passed = (E0 > 0 and E1 <= ERROR_RATIO * E0) if policy == 'relative' else E1 <= E0
        reports[name] = {'previous_error': E0, 'candidate_error': E1,
                         'required_error_max': (ERROR_RATIO if policy == 'relative' else 1.) * E0,
                         'relative_reduction': (1-E1/E0) if E0 else None,
                         'milestone_passed': passed, 'strict_improvement': improved, 'nonregression': guards,
                         'passed': passed and all(guards.values())}
    checks = {'visual_review': visual_review is True,
              'independent_replay': replay_verified is True,
              'source_support': source_support_verified is True}
    accepted = (all(r['passed'] for r in reports.values())
                and any(r['strict_improvement'] for r in reports.values())
                and all(checks.values()))
    return {'accepted': accepted, 'epoch': previous['epoch'], 'policy': policy, 'error_ratio': ERROR_RATIO if policy == 'relative' else 1.,
            'observations': reports, 'evidence': checks}


def progress(baseline_error: float, current_error: float, count: int, *, policy: str = 'nonregressive') -> dict:
    if not math.isfinite(baseline_error) or baseline_error <= 0:
        raise ValueError('Progress requires positive finite baseline error')
    if not math.isfinite(current_error) or current_error < 0 or isinstance(count, bool) or not isinstance(count, int) or count < 0:
        raise ValueError('Invalid current error or qualifying-pass count')
    if policy not in ('nonregressive', 'relative'):
        raise ValueError('Unknown improvement policy')
    ratio = ERROR_RATIO if policy == 'relative' else 1.
    return {'policy': policy, 'residual_reduction_percent': 100*(1-current_error/baseline_error),
            'required_reduction_percent': 100*(1-ratio**count),
            'required_error_max': baseline_error*ratio**count,
            'qualifying_passes': count, 'is_apple_accuracy': False}


def main():
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument('previous',type=Path);ap.add_argument('candidate',type=Path)
    ap.add_argument('--evidence',type=Path,required=True,
                    help='JSON booleans visual_review, replay_verified, source_support_verified')
    ap.add_argument('--policy', choices=('nonregressive', 'relative'), default='nonregressive')
    args=ap.parse_args()
    report=evaluate(json.loads(args.previous.read_text()),json.loads(args.candidate.read_text()),
                    policy=args.policy, **json.loads(args.evidence.read_text()))
    print(json.dumps(report,indent=2,allow_nan=False))
    raise SystemExit(0 if report['accepted'] else 1)


if __name__=='__main__':main()
