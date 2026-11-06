package io.github.anastaci1a.localica.lint

import com.android.tools.lint.checks.infrastructure.TestFiles.kotlin
import com.android.tools.lint.checks.infrastructure.TestFiles.java
import com.android.tools.lint.checks.infrastructure.TestFiles.source
import com.android.tools.lint.checks.infrastructure.TestLintTask
import com.android.tools.lint.checks.infrastructure.TestMode
import org.junit.Before
import org.junit.Test

class ProvenanceContractTest {
    @Before fun initializeLint() { com.android.tools.lint.client.api.LintClient.clientName = "unit-tests" }
    private fun task(rule: UiRuleId, source: String, vararg extra: com.android.tools.lint.checks.infrastructure.TestFile): TestLintTask =
        TestLintTask.lint().detector(UiPolicyDetector()).issues(rule.issue).allowMissingSdk().testModes(TestMode.DEFAULT)
            .files(*(UiPolicyDetectorTest.stubs + kotlin("src/test/Subject.kt", source).indented() + extra).toTypedArray())

    @Test fun readProvenanceCrossesFilesAssignmentsAndGetterBodies() {
        val helper = kotlin("src/test/Helper.kt", """
            package io.github.anastaci1a.localica.ui.components
            import io.github.anastaci1a.localica.ui.engine.MotionValue
            fun pixels(view: MotionValue) = view.sample().value
        """).indented()
        task(UiRuleId.RawMotionMap, """
            package io.github.anastaci1a.localica.ui.components
            import io.github.anastaci1a.localica.ui.engine.*
            fun bad(view: MotionValue): Boolean { var p = 0f; p = pixels(view); return p > .5f }
        """, helper).run().expectErrorCount(1)
        task(UiRuleId.MotionReadInComposition, """
            package io.github.anastaci1a.localica.ui.components
            import androidx.compose.runtime.Composable
            import io.github.anastaci1a.localica.ui.engine.*
            @Composable fun bad(view: MotionValue) { pixels(view) }
        """, helper).run().expectMatches("(?s).*Helper.kt:3: Error: .*MotionReadInComposition.*1 error.*")
        task(UiRuleId.MotionReadInComposition, """
            package io.github.anastaci1a.localica.ui.components
            import androidx.compose.runtime.Composable
            import io.github.anastaci1a.localica.ui.engine.*
            class Holder(val view: MotionValue) { val pixel: Float get() = view.velocityAt(FrameStamp()) }
            @Composable fun bad(holder: Holder) { val p = holder.pixel }
        """).run().expectErrorCount(1)
    }

    @Test fun invokedLambdaIsImmediateButStoredDrawCallbackIsDeferred() {
        val imports = "package io.github.anastaci1a.localica.ui.components\nimport androidx.compose.runtime.*\nimport io.github.anastaci1a.localica.ui.engine.*\n"
        task(UiRuleId.MotionReadInComposition, imports + "@Composable fun Bad(v: MotionValue) { ({ v.value() })() }").run().expectErrorCount(1)
        task(UiRuleId.MotionReadInComposition, imports + "@Composable fun Bad(v: MotionValue) { val f = { v.value() }; f() }").run().expectErrorCount(1)
        task(UiRuleId.MotionReadInComposition, imports + "@Composable fun Good(v: MotionValue) { val f = { v.value() }; drawBehind(f) }; fun drawBehind(block: () -> Unit) {}").run().expectClean()
        task(UiRuleId.MotionReadInComposition, imports.replace("ui.components", "ui.engine") + "@Composable fun Bad(v: MotionValue) { v.valueAt(FrameStamp()) }").run().expectErrorCount(1)
    }

