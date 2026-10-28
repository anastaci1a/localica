"""Synthetic Python evidence-oracle regressions, NOT Kotlin or native execution."""
import hashlib
from pathlib import Path
import sys
import unittest

# Works from package tests/ and when published beside native_witness.py in .github/.
HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent))
from native_witness import validate_timeline


def record(phase, now, *, animation=None, kind=None, progress_at=None, ids=(),
           ime=0, nav=0, attached=True, focused=True, target_visible=False,
           target_ime=0, requested=100, detail=None):
    def k(v):
        if v is None: return 'null'
        if isinstance(v,bool): return str(v).lower()
        return str(v)
    detail = detail or f'animation={k(animation)} type={k(kind)}'
    values = dict(uptime=now,virtual=200,requested=requested,progressAt=progress_at,
        attached=attached,focused=focused,ime=ime,navigation=nav,
        targetVisible=target_visible,targetIme=target_ime)
    body = ', '.join(f'{key}={k(value)}' for key,value in values.items())
    return f'{now} nativeReturn phase={phase} {detail} NativeReturnEvidence({body}, activeHideIds={list(ids)})'


def fixture_lines(progress='.5', opening='false'):
    return [record('dismissal-armed',100),
        record('prepare',101,animation=7,kind=8),
        record('start',102,animation=7,kind=8),
        record('progress',103,progress_at=103,ids=(7,),ime=400,nav=84),
        f'104 SearchMorphSample(progress={progress}, opening={opening}, bounds=Rect.fromLTRB(1.0, 2.0, 3.0, 4.0))',
        record('placed',104,progress_at=103,ids=(7,),ime=400,nav=84,
            detail=f'sample=0 progress={progress} opening={opening}'),
        record('end',105,animation=7,kind=8,ime=400,nav=84),
        record('detach',106,attached=False,requested=None)]


def fixture_timeline():
    """Entirely synthetic positive fixture; never label it captured device evidence."""
    return '\n'.join(fixture_lines()).encode()


