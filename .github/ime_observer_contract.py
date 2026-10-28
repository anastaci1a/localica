"""One exact native-observer class; diagnostic only, never timing/optical/release equivalence."""
import hashlib,json,os,sys
from pathlib import Path
BASE = '77314e80885f4649d58482991ac3e2f9898b7d8a'
BASE_PARENT = 'b41eb52caaebba70b434c5e88749293eec3ecdc9'
BASE_TREE = '83c3655bf4a1700bb60d7c75b37d2b33ad63f4b1'
TREE = '7ecf5a43161a8c2ef40a5dc7bf91bc569768d728'
SOURCE = 'app/src/androidTest/java/io/github/anastaci1a/localica/SearchImeMotionTest.kt'
FILES = ['.github/workflows/hosted-ime-observer.yml', '.github/run-ime-observer.sh', '.github/reconcile-ime-observer.py', '.github/ime_observer_contract.py', '.github/ime_environment.py', '.github/native_witness.py', '.github/test-ime-native-witness.py']
INVENTORIES = {'observer': {'classes': [{'class': 'io.github.anastaci1a.localica.SearchImeMotionTest',
                           'declarations': [{'id': 'io.github.anastaci1a.localica.SearchImeMotionTest#independentOpeningAndEmptyNativeBackHaveOneDirectReturn',
                                             'line': 480},
                                            {'id': 'io.github.anastaci1a.localica.SearchImeMotionTest#outsidePointerDismissalDoesNotFollowTheKeyboardDown',
                                             'line': 481},
                                            {'id': 'io.github.anastaci1a.localica.SearchImeMotionTest#reducedMotionShapesImmediatelyThenAnchorsAndSnapsDirectlyHome',
                                             'line': 482},
                                            {'id': 'io.github.anastaci1a.localica.SearchImeMotionTest#absentKeyboardOpeningCanBeCancelledAndReopenedWithoutStaleReadiness',
                                             'line': 484},
                                            {'id': 'io.github.anastaci1a.localica.SearchImeMotionTest#disposingWhileKeyboardPreparationIsPendingRestoresThePill',
                                             'line': 516},
                                            {'id': 'io.github.anastaci1a.localica.SearchImeMotionTest#shapeStartsBeforeKeyboardAndNativeArrivalDoesNotWaitForShapeCompletion',
                                             'line': 528},
                                            {'id': 'io.github.anastaci1a.localica.SearchImeMotionTest#tallContentKeepsItsTargetBottomAboveTheArrivingNativeKeyboard',
                                             'line': 562},
                                            {'id': 'io.github.anastaci1a.localica.SearchImeMotionTest#completedShapeCanResizeBeforeDelayedKeyboardPlacementWithoutAStaleEndpoint',
                                             'line': 590},
                                            {'id': 'io.github.anastaci1a.localica.SearchImeMotionTest#lateNativeImeCannotSnapAtTheOpeningToPlacementHandoff',
                                             'line': 614},
                                            {'id': 'io.github.anastaci1a.localica.SearchImeMotionTest#nonemptyNativeBackPreservesTheQueryAndEasesItsRetainedPanel',
                                             'line': 646}],
                           'methods': ['io.github.anastaci1a.localica.SearchImeMotionTest#independentOpeningAndEmptyNativeBackHaveOneDirectReturn',
                                       'io.github.anastaci1a.localica.SearchImeMotionTest#outsidePointerDismissalDoesNotFollowTheKeyboardDown',
                                       'io.github.anastaci1a.localica.SearchImeMotionTest#reducedMotionShapesImmediatelyThenAnchorsAndSnapsDirectlyHome',
                                       'io.github.anastaci1a.localica.SearchImeMotionTest#absentKeyboardOpeningCanBeCancelledAndReopenedWithoutStaleReadiness',
                                       'io.github.anastaci1a.localica.SearchImeMotionTest#disposingWhileKeyboardPreparationIsPendingRestoresThePill',
                                       'io.github.anastaci1a.localica.SearchImeMotionTest#shapeStartsBeforeKeyboardAndNativeArrivalDoesNotWaitForShapeCompletion',
                                       'io.github.anastaci1a.localica.SearchImeMotionTest#tallContentKeepsItsTargetBottomAboveTheArrivingNativeKeyboard',
                                       'io.github.anastaci1a.localica.SearchImeMotionTest#completedShapeCanResizeBeforeDelayedKeyboardPlacementWithoutAStaleEndpoint',
                                       'io.github.anastaci1a.localica.SearchImeMotionTest#lateNativeImeCannotSnapAtTheOpeningToPlacementHandoff',
                                       'io.github.anastaci1a.localica.SearchImeMotionTest#nonemptyNativeBackPreservesTheQueryAndEasesItsRetainedPanel'],
                           'sha256': '316d7016aa356182f15796185fbf5692219944759d69785cb5a26899707b3126',
                           'source': 'app/src/androidTest/java/io/github/anastaci1a/localica/SearchImeMotionTest.kt'}],
              'limits': 'Static direct JUnit4 declarations only. Runtime missing/extra/duplicate IDs must fail closed; '
                        'inherited/generated/parameterized/SDK-filter discovery is not inferred.',
              'method_count': 10,
              'methods': ['io.github.anastaci1a.localica.SearchImeMotionTest#absentKeyboardOpeningCanBeCancelledAndReopenedWithoutStaleReadiness',
                          'io.github.anastaci1a.localica.SearchImeMotionTest#completedShapeCanResizeBeforeDelayedKeyboardPlacementWithoutAStaleEndpoint',
                          'io.github.anastaci1a.localica.SearchImeMotionTest#disposingWhileKeyboardPreparationIsPendingRestoresThePill',
                          'io.github.anastaci1a.localica.SearchImeMotionTest#independentOpeningAndEmptyNativeBackHaveOneDirectReturn',
                          'io.github.anastaci1a.localica.SearchImeMotionTest#lateNativeImeCannotSnapAtTheOpeningToPlacementHandoff',
                          'io.github.anastaci1a.localica.SearchImeMotionTest#nonemptyNativeBackPreservesTheQueryAndEasesItsRetainedPanel',
                          'io.github.anastaci1a.localica.SearchImeMotionTest#outsidePointerDismissalDoesNotFollowTheKeyboardDown',
                          'io.github.anastaci1a.localica.SearchImeMotionTest#reducedMotionShapesImmediatelyThenAnchorsAndSnapsDirectlyHome',
                          'io.github.anastaci1a.localica.SearchImeMotionTest#shapeStartsBeforeKeyboardAndNativeArrivalDoesNotWaitForShapeCompletion',
                          'io.github.anastaci1a.localica.SearchImeMotionTest#tallContentKeepsItsTargetBottomAboveTheArrivingNativeKeyboard'],
              'runtime_discovery_proven': False,
              'runtime_methods': ['io.github.anastaci1a.localica.SearchImeMotionTest#absentKeyboardOpeningCanBeCancelledAndReopenedWithoutStaleReadiness',
                                  'io.github.anastaci1a.localica.SearchImeMotionTest#completedShapeCanResizeBeforeDelayedKeyboardPlacementWithoutAStaleEndpoint',
                                  'io.github.anastaci1a.localica.SearchImeMotionTest#disposingWhileKeyboardPreparationIsPendingRestoresThePill',
                                  'io.github.anastaci1a.localica.SearchImeMotionTest#independentOpeningAndEmptyNativeBackHaveOneDirectReturn',
                                  'io.github.anastaci1a.localica.SearchImeMotionTest#lateNativeImeCannotSnapAtTheOpeningToPlacementHandoff',
                                  'io.github.anastaci1a.localica.SearchImeMotionTest#nonemptyNativeBackPreservesTheQueryAndEasesItsRetainedPanel',
                                  'io.github.anastaci1a.localica.SearchImeMotionTest#outsidePointerDismissalDoesNotFollowTheKeyboardDown',
                                  'io.github.anastaci1a.localica.SearchImeMotionTest#reducedMotionShapesImmediatelyThenAnchorsAndSnapsDirectlyHome',
                                  'io.github.anastaci1a.localica.SearchImeMotionTest#shapeStartsBeforeKeyboardAndNativeArrivalDoesNotWaitForShapeCompletion',
                                  'io.github.anastaci1a.localica.SearchImeMotionTest#tallContentKeepsItsTargetBottomAboveTheArrivingNativeKeyboard'],
              'schema_version': 1,
              'selectors': ['io.github.anastaci1a.localica.SearchImeMotionTest']}}