    @Test fun foundationScrollNeedsValidatedTokenAndMotionTarget() {
        val scroll = kotlin("src/test/Scroll.kt", """
            package androidx.compose.foundation.lazy
            class LazyListState { fun animateScrollToItem(target: Float, profile: Any) {}; fun scrollBy(target: Float, profile: Any) {} }
        """).indented()
        val profile = kotlin("src/test/Profile.kt", """
            package io.github.anastaci1a.localica.ui.spec
            object MotionProfile { object Scroll { val profile = Any() } }
        """).indented()
        val imports = """
            package io.github.anastaci1a.localica.ui.components
            import androidx.compose.foundation.lazy.LazyListState
            import io.github.anastaci1a.localica.ui.engine.MotionValue
            import io.github.anastaci1a.localica.ui.spec.MotionProfile
        """.trimIndent() + "\n"
        task(UiRuleId.RawUiMotion, imports + "fun good(state: LazyListState, v: MotionValue) { val token = MotionProfile.Scroll.profile; state.animateScrollToItem(v.value(), token) }", scroll, profile).run().expectClean()
        task(UiRuleId.RawUiMotion, imports + "fun bad(state: LazyListState, v: MotionValue) { state.animateScrollToItem(v.value(), androidx.compose.animation.core.tween()) }", scroll, profile).run().expectErrorCount(2)
        task(UiRuleId.RawUiMotion, imports + "fun bad(state: LazyListState) { state.scrollBy(1f, MotionProfile.Scroll.profile) }", scroll, profile).run().expectErrorCount(1)
    }

    @Test fun inheritedCapabilitiesAndIsChecksStayProtected() {
        task(UiRuleId.RawMotionMap, """
            package io.github.anastaci1a.localica.ui.components
            import io.github.anastaci1a.localica.ui.engine.*
            class Borrowed : MotionFloat()
            fun bad(v: Borrowed) { v.release() }
            fun cast(v: MotionValue) = v is MotionFloat
        """).run().expectErrorCount(3)
    }

    @Test fun javaStaticImportsAndBorrowedParametersResolve() {
        TestLintTask.lint().detector(UiPolicyDetector()).issues(UiRuleId.RawUiMotion.issue).allowMissingSdk().testModes(TestMode.DEFAULT)
            .javaLanguageLevel("17").files(java("""
                package androidx.compose.animation.core;
                public class Specs { public static int tween() { return 0; } }
            """).indented(), java("""
                package io.github.anastaci1a.localica.ui.components;
                import static androidx.compose.animation.core.Specs.tween;
                class Screen { int bad() { return tween(); } }
            """).indented()).run().expectErrorCount(1)
        TestLintTask.lint().detector(UiPolicyDetector()).issues(UiRuleId.RawMotionMap.issue).allowMissingSdk().testModes(TestMode.DEFAULT)
            .files(*(UiPolicyDetectorTest.stubs + java("src/Screen.java", """
                package io.github.anastaci1a.localica.ui.components;
                import io.github.anastaci1a.localica.ui.engine.MotionFloat;
                class Screen { void bad(MotionFloat borrowed) { borrowed.release(); } }
            """).indented()).toTypedArray()).run().expectErrorCount(2)
    }

    @Test fun borrowedEventMutationAndDeltaCoroutineCannotAcquireOwnership() {
        val code = """
            package io.github.anastaci1a.localica.ui.components
            import androidx.compose.runtime.*
            import io.github.anastaci1a.localica.ui.engine.*
            @Composable fun Bad(handle: MotionFloat, delta: Float) {
                Button(onClick = { handle.release() })
                LaunchedEffect(delta) { handle.drive() }
            }
            fun Button(onClick: () -> Unit) {}
        """.trimIndent()
        val effect = kotlin("src/test/Effect.kt", """
            package androidx.compose.runtime
            fun LaunchedEffect(key: Any, block: suspend () -> Unit) {}
        """).indented()
        task(UiRuleId.RawMotionMap, code, effect).run().expectErrorCount(3)
        task(UiRuleId.IndependentUiClock, code, effect).run().expectErrorCount(1)
    }

