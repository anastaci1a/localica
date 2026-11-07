package io.github.anastaci1a.localica.lint

import com.android.tools.lint.checks.infrastructure.LintDetectorTest
import com.android.tools.lint.checks.infrastructure.TestFile
import com.android.tools.lint.checks.infrastructure.TestFiles
import com.android.tools.lint.checks.infrastructure.TestFiles.kotlin
import com.android.tools.lint.checks.infrastructure.TestMode
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Issue

class UiPolicyDetectorTest : LintDetectorTest() {
    override fun getDetector(): Detector = UiPolicyDetector()
    override fun getIssues(): List<Issue> = LocalicaIssueRegistry().issues

    private fun check(rule: UiRuleId, code: String, count: Int, layer: String = "components", vararg extra: String) {
        val files = stubs + kotlin("src/test/Subject.kt", "package io.github.anastaci1a.localica.ui.$layer\n$code").indented() +
            extra.mapIndexed { index, source -> kotlin("src/test/Helper$index.kt", source).indented() }
        val result = lint().files(*files.toTypedArray()).issues(rule.issue).allowMissingSdk().testModes(TestMode.DEFAULT).run()
        if (count == 0) result.expectClean() else {
            result.expectMatches("(?s).*src/test/Subject.kt:[0-9]+: Error: .*\\[${rule.name}(?: from [^]]+)?].*\\n$count errors?.*")
            result.expectErrorCount(count)
        }
    }

    fun testRawUiMotionAliasWrapperAndEngine() {
        val body = """
            import androidx.compose.animation.core.tween as custom
            fun wrapper() = custom()
        """
        check(UiRuleId.RawUiMotion, body, 1)
        check(UiRuleId.RawUiMotion, body, 0, "engine")
        check(UiRuleId.RawUiMotion, "fun f() = androidx.compose.animation.core.tween()", 1)
        check(UiRuleId.RawUiMotion, "import androidx.compose.animation.core.*\nfun f() = spring()", 1)
        check(UiRuleId.RawUiMotion, "class Unrelated { fun tween() = 0 }; fun f() = Unrelated().tween()", 0)
    }

    fun testClocksAndReadiness() {
        val bad = """
            import androidx.compose.runtime.*
            suspend fun popup() { withFrameNanos { }; withFrameMillis { }; System.nanoTime() }
        """
        check(UiRuleId.IndependentUiClock, bad, 3)
        check(UiRuleId.IndependentUiClock, bad, 0, "engine.platform")
        check(UiRuleId.IndependentUiClock, "fun logical(ready: Boolean) = ready", 0)
    }

    fun testPresentationBindingsAndGraphicsWithoutTransforms() {
        val body = """
            import androidx.compose.ui.*
            import androidx.compose.ui.graphics.*
            fun m() = Modifier.graphicsLayer { alpha = .5f; scaleX = .8f }
        """
        // The layer call and both assignments are independently anchored.
        check(UiRuleId.RawPresentationTransform, body, 3)
        check(UiRuleId.RawPresentationTransform, body, 0, "engine")
        check(UiRuleId.RawPresentationTransform, """
            import androidx.compose.ui.*
            import androidx.compose.ui.graphics.*
            fun m() = Modifier.graphicsLayer { clip = true }
        """, 0)
        check(UiRuleId.RawPresentationTransform, """
            import androidx.compose.ui.*
            import androidx.compose.ui.draw.alpha as fade
            fun m() = Modifier.fade(.5f)
        """, 1)
    }

    fun testShadowAndOrdering() {
        val body = """
            import androidx.compose.ui.*
            import androidx.compose.ui.draw.*
            fun m() = Modifier.zIndex(4f).shadow(8f)
        """
        check(UiRuleId.RawShadowOrZOrder, body, 2)
        check(UiRuleId.RawShadowOrZOrder, body, 0, "engine")
        check(UiRuleId.RawShadowOrZOrder, """
            import io.github.anastaci1a.localica.ui.engine.SurfaceTarget
            fun intent() = SurfaceTarget()
        """, 0)
    }

