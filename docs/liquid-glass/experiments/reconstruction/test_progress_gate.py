"""Acceptance tests for genuine improvement, source continuity and paired gates."""
import copy
import math
import unittest
from progress_gate import evaluate, error, progress


class GateTests(unittest.TestCase):
    def setUp(self):
        self.before={'epoch':'frozen-inputs-and-protocol','observations':{
            'A':{'glass':10.,'rim':20.,'shadow':3.},
            'B':{'glass':20.,'rim':10.,'foreground':4.}}}
        self.after=copy.deepcopy(self.before)
        for scores in self.after['observations'].values():
            for key in scores:scores[key]*=.99
        self.evidence=dict(visual_review=True,replay_verified=True,source_support_verified=True)

    def gate(self):return evaluate(self.before,self.after,**self.evidence)

    def test_weighted_rms_not_mean_of_rms(self):
        self.assertAlmostEqual(error({'glass':10,'rim':20}),math.sqrt(190))

    def test_all_backgrounds_improve(self):self.assertTrue(self.gate()['accepted'])

    def test_one_tie_rejects(self):
        self.after['observations']['B']=self.before['observations']['B']
        self.assertFalse(self.gate()['accepted'])

    def test_too_small_gain_rejects(self):
        self.after['observations']['A']={'glass':9.999,'rim':19.999,'shadow':3}
        self.assertFalse(self.gate()['accepted'])

    def test_lower_total_does_not_excuse_glass_regression(self):
        self.after['observations']['A']={'glass':10.1,'rim':15,'shadow':3}
        self.assertFalse(self.gate()['accepted'])

    def test_shadow_and_foreground_guard(self):
        for name,key in [('A','shadow'),('B','foreground')]:
            with self.subTest(key=key):
                old=self.after['observations'][name][key]
                self.after['observations'][name][key]=10
                self.assertFalse(self.gate()['accepted'])
                self.after['observations'][name][key]=old

    def test_invalid_measurements_fail_closed(self):
        for value in [float('nan'),float('inf'),-1,True,'0']:
            with self.subTest(value=value):
                self.after['observations']['A']['glass']=value
                with self.assertRaises(ValueError):self.gate()

    def test_changed_epoch_and_observations_fail(self):
        self.after['epoch']='new-background'
        with self.assertRaises(ValueError):self.gate()
        self.after['epoch']=self.before['epoch'];del self.after['observations']['B']
        with self.assertRaises(ValueError):self.gate()

    def test_evidence_cannot_be_inferred_from_score(self):
        for name in self.evidence:
            with self.subTest(name=name):
                self.evidence[name]=False
                self.assertFalse(self.gate()['accepted']);self.evidence[name]=True

    def test_zero_residual_tie_is_not_improvement(self):
        for doc in (self.before,self.after):
            for score in doc['observations'].values():
                score['glass']=score['rim']=0
        self.assertFalse(self.gate()['accepted'])

    def test_milestones(self):
        self.assertAlmostEqual(progress(10,9,15)['required_reduction_percent'],7.2431,places=3)
        self.assertAlmostEqual(progress(10,9,20)['required_reduction_percent'],9.53895,places=3)
        self.assertFalse(progress(10,9,20)['is_apple_accuracy'])


if __name__=='__main__':unittest.main()