    @Test fun dataLeafAdmitsOnlyNamedSettingsIngress() {
        val settings = kotlin("src/test/Settings.kt", "package io.github.anastaci1a.localica.navigation\nclass InterfaceSettings")
        task(UiRuleId.UiLayerDependency, """
            package io.github.anastaci1a.localica.ui.spec
            import io.github.anastaci1a.localica.navigation.InterfaceSettings
            fun uiSpec(settings: InterfaceSettings) = settings
        """, settings).run().expectClean()
        task(UiRuleId.UiLayerDependency, """
            package io.github.anastaci1a.localica.ui.spec
            import androidx.compose.foundation.layout.Column
            fun bad() { Column {} }
        """).run().expectErrorCount(2)
    }

    @Test fun suppressionAuditCannotBeSuppressedOrForgedByStrings() {
        task(UiRuleId.UnreviewedUiEscape, """
            @file:Suppress("UnreviewedUiEscape")
            package io.github.anastaci1a.localica.ui.components
            fun bad() = 0
        """).run().expectErrorCount(1)
        task(UiRuleId.UnreviewedUiEscape, """
            package io.github.anastaci1a.localica.ui.components
            const val RULE = "Raw" + "UiMotion"
            @Suppress(RULE) fun bad() = 0
        """).run().expectErrorCount(1)
        task(UiRuleId.UnreviewedUiEscape, "package io.github.anastaci1a.localica.ui.components\nval text = \"@Suppress(\\\"all\\\")\"").run().expectClean()
    }

    @Test fun delegatedAndForwardedLambdaReadsRetainTheirExecutionPhase() {
        val imports = "package io.github.anastaci1a.localica.ui.components\nimport androidx.compose.runtime.*\nimport io.github.anastaci1a.localica.ui.engine.*\n"
        task(UiRuleId.MotionReadInComposition, imports + "@Composable fun Bad(v: MotionValue) { val pixel by lazy { v.value() }; println(pixel) }").run().expectErrorCount(1)
        val helpers = "fun relay(block: () -> Unit) { block() }; fun drawBehind(block: () -> Unit) {}\n"
        task(UiRuleId.MotionReadInComposition, imports + helpers + "@Composable fun Bad(v: MotionValue) { relay { v.value() } }").run().expectErrorCount(1)
        task(UiRuleId.MotionReadInComposition, imports + "fun relay(block: () -> Unit) { drawBehind { block() } }; fun drawBehind(block: () -> Unit) {}\n@Composable fun Good(v: MotionValue) { relay { v.value() } }").run().expectClean()
        task(UiRuleId.MotionReadInComposition, imports + helpers + "@Composable fun Bad(v: MotionValue) { relay { println(1) }; relay { v.value() } }").run().expectErrorCount(1)
        task(UiRuleId.MotionReadInComposition, imports + "@Composable fun Host(content: @Composable () -> Unit) {}\n@Composable fun Bad(v: MotionValue) { Host { v.valueAt(FrameStamp()) } }").run().expectErrorCount(1)
    }

    @Test fun exactLiveAdapterEscapeAndRejections() {
        val source = """
            package io.github.anastaci1a.localica.ui.engine.platform
            @Suppress("IndependentUiClock") fun ready() = System.nanoTime()
        """.trimIndent()
        val manifest = """
            - rule: IndependentUiClock
              symbol: io.github.anastaci1a.localica.ui.engine.platform.SubjectKt.ready:2
              reason: Native readiness adapter
              issue: https://github.com/anastaci1a/localica/issues/600
              owner: platform
              reviewer: lead
              expires: U13.03a
        """.trimIndent()
        fun verify(data: String, completed: String, errors: Int) {
            val result = task(UiRuleId.UnreviewedUiEscape, source, source("ui-escapes.yaml", data))
                .configureOption("knownSteps", "U13.01,U13.03a")
                .configureOption("completedSteps", completed).run()
            if (errors == 0) result.expectClean() else result.expectErrorCount(errors)
        }
        verify(manifest, "U13.01", 0)
        verify(manifest, "U13.01,U13.03a", 1)
        verify(manifest.replace("SubjectKt.ready:2", "SubjectKt.wrong:2"), "U13.01", 1)
        verify(manifest.replace("U13.03a", "U99.99"), "U13.01", 1)
        verify(manifest + "\n" + manifest, "U13.01", 1)
    }
}