    fun testStateKeyedShapeAndPlotPath() {
        val body = """
            import androidx.compose.runtime.*
            import androidx.compose.foundation.shape.*
            fun outline(selected: Boolean) = remember(selected) { RoundedCornerShape(3f) }
        """
        check(UiRuleId.ReplacePersistentShape, body, 1)
        check(UiRuleId.ReplacePersistentShape, body, 0, "engine")
        check(UiRuleId.ReplacePersistentShape, """
            import androidx.compose.ui.graphics.Path
            fun plot(selected: Boolean) { if (selected) { val plot = Path() } }
        """, 0)
        check(UiRuleId.ReplacePersistentShape, """
            import androidx.compose.runtime.*
            import io.github.anastaci1a.localica.ui.engine.*
            @Composable fun outline() { val shape = rememberPersistentShape(); shape.retarget() }
        """, 0)
    }

    fun testLifetimeVersusContentReadiness() {
        val body = """
            import androidx.compose.ui.window.Popup
            fun host(visible: Boolean) { if (visible) Popup { } }
        """
        check(UiRuleId.UnownedSurfaceLifetime, body, 1)
        check(UiRuleId.UnownedSurfaceLifetime, """
            import androidx.compose.ui.window.Popup
            fun retained(ready: Boolean) { Popup { if (ready) println("content") } }
        """, 0)
        check(UiRuleId.UnownedSurfaceLifetime, """
            import androidx.compose.ui.graphics.*
            fun bad() = rememberGraphicsLayer()
        """, 1)
    }

    fun testEveryProtectedMutatorAndMutableParameters() {
        val methods = mapOf(
            "MotionFloat" to listOf("retarget", "retargetSpring", "drive", "release", "initialize"),
            "MotionRect" to listOf("retarget", "retargetSpring", "drive", "release", "initialize"),
            "MotionColor" to listOf("retarget", "retargetSpring", "drive", "release", "initialize"),
            "ExpandCollapse" to listOf("target", "contactBegin", "contactMove", "contactRelease", "contactCancel", "setBasis", "bind"),
            "PersistentShape" to listOf("retarget", "drive"),
            "SurfaceNode" to listOf("request", "transfer", "dismiss"),
        )
        for ((type, names) in methods) {
            val calls = names.joinToString("; ") { "alias.$it()" }
            check(UiRuleId.RawMotionMap, """
                import io.github.anastaci1a.localica.ui.engine.*
                fun borrowed(handle: $type) { val alias = handle; $calls }
            """, names.size + 1)
            check(UiRuleId.RawMotionMap, """
                import androidx.compose.runtime.*
                import io.github.anastaci1a.localica.ui.engine.*
                @Composable fun owner() { val handle = remember$type(); val alias = handle; $calls }
            """, 0)
        }
    }