class NativeWitnessOracle(unittest.TestCase):
    def rejects(self, lines):
        with self.assertRaises(ValueError): validate_timeline('\n'.join(lines).encode())
    def test_positive_and_preserves_raw_hash(self):
        raw=fixture_timeline(); result=validate_timeline(raw)
        self.assertEqual(result['raw_sha256'],hashlib.sha256(raw).hexdigest())
        self.assertEqual(result['witnesses'][0]['animation_ids'],[7])
        self.assertEqual(result['witnesses'][0]['sample_index'],0)
        self.assertFalse(result['causal_proof']); self.assertFalse(result['optical_acceptance'])
        self.assertIn('no independently logged native window token',result['generation_basis'])
    def test_endpoints_rejected(self):
        for p in ('0','.009','.99','1','NaN','Infinity'):
            with self.subTest(p=p): self.rejects(fixture_lines(progress=p))
    def test_lower_boundary_positive(self):
        self.assertTrue(validate_timeline('\n'.join(fixture_lines(progress='.01')).encode())['witnesses'])
    def test_opening_not_return(self): self.rejects(fixture_lines(opening='true'))
    def test_navigation_only(self):
        self.rejects([l.replace('ime=400','ime=84') for l in fixture_lines()])
    def test_zero_occlusion(self):
        self.rejects([l.replace('ime=400','ime=0') for l in fixture_lines()])
    def test_unknown_hidden_target(self):
        for v in ('true','null'):
            self.rejects([l.replace('targetVisible=false','targetVisible='+v) for l in fixture_lines()])
    def test_nonzero_unknown_target(self):
        for v in ('600','null'):
            self.rejects([l.replace('targetIme=0','targetIme='+v) for l in fixture_lines()])
    def test_missing_prepare(self): self.rejects(fixture_lines()[:1]+fixture_lines()[2:])
    def test_missing_start(self): self.rejects(fixture_lines()[:2]+fixture_lines()[3:])
    def test_nonime_animation(self): self.rejects([l.replace('type=8','type=1') for l in fixture_lines()])
    def test_mismatching_start_identity(self):
        lines=fixture_lines(); lines[2]=lines[2].replace('animation=7','animation=9'); self.rejects(lines)
    def test_wrong_progress_identity(self):
        self.rejects([l.replace('activeHideIds=[7]','activeHideIds=[9]') for l in fixture_lines()])
    def test_missing_progress(self): self.rejects(fixture_lines()[:3]+fixture_lines()[4:])
    def test_borrowed_positive_inset(self):
        lines=fixture_lines(); lines[3]=lines[3].replace('ime=400','ime=0'); self.rejects(lines)
    def test_unfocused_placed(self):
        lines=fixture_lines(); lines[5]=lines[5].replace('focused=true','focused=false'); self.rejects(lines)
    def test_detached_placed(self):
        lines=fixture_lines(); lines[5]=lines[5].replace('attached=true','attached=false'); self.rejects(lines)
    def test_focus_loss_invalidates_old_animation(self):
        lines=fixture_lines(); lines.insert(4,record('focus=false',103,focused=False)); self.rejects(lines)
    def test_detach_invalidates_old_animation(self):
        lines=fixture_lines(); lines.insert(4,record('detach',103,attached=False)); self.rejects(lines)
    def test_end_before_placed_invalidates(self):
        lines=fixture_lines(); lines.insert(4,record('end',103,animation=7,kind=8,ime=400,nav=84)); self.rejects(lines)
    def test_second_start_cannot_borrow_previous_progress(self):
        lines=fixture_lines(); lines.insert(4,record('start',103,animation=7,kind=8,ime=400,nav=84)); self.rejects(lines)
    def test_arm_snapshot_can_follow_request(self):
        lines=fixture_lines(); lines[0]=record('dismissal-armed',101)
        self.assertTrue(validate_timeline('\n'.join(lines).encode())['witnesses'])
    def test_future_arm_rejected(self):
        lines=fixture_lines(); lines[0]=record('dismissal-armed',100,requested=101); self.rejects(lines)
    def test_rearm_rejected(self):
        lines=fixture_lines(); lines.insert(1,record('dismissal-armed',100)); self.rejects(lines)
    def test_predismiss_progress_rejected(self):
        self.rejects([l.replace('progressAt=103','progressAt=99') for l in fixture_lines()])
    def test_future_progress_rejected(self):
        self.rejects([l.replace('progressAt=103','progressAt=104') for l in fixture_lines()])
    def test_no_matching_animation(self):
        self.rejects([l.replace('activeHideIds=[7]','activeHideIds=[]').replace('progressAt=103','progressAt=null') for l in fixture_lines()])
    def test_duplicate_ids(self):
        self.rejects([l.replace('activeHideIds=[7]','activeHideIds=[7, 7]') for l in fixture_lines()])
    def test_no_placed_sample(self): self.rejects(fixture_lines()[:4]+fixture_lines()[5:])
    def test_sample_index_mismatch(self):
        self.rejects([l.replace('sample=0','sample=1') for l in fixture_lines()])
    def test_sample_progress_mismatch(self):
        lines=fixture_lines(); lines[5]=lines[5].replace('progress=.5','progress=.4'); self.rejects(lines)
    def test_sample_direction_mismatch(self):
        lines=fixture_lines(); lines[5]=lines[5].replace('opening=false','opening=true'); self.rejects(lines)
    def test_nonadjacent_sample(self):
        lines=fixture_lines(); lines.insert(5,'104 textEditing something'); self.rejects(lines)
    def test_timestamp_mismatch(self):
        lines=fixture_lines(); lines[5]=lines[5].replace('uptime=104','uptime=105'); self.rejects(lines)
    def test_regressed_timeline(self):
        lines=fixture_lines(); lines[3]=lines[3].replace('103','101'); self.rejects(lines)
    def test_callback_corruption_after_witness_still_fails(self):
        lines=fixture_lines(); lines[6]=lines[6].replace('activeHideIds=[]','activeHideIds=[7]'); self.rejects(lines)
    def test_empty_or_untyped_input(self):
        for raw in (b'',b'original uninstrumented timeline',fixture_timeline().decode()):
            with self.assertRaises(ValueError): validate_timeline(raw)
    def test_unknown_phase(self):
        self.rejects([l.replace('phase=progress','phase=unknown') for l in fixture_lines()])
    def test_missing_field(self):
        self.rejects([l.replace('navigation=84, ','') for l in fixture_lines()])
    def test_negative_inset(self):
        self.rejects([l.replace('ime=400','ime=-1') for l in fixture_lines()])
    def test_same_window_generation_recovery_needs_fresh_prepare_start_progress(self):
        lines=fixture_lines()
        lines[1:4]=[record('prepare',101,animation=6,kind=8),record('start',101,animation=6,kind=8),
            record('focus=false',102,focused=False),record('focus=true',102),
            record('prepare',102,animation=7,kind=8),record('start',102,animation=7,kind=8),
            record('progress',103,progress_at=103,ids=(7,),ime=400,nav=84)]
        result=validate_timeline('\n'.join(lines).encode())
        self.assertEqual(result['witnesses'][0]['generation'],2)

if __name__=='__main__': unittest.main(verbosity=2)