CASES = {'observer-class': {'arm': 'native-observer',
                    'controls': None,
                    'expected': ['io.github.anastaci1a.localica.SearchImeMotionTest#absentKeyboardOpeningCanBeCancelledAndReopenedWithoutStaleReadiness',
                                 'io.github.anastaci1a.localica.SearchImeMotionTest#completedShapeCanResizeBeforeDelayedKeyboardPlacementWithoutAStaleEndpoint',
                                 'io.github.anastaci1a.localica.SearchImeMotionTest#disposingWhileKeyboardPreparationIsPendingRestoresThePill',
                                 'io.github.anastaci1a.localica.SearchImeMotionTest#independentOpeningAndEmptyNativeBackHaveOneDirectReturn',
                                 'io.github.anastaci1a.localica.SearchImeMotionTest#lateNativeImeCannotSnapAtTheOpeningToPlacementHandoff',
                                 'io.github.anastaci1a.localica.SearchImeMotionTest#nonemptyNativeBackPreservesTheQueryAndEasesItsRetainedPanel',
                                 'io.github.anastaci1a.localica.SearchImeMotionTest#outsidePointerDismissalDoesNotFollowTheKeyboardDown',
                                 'io.github.anastaci1a.localica.SearchImeMotionTest#reducedMotionShapesImmediatelyThenAnchorsAndSnapsDirectlyHome',
                                 'io.github.anastaci1a.localica.SearchImeMotionTest#shapeStartsBeforeKeyboardAndNativeArrivalDoesNotWaitForShapeCompletion',
                                 'io.github.anastaci1a.localica.SearchImeMotionTest#tallContentKeepsItsTargetBottomAboveTheArrivingNativeKeyboard'],
                    'parent': '77314e80885f4649d58482991ac3e2f9898b7d8a',
                    'scope': 'class',
                    'selectors': ['io.github.anastaci1a.localica.SearchImeMotionTest'],
                    'sha': '51a8c85950ddfcd82d358a71832686a9809e81c7',
                    'tree': '7ecf5a43161a8c2ef40a5dc7bf91bc569768d728'}}