    fun testOwnershipCannotBeForgedByFieldWrapperCastOrCallback() {
        check(UiRuleId.RawMotionMap, """
            import androidx.compose.runtime.*
            import io.github.anastaci1a.localica.ui.engine.*
            class Wrapper(val handle: MotionFloat)
            @Composable fun bad(w: Wrapper) { val alias = w.handle; alias.release() }
        """, 2)
        check(UiRuleId.RawMotionMap, """
            import androidx.compose.runtime.*
            import io.github.anastaci1a.localica.ui.engine.*
            @Composable fun bad() { val handle = rememberMotionFloat(); val escaped = { handle.release() }; escaped() }
        """, 1)
        check(UiRuleId.RawMotionMap, """
            import io.github.anastaci1a.localica.ui.engine.*
            fun bad(view: MotionValue) { (view as MotionFloat).drive() }
        """, 2)
        check(UiRuleId.RawMotionMap, """
            import io.github.anastaci1a.localica.ui.engine.*
            fun intent(view: ExpansionView) = view.expanded
        """, 0)
        check(UiRuleId.RawMotionMap, """
            import androidx.compose.runtime.*
            import io.github.anastaci1a.localica.ui.engine.*
            @Composable fun owner() { val handle = rememberMotionFloat(); Button(onClick = { handle.release() }) }
            fun Button(onClick: () -> Unit) {}
        """, 0)
        // U13 amendment: effects and gesture callbacks lexically inside the owner composable are owner scope.
        check(UiRuleId.RawMotionMap, """
            import androidx.compose.runtime.*
            import io.github.anastaci1a.localica.ui.engine.*
            class Intents(val onDragStart: () -> Unit, val onDrag: () -> Unit, val onDragCancel: () -> Unit)
            @Composable fun owner(): Intents { val handle = rememberMotionFloat()
                SideEffect { handle.release() }; LaunchedEffect(1) { handle.release() }; DisposableEffect(1) { handle.release() }
                return Intents(onDragStart = { handle.drive() }, onDrag = { handle.drive() }, onDragCancel = { handle.release() }) }
        """, 0)
        // Negative: the same calls from another composable, a passed-in handle, a non-owner effect or an unlisted callback.
        check(UiRuleId.RawMotionMap, """
            import androidx.compose.runtime.*
            import io.github.anastaci1a.localica.ui.engine.*
            class Intents(val onDragStart: () -> Unit, val onMove: () -> Unit)
            fun LaunchedEffect(key: Any?, block: () -> Unit) {}
            @Composable fun borrowed(handle: MotionFloat) { SideEffect { handle.release() } }
            @Composable fun other() { val handle = rememberMotionFloat(); LaunchedEffect(1) { handle.release() }; Intents(onDragStart = {}, onMove = { handle.drive() }) }
            @Composable fun stored() { val handle = rememberMotionFloat(); val later = remember { { handle.release() } }; SideEffect { later() } }
        """, 4, "screens")
    }

    fun testMotionAliasHelperChannelAndLogicalTargets() {
        check(UiRuleId.RawMotionMap, """
            import io.github.anastaci1a.localica.ui.engine.*
            fun sample(view: MotionValue) = view.sample().value
            fun bad(view: MotionValue): Boolean { val p = sample(view); val q = p; return q > .5f }
        """, 1)
        check(UiRuleId.RawMotionMap, """
            import io.github.anastaci1a.localica.ui.engine.*
            fun bad(view: MotionValue) = view.valueAt(FrameStamp()).coerceIn(0f, 1f)
        """, 1)
        check(UiRuleId.RawMotionMap, """
            import io.github.anastaci1a.localica.ui.engine.*
            fun intent(view: MotionValue) = view.target > .5f
        """, 0)
    }

    fun testSubcomposeMeasurementDoesNotInheritItsRenderedContentsMotionReads() {
        val layout = """
            package androidx.compose.ui.layout
            class Placeable(val height: Int)
            class Measurable { fun measure() = Placeable(20) }
            fun subcompose(p0: Any, p1: () -> Unit): List<Measurable> { p1(); return listOf(Measurable()) }
        """
        val renderer = """
            package io.github.anastaci1a.localica.ui.engine
            fun render(view: MotionValue) { view.value() }
        """
        check(UiRuleId.RawMotionMap, """
            import androidx.compose.ui.layout.*
            import io.github.anastaci1a.localica.ui.engine.*
            fun fit(view: MotionValue): Boolean {
                val natural = subcompose("body") { render(view) }.single().measure()
                return natural.height <= 200
            }
        """, 0, "components", layout, renderer)
        // The SDK boundary does not conceal a motion-dependent slot identity.
        check(UiRuleId.RawMotionMap, """
            import androidx.compose.ui.layout.*
            import io.github.anastaci1a.localica.ui.engine.*
            fun bad(view: MotionValue) = subcompose(view.value()) { render(view) }.single().measure().height > 0
        """, 1, "components", layout, renderer)
        // A user helper with the same name is still followed.
        check(UiRuleId.RawMotionMap, """
            import io.github.anastaci1a.localica.ui.engine.*
            fun subcompose(content: () -> Float) = content()
            fun bad(view: MotionValue) = subcompose { view.value() } > .5f
        """, 1)
    }

