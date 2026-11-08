package io.github.anastaci1a.localica.lint

import com.android.tools.lint.checks.infrastructure.LintDetectorTest
import com.android.tools.lint.checks.infrastructure.TestFiles.kotlin
import com.android.tools.lint.checks.infrastructure.TestMode
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Issue

/**
 * PROPOSED regressions for the MINIMAL derived() correction (agent-19 review, 2026-11-08): a resolved Unit/void call,
 * or a reference to a void method, never taints the caller's value; non-void bodies keep the existing full traversal.
 * Cases A-C are the RepeatOptionsDialog shape (five findings at 812195e1); D-H are the intended positives agent-19 named.
 */
class UnitCallTaintTest : LintDetectorTest() {
    override fun getDetector(): Detector = UiPolicyDetector()
    override fun getIssues(): List<Issue> = LocalicaIssueRegistry().issues

    private fun run(code: String) = lint().files(*(UiPolicyDetectorTest.stubs +
        kotlin("src/test/Subject.kt", "package io.github.anastaci1a.localica.ui.components\n$code").indented()).toTypedArray())
        .issues(UiRuleId.RawMotionMap.issue).allowMissingSdk().testModes(TestMode.DEFAULT).run()

    private val common = """
        import androidx.compose.runtime.*
        import io.github.anastaci1a.localica.ui.engine.*
        class Highlight(private val presentation: MotionValue) { fun value(): Float = presentation.value() }
        @Composable fun rememberHighlight(view: MotionValue): Highlight = remember { Highlight(view) }
        fun semantics(block: () -> Unit) {}
        class Placeable(val height: Int)
        fun subcompose(content: () -> Unit): Placeable = Placeable(0)
    """

    /** A: the Repeat shape. The read sits in a never-invoked lambda of a Unit composable; the compared value is a measurement. */
    fun testMeasurementOfFrameWhoseRowReadsMotionInSemanticsLambdaIsClean() {
        run(common + """
            @Composable fun Entry(view: MotionValue, content: () -> Unit) { val alpha = rememberHighlight(view); semantics { alpha.value() }; content() }
            @Composable fun Frame(view: MotionValue) { Entry(view) { } }
            @Composable fun Dialog(view: MotionValue, maxHeight: Int): Boolean { val natural = subcompose { Frame(view) }; return natural.height <= maxHeight }
        """).expectClean()
    }

    /** B: identical shape without the read stays clean (control). */
    fun testSameMeasurementWithoutMotionReadIsClean() {
        run(common + """
            @Composable fun Entry(view: MotionValue, content: () -> Unit) { val alpha = rememberHighlight(view); semantics { alpha.hashCode() }; content() }
            @Composable fun Frame(view: MotionValue) { Entry(view) { } }
            @Composable fun Dialog(view: MotionValue, maxHeight: Int): Boolean { val natural = subcompose { Frame(view) }; return natural.height <= maxHeight }
        """).expectClean()
    }

    /** C: a Unit statement read inside a called Unit body does not taint an unrelated measurement. */
    fun testUnitStatementReadInCalledUnitBodyDoesNotTaintMeasurement() {
        run(common + """
            fun log(value: Float) {}
            @Composable fun Frame(view: MotionValue) { log(view.value()) }
            @Composable fun Dialog(view: MotionValue, maxHeight: Int): Boolean { val natural = subcompose { Frame(view) }; return natural.height <= maxHeight }
        """).expectClean()
    }

    /** D: value flow through a helper's expression body is still flagged. */
    fun testReturnedMotionValueThroughHelperIsFlagged() {
        run(common + """
            fun height(view: MotionValue): Float = view.value()
            fun bad(view: MotionValue, max: Float): Boolean = height(view) <= max
        """).expectErrorCount(1)
    }

    /** E (agent-19): a motion-dependent value returned through control flow keeps the taint (the inner compare is its own finding). */
    fun testControlFlowReturnKeepsTaint() {
        run(common + """
            fun level(view: MotionValue): Float { if (view.value() > 0f) return 1f; return 0f }
            fun bad(view: MotionValue, max: Float): Boolean = level(view) <= max
        """).expectErrorCount(2)
    }

    /** F (agent-19): a non-local return from an inline lambda targeting the helper keeps the taint. */
    fun testNonLocalReturnKeepsTaint() {
        run(common + """
            fun level(view: MotionValue): Float { run { return view.value() } }
            fun bad(view: MotionValue, max: Float): Boolean = level(view) <= max
        """).expectErrorCount(1)
    }

    /** G (agent-19): a side-effect assignment inside a lambda is tracked through the assignments map. */
    fun testAssignmentSideEffectKeepsTaint() {
        run(common + """
            fun bad(view: MotionValue, max: Float): Boolean { var level = 0f; run { level = view.value() }; return level > max }
        """).expectErrorCount(1)
    }

    /** H (agent-19): a returned accessor (property getter) keeps the taint. */
    fun testReturnedAccessorKeepsTaint() {
        run(common + """
            class Face(private val view: MotionValue) { val level: Float get() = view.value() }
            fun bad(face: Face, max: Float): Boolean = face.level > max
        """).expectErrorCount(1)
    }
}
