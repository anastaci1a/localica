package io.github.anastaci1a.localica.lint

import com.android.tools.lint.checks.infrastructure.LintDetectorTest
import com.android.tools.lint.checks.infrastructure.TestFiles.kotlin
import com.android.tools.lint.checks.infrastructure.TestMode
import com.android.tools.lint.detector.api.Detector

class PopupPolicyDetectorTest : LintDetectorTest() {
    override fun getDetector(): Detector = PopupPolicyDetector()
    override fun getIssues() = LocalicaIssueRegistry().issues.filter { it.implementation.detectorClass == PopupPolicyDetector::class.java }
    private val root = "src/main/java/io/github/anastaci1a/localica/ui/"
    private val modifier = kotlin("src/main/java/androidx/compose/ui/Modifier.kt", """
        package androidx.compose.ui
        open class Modifier { companion object : Modifier() }
    """).indented()
    private val roles = kotlin(root + "components/TriggerRole.kt", """
        package io.github.anastaci1a.localica.ui.components
        sealed interface TriggerRole {
            data object Surface : TriggerRole
            data object PlainIcon : TriggerRole
        }
    """).indented()
    private val api = kotlin(root + "GlassMorph.kt", """
        package io.github.anastaci1a.localica.ui
        import androidx.compose.ui.Modifier
        import io.github.anastaci1a.localica.ui.components.TriggerRole
        class GlassAnchor {
            var face: (() -> Unit)? = null
            var depthScale: Float = 1f
            var futureBindingField: Boolean = false
        }
        val LocalGlassPopupHome = 1
        fun glassPopupProjection() {}
        fun menu(motionKind: Int) {}
        fun rememberGlassAnchor() = GlassAnchor()
        fun Modifier.glassAnchor(anchor: GlassAnchor, role: TriggerRole = TriggerRole.Surface, face: (() -> Unit)? = null) = this
        fun GlassAnchor.captureInk() {}
    """).indented()
    private val native = kotlin("src/main/java/androidx/compose/ui/window/Dialog.kt", "package androidx.compose.ui.window\nfun Dialog(body: () -> Unit) {}")
    private val dependencies get() = arrayOf(api, modifier, roles, native)
    private val sanctioned = """
        import androidx.compose.ui.Modifier
        import io.github.anastaci1a.localica.ui.*
        import io.github.anastaci1a.localica.ui.components.TriggerRole
        fun screen() {
            val anchor = rememberGlassAnchor()
            Modifier.glassAnchor(anchor, role = TriggerRole.PlainIcon, face = { println("face") })
            anchor.captureInk()
        }
    """.trimIndent()

    fun testEachRuleHasMutantAndSanctionedContract() {
        val mutants = mapOf(
            "ScreenPopupHome" to "fun screen() { glassPopupProjection() }",
            "ScreenPopupMotion" to "fun screen() { menu(motionKind = 1) }",
            "ScreenPopupTrigger" to "fun screen() { GlassAnchor() }",
            "ScreenPopupDrawPath" to "fun screen() { androidx.compose.ui.window.Dialog {} }",
            "DuplicatePopupRole" to "class SecondPopupAnchor",
        )
        for ((id, source) in mutants) {
            lint().files(*dependencies, kotlin(root + "Screen.kt", "package io.github.anastaci1a.localica.ui\n" + source))
                .issues(LocalicaIssueRegistry().issues.single { it.id == id })
                .allowMissingSdk().testModes(TestMode.DEFAULT).run().expectErrorCount(1)
            lint().files(*dependencies, kotlin(root + "Screen.kt", "package io.github.anastaci1a.localica.ui\n" + sanctioned))
                .issues(LocalicaIssueRegistry().issues.single { it.id == id })
                .allowMissingSdk().testModes(TestMode.DEFAULT).run().expectClean()
            lint().files(*dependencies, kotlin(root + "components/ControlProgress.kt", "package io.github.anastaci1a.localica.ui.components\nimport io.github.anastaci1a.localica.ui.*\n" + source))
                .issues(LocalicaIssueRegistry().issues.single { it.id == id })
                .allowMissingSdk().testModes(TestMode.DEFAULT).run().expectErrorCount(1)
        }
    }

    fun testAliasedHomesAndNativeWindowsStillFail() {
        lint().files(*dependencies, kotlin(root + "Screen.kt", """
            package io.github.anastaci1a.localica.ui
            import androidx.compose.ui.window.Dialog as PrivateDialog
            import io.github.anastaci1a.localica.ui.LocalGlassPopupHome as LocalHome
            fun screen() { println(LocalHome); PrivateDialog {} }
        """.trimIndent())).allowMissingSdk().testModes(TestMode.DEFAULT).run().expectErrorCount(2)
    }

    fun testPerRuleOwnersCannotBorrowAnotherCapability() {
        lint().files(*dependencies, kotlin(root + "GlassPopupHome.kt", """
            package io.github.anastaci1a.localica.ui
            class GlassPopupHome
            fun bindHome() { println(LocalGlassPopupHome) }
            fun forbidden() { androidx.compose.ui.window.Dialog {} }
        """.trimIndent())).allowMissingSdk().testModes(TestMode.DEFAULT).run().expectErrorCount(1)
    }

    fun testOwnerCannotGrowAnotherRoleImplementation() {
        lint().files(*dependencies, kotlin(root + "GlassPopupHome.kt", """
            package io.github.anastaci1a.localica.ui
            class SecondPopupHome
        """.trimIndent())).allowMissingSdk().testModes(TestMode.DEFAULT).run().expectErrorCount(1)
    }

    fun testNeighboringOwnerFilenameDoesNotBypassThePolicy() {
        lint().files(*dependencies, kotlin(root + "GlassMorphCopy.kt", """
            package io.github.anastaci1a.localica.ui
            fun copy() { glassPopupProjection() }
        """.trimIndent())).allowMissingSdk().testModes(TestMode.DEFAULT).run().expectErrorCount(1)
    }

    fun testAllAnchorFieldsAreProtectedIncludingFutureFieldsAndImplicitWriters() {
        lint().files(*dependencies, kotlin(root + "Screen.kt", """
            package io.github.anastaci1a.localica.ui
            fun override(anchor: GlassAnchor) {
                anchor.apply { face = {} }
                with(anchor) { depthScale = 2f }
                anchor.futureBindingField = true
            }
        """.trimIndent())).allowMissingSdk().testModes(TestMode.DEFAULT).run().expectErrorCount(3)
    }

    fun testRenamingABooleanMotionSwitchCannotBypassTheGate() {
        lint().files(*dependencies, kotlin(root + "components/Legacy.kt", """
            package io.github.anastaci1a.localica.ui.components
            fun rememberPresence(key: Any, shown: Boolean, renamedMotionSwitch: Boolean) {}
        """.trimIndent()), kotlin(root + "Screen.kt", """
            package io.github.anastaci1a.localica.ui
            import io.github.anastaci1a.localica.ui.components.rememberPresence
            fun screen() { rememberPresence("key", true, renamedMotionSwitch = true) }
        """.trimIndent())).allowMissingSdk().testModes(TestMode.DEFAULT).run().expectErrorCount(1)
    }
}