    fun testImmediateAndDeferredMotionReads() {
        val imports = "import androidx.compose.runtime.*\nimport io.github.anastaci1a.localica.ui.engine.*\n"
        for (read in listOf("value()", "velocity()", "sample()", "valueAt(FrameStamp())", "velocityAt(FrameStamp())", "sampleAt(FrameStamp())")) {
            check(UiRuleId.MotionReadInComposition, imports + "@Composable fun Bad(view: MotionValue) { val p = view.$read }", 1)
            check(UiRuleId.MotionReadInComposition, imports + "@Composable fun Good(view: MotionValue) { drawBehind { view.$read } }\nfun drawBehind(block: () -> Unit) {}", 0)
        }
        check(UiRuleId.MotionReadInComposition, imports + "@Composable fun Bad(view: MotionValue) { run { view.value() } }", 1)
        check(UiRuleId.MotionReadInComposition, imports + "@Composable fun Good(view: MotionValue) { val later = { view.value() } }", 0)
        check(UiRuleId.MotionReadInComposition, imports + "fun helper(v: MotionValue) = v.value()\n@Composable fun Bad(view: MotionValue) { helper(view) }", 1)
        check(UiRuleId.MotionReadInComposition, imports + "@Composable fun Bad(shape: ShapeView) { shape.outlineAt(FrameStamp()) }", 1)
    }

    fun testSpacingAliasesAndValidatedSpecData() {
        val body = """
            import androidx.compose.ui.unit.*
            const val GAP = 8
            fun spacing() = GAP.dp
        """
        check(UiRuleId.LiteralUiSpacing, body, 1)
        check(UiRuleId.LiteralUiSpacing, body, 0, "spec")
        check(UiRuleId.LiteralUiSpacing, """
            import io.github.anastaci1a.localica.ui.spec.Tokens
            fun spacing() = Tokens.gap
        """, 0)
    }

    fun testScreenShellAliasHelperAndPreset() {
        val body = """
            import androidx.compose.foundation.layout.Column as Stack
            fun shell() { Stack {} }
        """
        check(UiRuleId.NonPresetScreenLayout, body, 1, "screens")
        check(UiRuleId.NonPresetScreenLayout, body, 0, "components")
        check(UiRuleId.NonPresetScreenLayout, "fun shell() { ShellLayout {} }; fun ShellLayout(content: () -> Unit) {}", 0, "screens")
    }

    fun testSpecConstructionAndIngress() {
        val body = "fun bad() = io.github.anastaci1a.localica.ui.spec.UiSpec()"
        check(UiRuleId.UnvalidatedUiSpec, body, 1)
        check(UiRuleId.UnvalidatedUiSpec, body, 0, "spec")
        check(UiRuleId.UnvalidatedUiSpec, "fun valid() = io.github.anastaci1a.localica.ui.spec.uiSpec()", 0)
    }

    fun testLayerImportsQualifiedReferencesAndDataLeaf() {
        check(UiRuleId.UiLayerDependency, "import io.github.anastaci1a.localica.ui.engine.MotionValue\nfun f(v: MotionValue) = v", 2, "spec")
        check(UiRuleId.UiLayerDependency, "fun f() = io.github.anastaci1a.localica.ui.layout.LayoutMarker()", 1, "components")
        check(UiRuleId.UiLayerDependency, "import io.github.anastaci1a.localica.ui.spec.Tokens\nfun f() = Tokens.gap", 0, "engine")
        check(UiRuleId.UiLayerDependency, "import androidx.compose.animation.core.tween\nfun f() = tween()", 2, "spec")
    }

    fun testForgedAndGlobalEscapes() {
        check(UiRuleId.UnreviewedUiEscape, "@Suppress(\"RawUiMotion\") fun bad() = 1", 1)
        check(UiRuleId.UnreviewedUiEscape, "@Suppress(\"all\") fun bad() = 1", 1)
        check(UiRuleId.UnreviewedUiEscape, "//noinspection RawUiMotion\nfun bad() = 1", 1)
        check(UiRuleId.UnreviewedUiEscape, "@Suppress(\"UNUSED_PARAMETER\") fun fine() = 1", 0)
    }

