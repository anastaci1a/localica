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

    @Test fun onlyNamedSpecProviderAndGlassIngressAreAllowed() {
        val runtime = kotlin("src/test/CompositionLocal.kt", """
            package androidx.compose.runtime
            class ProvidableCompositionLocal<T>
            fun <T> staticCompositionLocalOf(defaultFactory: () -> T) = ProvidableCompositionLocal<T>()
        """).indented()
        val glass = kotlin("src/test/Glass.kt", """
            package io.github.anastaci1a.localica.navigation
            class GlassAppearance { val blur = .2f; fun normalized() = this }
        """).indented()
        val imports = "package io.github.anastaci1a.localica.ui.spec\nimport androidx.compose.runtime.staticCompositionLocalOf\n"
        task(UiRuleId.UiLayerDependency, imports + "val LocalUiSpec = staticCompositionLocalOf { ResolvedUiSpec() }", runtime).run().expectClean()
        task(UiRuleId.UiLayerDependency, imports + "val OtherProvider = staticCompositionLocalOf { ResolvedUiSpec() }", runtime).run().expectErrorCount(2)
        task(UiRuleId.UiLayerDependency, imports + "val LocalUiSpec = staticCompositionLocalOf { ResolvedUiSpec() }; val OtherProvider = staticCompositionLocalOf { ResolvedUiSpec() }", runtime).run().expectErrorCount(1)
        val navigation = "io.github.anastaci1a.localica.navigation.GlassAppearance"
        task(UiRuleId.UiLayerDependency, "package io.github.anastaci1a.localica.ui.spec\nfun uiSpec() = $navigation().normalized().blur", glass).run().expectClean()
        task(UiRuleId.UiLayerDependency, "package io.github.anastaci1a.localica.ui.spec\nfun bad() = $navigation().normalized().blur", glass).run().expectErrorCount(3)
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

    @Test fun mainActivityUsesTheScreenPolicyByDeclaration() {
        task(UiRuleId.NonPresetScreenLayout, """
            package io.github.anastaci1a.localica
            import androidx.compose.foundation.layout.Column
            class MainActivity { fun bad() { Column {} } }
        """).run().expectErrorCount(1)
        task(UiRuleId.UiLayerDependency, """
            package io.github.anastaci1a.localica
            class MainActivity { fun bad(v: io.github.anastaci1a.localica.ui.engine.MotionValue) = v }
        """).run().expectErrorCount(1)
        task(UiRuleId.NonPresetScreenLayout, """
            package io.github.anastaci1a.localica
            class MainActivity { fun good() { ShellLayout {} } }
            fun ShellLayout(content: () -> Unit) {}
        """).run().expectClean()
    }

    @Test fun allThirteenRulesPinTheirDiagnosticSourceLine() {
        val engine = "io.github.anastaci1a.localica.ui.engine"
        val cases = listOf(
            Triple(UiRuleId.RawUiMotion, "fun bad() = androidx.compose.animation.core.tween()", 2),
            Triple(UiRuleId.IndependentUiClock, "fun bad() = System.nanoTime()", 2),
            Triple(UiRuleId.RawPresentationTransform, "import androidx.compose.ui.Modifier\nimport androidx.compose.ui.draw.alpha\nfun bad() = Modifier.alpha(.5f)", 4),
            Triple(UiRuleId.RawShadowOrZOrder, "import androidx.compose.ui.Modifier\nimport androidx.compose.ui.draw.shadow\nfun bad() = Modifier.shadow(2f)", 4),
            Triple(UiRuleId.ReplacePersistentShape, "fun bad(s: Boolean) = androidx.compose.runtime.remember(s) { androidx.compose.foundation.shape.RoundedCornerShape(2f) }", 2),
            Triple(UiRuleId.UnownedSurfaceLifetime, "fun bad(v: Boolean) { if (v) androidx.compose.ui.window.Popup {} }", 2),
            Triple(UiRuleId.RawMotionMap, "fun bad(v: $engine.MotionValue) = v.value().coerceIn(0f, 1f)", 2),
            Triple(UiRuleId.MotionReadInComposition, "@androidx.compose.runtime.Composable fun Bad(v: $engine.MotionValue) { v.value() }", 2),
            Triple(UiRuleId.LiteralUiSpacing, "import androidx.compose.ui.unit.dp\nfun bad() = 8.dp", 3),
            Triple(UiRuleId.NonPresetScreenLayout, "fun bad() { androidx.compose.foundation.layout.Column {} }", 2),
            Triple(UiRuleId.UnvalidatedUiSpec, "fun bad() = io.github.anastaci1a.localica.ui.spec.UiSpec()", 2),
            Triple(UiRuleId.UiLayerDependency, "fun bad() = io.github.anastaci1a.localica.ui.layout.LayoutMarker()", 2),
            Triple(UiRuleId.UnreviewedUiEscape, "@Suppress(\"RawUiMotion\") fun bad() = 0", 2),
        )
        for ((rule, code, line) in cases) {
            val pkg = if (rule == UiRuleId.NonPresetScreenLayout) "io.github.anastaci1a.localica.ui" else "io.github.anastaci1a.localica.ui.components"
            task(rule, "package $pkg\n$code").run()
                .expectMatches("(?s).*Subject.kt:$line: Error: .*\\[" + rule.name + "(?: from [^]]+)?].*1 error.*")
                .expectErrorCount(1)
        }
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
    @Test fun onlyNamedThemeRootMayProvideUiClock() {
        val provider=kotlin("src/engine/UiClock.kt", """
            package io.github.anastaci1a.localica.ui.engine
            fun ProvideUiClock(content: () -> Unit) { content() }
        """).indented()
        fun run(path: String, method: String)=TestLintTask.lint().detector(UiPolicyDetector()).issues(UiRuleId.UiLayerDependency.issue)
            .allowMissingSdk().testModes(TestMode.DEFAULT).files(*(UiPolicyDetectorTest.stubs+provider+kotlin(path,"""
                package io.github.anastaci1a.localica.ui
                import io.github.anastaci1a.localica.ui.engine.ProvideUiClock
                fun $method() { ProvideUiClock {} }
            """).indented()).toTypedArray()).run()
        run("src/Theme.kt","LocalicaTheme").expectClean()
        run("src/Other.kt","LocalicaTheme").expectErrorCount(2)
        run("src/Theme.kt","OtherTheme").expectErrorCount(1)
    }

    @Test fun constructorsAndPreparedChannelHooksStayInsideEngine() {
        val source="fun bad() = io.github.anastaci1a.localica.ui.engine.MotionFloat()"
        task(UiRuleId.RawUiMotion,"package io.github.anastaci1a.localica.ui.components\n$source").run().expectErrorCount(1)
        task(UiRuleId.RawUiMotion,"package io.github.anastaci1a.localica.ui.engine\n$source").run().expectClean()
    }

    @Test fun expansionConstructionAndLifecycleHooksStayEngineOwned() {
        val pkg="io.github.anastaci1a.localica.ui"
        task(UiRuleId.RawUiMotion,"package $pkg.components\nfun bad(v: $pkg.engine.MotionFloat) { v.onRest = {} }").run().expectErrorCount(1)
        task(UiRuleId.RawUiMotion,"package $pkg.engine\nfun good(v: MotionFloat) { v.onRest = {} }").run().expectClean()
        task(UiRuleId.RawUiMotion,"package $pkg.components\nfun bad(v: $pkg.engine.ExpandCollapse) { v.updateSpec(1) }").run().expectErrorCount(1)
        task(UiRuleId.RawUiMotion,"package $pkg\nfun bad() = $pkg.engine.ExpandCollapse()").run().expectErrorCount(1)
    }

}