def control_args(case):
    v=CASES[case]['controls']
    return [] if v is None else ['-Pandroid.testInstrumentationRunnerArguments.'+k+'='+str(x).lower() for k,x in zip(('searchImeRetirementDiagnostics','searchImeNativeTrace','searchImeNativeWarmup','searchImeInputObservation'),v)]
def command(case):
    return ['./gradlew','connectedDebugAndroidTest','--no-daemon','--max-workers=2','-Pandroid.testInstrumentationRunnerArguments.class='+','.join(CASES[case]['selectors']),'-Pandroid.testInstrumentationRunnerArguments.additionalTestOutputDir=/sdcard/Android/media/io.github.anastaci1a.localica.debug/additional_test_output','-Pandroid.enableAdditionalTestOutput=true','-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true',*control_args(case)]
def expected_inventory(case):return INVENTORIES['observer']
def prepare(root,case,source,api,selector,tooling,invocation):
    c=CASES[case]
    if source!=c['sha'] or api!='36' or selector!=','.join(c['selectors']):raise ValueError('Wrong fixed case')
    inventory=json.loads((Path(root)/'expected-methods.json').read_text())
    if inventory!=expected_inventory(case):raise ValueError('Pinned exact-byte source inventory differs')
    argv=invocation[invocation.index('--')+1:]
    if argv!=command(case):raise ValueError('Wrong command/control arguments')
    paths=['.github/test-evidence/'+p for p in ('observe.py','supervise.py','publish.mjs','source_methods.py','environment.sh','package.json','package-lock.json')]+['.github/prepare-device-tests.sh']
    context=dict(schema_version=1,case=case,arm=c['arm'],scope=c['scope'],source_sha=source,source_parent=c['parent'],source_tree=c['tree'],baseline_sha=BASE,baseline_parent=BASE_PARENT,baseline_tree=BASE_TREE,source_changes=[SOURCE],controls=c['controls'],workflow_sha=os.environ['GITHUB_SHA'],tooling_sha='1e31403cb042e9896686200a4800faffce9b2a59',api=api,run_id=os.environ['GITHUB_RUN_ID'],attempt=os.environ['GITHUB_RUN_ATTEMPT'],selectors=c['selectors'],expected_methods=c['expected'],argv=argv,supervisor_argv=invocation,helper_sha256={p:hashlib.sha256((Path(tooling)/p).read_bytes()).hexdigest() for p in paths},harness_sha256={p:hashlib.sha256((Path(os.environ['GITHUB_WORKSPACE'])/'harness'/p).read_bytes()).hexdigest() for p in FILES},release_certified=False,full_suite_run=False,compatibility_run=False,optical_acceptance=False,causal_claim=False,timing_equivalent=False)
    with (Path(root)/'context.json').open('x') as f:json.dump(context,f,indent=2);f.write('\n')
if __name__=='__main__':prepare(*sys.argv[1:7],sys.argv[7:])