    fun testStageThreeRootAllowanceExpiresWithItsStep() {
        fun stage(completed: String) = TestFiles.source("tools/ui-consolidation/stage.json",
            """{"schemaVersion":1,"knownSteps":["U04.01b","U06.01","U09.01b","U10.05","U11.04","U13.02b"],"completedSteps":[$completed]}""")
        fun root(file: String, vararg extra: TestFile) = lint().files(*(stubs + kotlin("src/test/$file", """
            package io.github.anastaci1a.localica.ui
            import io.github.anastaci1a.localica.ui.engine.FrameStamp
            fun f(stamp: FrameStamp) = stamp
        """).indented() + extra).toTypedArray()).issues(UiRuleId.UiLayerDependency.issue).allowMissingSdk().testModes(TestMode.DEFAULT).run()
        root("GlassShadow.kt", stage("")).expectClean()
        root("GlassShadowTwin.kt", stage("")).expectErrorCount(2)
        root("GlassSilhouette.kt", stage("")).expectErrorCount(2)
        root("GlassSilhouetteOptics.kt", stage("")).expectClean()
        root("GlassSilhouetteOptics.kt", stage("\"U10.05\"")).expectErrorCount(2)
        // B2 (9:45 AM): the pixel-rectangle callers read the moved Engine type until their U09/U11 adoption.
        for ((file, step) in listOf("MotionPixels.kt" to "U11.04", "PlayerSurfaceFrame.kt" to "U11.04", "BrowseItemLayout.kt" to "U09.01b")) {
            root(file, stage("")).expectClean()
            root(file, stage("\"$step\"")).expectErrorCount(2)
        }
        root("GlassShadow.kt", stage("\"U04.01b\"")).expectClean()
        root("GlassShadow.kt", stage("\"U06.01\"")).expectErrorCount(2)
        root("GlassShadow.kt", TestFiles.source("tools/ui-consolidation/stage.json", "{\"schemaVersion\":1,\"knownSteps\":[\"U13.02b\"],\"completedSteps\":[]}")).expectErrorCount(2)
        root("GlassShadow.kt", TestFiles.source("tools/ui-consolidation/stage.json", "not json [")).expectErrorCount(2)
        root("GlassShadow.kt").expectErrorCount(2)
    }

    fun testStageThreeOwnersAreNotConstructedOutsideEngine() {
        for (type in listOf("PersistentShape", "SurfaceNode", "ShadowPlane", "HostKey")) {
            check(UiRuleId.RawUiMotion, "fun bad() = io.github.anastaci1a.localica.ui.engine.$type()", 1)
            check(UiRuleId.RawUiMotion, "fun fine() = io.github.anastaci1a.localica.ui.engine.$type()", 0, "engine")
        }
        check(UiRuleId.RawUiMotion, "fun bad() = io.github.anastaci1a.localica.ui.engine.boundedPlan()", 1)
    }

    companion object {
        internal val stubs = listOf(
            kotlin("""
                package androidx.compose.runtime
                @Target(AnnotationTarget.FUNCTION, AnnotationTarget.TYPE) annotation class Composable
                fun <T> remember(vararg keys: Any?, factory: () -> T): T = factory()
                suspend fun withFrameNanos(block: (Long) -> Unit) {}
                @Composable fun SideEffect(effect: () -> Unit) {}
                @Composable fun LaunchedEffect(key1: Any?, block: suspend () -> Unit) {}
                @Composable fun DisposableEffect(key1: Any?, effect: () -> Unit) {}
                suspend fun withFrameMillis(block: (Long) -> Unit) {}
            """).indented(),
            kotlin("""
                package androidx.compose.animation.core
                interface AnimationSpec
                fun tween(): AnimationSpec = TODO()
                fun spring(): AnimationSpec = TODO()
            """).indented(),
            kotlin("""
                package androidx.compose.ui
                object Modifier
                fun Modifier.zIndex(z: Float): Modifier = this
            """).indented(),
            kotlin("""
                package androidx.compose.ui.graphics
                import androidx.compose.ui.Modifier
                class Layer { var alpha = 1f; var scaleX = 1f; var clip = false }
                fun Modifier.graphicsLayer(block: Layer.() -> Unit): Modifier = this
                class Path
                fun rememberGraphicsLayer() = Layer()
                fun drawLayer(layer: Layer) {}
            """).indented(),
            kotlin("""
                package androidx.compose.ui.draw
                import androidx.compose.ui.Modifier
                fun Modifier.alpha(alpha: Float): Modifier = this
                fun Modifier.shadow(elevation: Float): Modifier = this
            """).indented(),
            kotlin("""
                package androidx.compose.foundation.shape
                class RoundedCornerShape(val corner: Float)
                class GenericShape(val block: () -> Unit)
            """).indented(),
            kotlin("""
                package androidx.compose.ui.window
                fun Popup(content: () -> Unit) {}
            """).indented(),
            kotlin("""
                package androidx.compose.foundation.layout
                fun Column(content: () -> Unit) {}
            """).indented(),
            kotlin("""
                package androidx.compose.ui.unit
                class Dp
                class TextUnit
                val Int.dp: Dp get() = Dp()
                val Int.sp: TextUnit get() = TextUnit()
            """).indented(),
            kotlin("""
                package io.github.anastaci1a.localica.ui.spec
                class UiSpec
                class ResolvedUiSpec
                fun uiSpec() = UiSpec()
                object Tokens { val gap = 8 }
            """).indented(),
            kotlin("package io.github.anastaci1a.localica.ui.layout; class LayoutMarker").indented(),
            kotlin("""
                package io.github.anastaci1a.localica.ui.engine
                class FrameStamp
                data class Kinematic(val value: Float, val velocity: Float)
                interface MotionValue {
                    fun value(): Float = 0f
                    fun velocity(): Float = 0f
                    fun sample(): Kinematic = Kinematic(0f, 0f)
                    fun valueAt(stamp: FrameStamp): Float = 0f
                    fun velocityAt(stamp: FrameStamp): Float = 0f
                    fun sampleAt(stamp: FrameStamp): Kinematic = Kinematic(0f, 0f)
                    val target: Float get() = 1f
                }
                open class MotionFloat : MotionValue {
                    var onRest: (() -> Unit)? = null
                    fun retarget() {}; fun retargetSpring() {}; fun drive() {}; fun release() {}; fun initialize() {}
                }
                class MotionRect : MotionValue {
                    fun retarget() {}; fun retargetSpring() {}; fun drive() {}; fun release() {}; fun initialize() {}
                }
                class MotionColor : MotionValue {
                    fun retarget() {}; fun retargetSpring() {}; fun drive() {}; fun release() {}; fun initialize() {}
                }
                interface ExpansionView { val phase: MotionValue; val expanded: Boolean; val inContact: Boolean }
                class ExpandCollapse {
                    fun updateSpec(value: Any) {}
                    fun target() {}; fun contactBegin() {}; fun contactMove() {}; fun contactRelease() {}
                    fun contactCancel() {}; fun setBasis() {}; fun bind() {}
                }
                interface ShapeView { fun outline(): Any; fun outlineAt(stamp: FrameStamp): Any }
                class PersistentShape { fun retarget() {}; fun drive() {} }
                class SurfaceNode { fun request() {}; fun transfer() {}; fun dismiss() {} }
                class SurfaceTarget
                class ShadowPlane
                class HostKey
                fun boundedPlan() = 0
                fun rememberMotionFloat() = MotionFloat()
                fun rememberMotionRect() = MotionRect()
                fun rememberMotionColor() = MotionColor()
                fun rememberExpandCollapse() = ExpandCollapse()
                fun rememberPersistentShape() = PersistentShape()
                fun rememberSurfaceNode() = SurfaceNode()
            """).indented(),
        )
    }
}
