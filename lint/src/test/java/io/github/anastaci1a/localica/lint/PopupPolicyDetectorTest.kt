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
            data object Selection : TriggerRole
            data object Lyrics : TriggerRole
            data object Navigation : TriggerRole
            data class SelectionItem(val entry: Int) : TriggerRole
            data class LyricsHeader(val contacts: Int, val preparation: Int) : TriggerRole
            data class MaterialSurface(val plane: Int) : TriggerRole
            data class RetainedSurface(val plane: Int) : TriggerRole
        }
    """).indented()
    private val api = kotlin(root + "GlassMorph.kt", """
        package io.github.anastaci1a.localica.ui
        import androidx.compose.ui.Modifier
        import io.github.anastaci1a.localica.ui.components.TriggerRole
        class GlassAnchor {
            val popupHome: GlassPopupHome? = null
            var face: (() -> Unit)? = null
            var depthScale: Float = 1f
            var futureBindingField: Boolean = false
            val contact = GlassContact()
        }
        class GlassContact { var modal = false; val state = ContactState(); val expansion = ContactExpansion() }
        class ContactState { var impulseScale = 1f }
        class ContactExpansion { fun bind() {} }
        val LocalGlassPopupHome = 1
        fun glassPopupProjection() {}
        fun menu(motionKind: Int) {}
        fun rememberGlassAnchor() = GlassAnchor()
        fun rememberGlassPopupHome() = Unit
        fun GlassPopupHome(home: Unit, content: () -> Unit) {}
        fun Modifier.glassAnchor(anchor: GlassAnchor, role: TriggerRole = TriggerRole.Surface, face: (() -> Unit)? = null) = this
        fun GlassAnchor.captureInk() {}
        fun GlassAnchor.glassAnchor(role: TriggerRole.MaterialSurface) = { _: Int, _: Boolean -> Unit }
        fun materialRole() = TriggerRole.MaterialSurface(1)
    """).indented()
    private val native = kotlin("src/main/java/androidx/compose/ui/window/Dialog.kt", "package androidx.compose.ui.window\nfun Dialog(body: () -> Unit) {}")
    private val homeSource = """
        package io.github.anastaci1a.localica.ui
        class GlassPopupHome {
            var mounted = true
            val flights = mutableListOf<GlassPopupFlight>()
            val children = mutableListOf<GlassPopupHome>()
        }
        class GlassPopupFlight {
            var chromeTrigger = false
            var phase: () -> Float = { 0f }
        }
    """.trimIndent()
    private val homeApi = kotlin(root + "GlassPopupHome.kt", homeSource)
    private val presenterApi = kotlin(root + "components/Popup.kt", """
        package io.github.anastaci1a.localica.ui.components
        val LocalPopupBodyPresenter = 1
        infix fun Int.provides(body: Int) = body
    """).indented()
    private val dependencies get() = arrayOf(api, modifier, roles, native, homeApi, presenterApi)

    fun testCalibrationNamedRoleCannotBeBorrowedByAnotherSurface() {
        lint().files(*dependencies, kotlin(root + "Screen.kt", """
            package io.github.anastaci1a.localica.ui
            import io.github.anastaci1a.localica.ui.components.TriggerRole as Role
            fun screen() { println(Role.Selection); println(Role.Lyrics); println(Role.Navigation) }
        """.trimIndent())).allowMissingSdk().testModes(TestMode.DEFAULT).run().expectErrorCount(3)
    }

    fun testCalibrationShadowRegistrationIsOnlyForGlassShadow() {
        lint().files(*dependencies, kotlin(root + "Screen.kt", """
            package io.github.anastaci1a.localica.ui
            fun screen(anchor: GlassAnchor) { anchor.glassAnchor(materialRole()) }
        """.trimIndent())).allowMissingSdk().testModes(TestMode.DEFAULT).run().expectErrorCount(1)
    }

    fun testCalibrationNestedAnchorContactWritesFail() {
        lint().files(*dependencies, kotlin(root + "Screen.kt", """
            package io.github.anastaci1a.localica.ui
            fun screen(anchor: GlassAnchor) {
                anchor.contact.modal = true
                anchor.contact.state.impulseScale = 2f
                anchor.contact.expansion.bind()
            }
        """.trimIndent())).allowMissingSdk().testModes(TestMode.DEFAULT).run().expectErrorCount(3)
    }

    fun testCalibrationRoleConstructorsArePinnedIncludingAliases() {
        lint().files(*dependencies, kotlin(root + "Screen.kt", """
            package io.github.anastaci1a.localica.ui
            import io.github.anastaci1a.localica.ui.components.TriggerRole as Role
            import io.github.anastaci1a.localica.ui.components.TriggerRole.SelectionItem as Item
            fun screen() { println(Item(1)); println(Role.LyricsHeader(1, 2)); println(Role.MaterialSurface(1)); println(Role.RetainedSurface(1)) }
        """.trimIndent())).allowMissingSdk().testModes(TestMode.DEFAULT).run().expectErrorCount(4)
    }

    fun testCalibrationNestedCallableAndImplicitScopeReceiversFail() {
        lint().files(*dependencies, kotlin(root + "Screen.kt", """
            package io.github.anastaci1a.localica.ui
            fun screen(anchor: GlassAnchor?) {
                val present = anchor ?: return
                val bind = present.contact.expansion::bind
                with(present.contact) { modal = true }
                anchor?.let { borrowed -> borrowed.contact.modal = true }
            }
        """.trimIndent())).allowMissingSdk().testModes(TestMode.DEFAULT).run().expectErrorCount(3)
    }

    fun testCalibrationHomeFactoryCallableReferencesFail() {
        lint().files(*dependencies, kotlin(root + "Screen.kt", """
            package io.github.anastaci1a.localica.ui
            fun screen() { val factory = ::rememberGlassPopupHome; val projection = ::glassPopupProjection }
        """.trimIndent())).allowMissingSdk().testModes(TestMode.DEFAULT).run().expectErrorCount(2)
    }

    fun testPinnedRoleAndRegistrationCallersPass() {
        for ((file, role) in listOf("FloatingSelectionPanel.kt" to "Selection", "PlayerSurface.kt" to "Lyrics", "LocalicaScreen.kt" to "Navigation")) {
            lint().files(*dependencies, kotlin(root + file, "package io.github.anastaci1a.localica.ui\nimport io.github.anastaci1a.localica.ui.components.TriggerRole as Role\nfun screen() { println(Role.$role) }"))
                .allowMissingSdk().testModes(TestMode.DEFAULT).run().expectClean()
        }
        lint().files(*dependencies, kotlin(root + "GlassShadow.kt", "package io.github.anastaci1a.localica.ui\nfun screen(anchor: GlassAnchor) { anchor.glassAnchor(materialRole()) }"))
            .allowMissingSdk().testModes(TestMode.DEFAULT).run().expectClean()
    }

    fun testNestedContactAliasesAndScopeFunctionReceiversFail() {
        lint().files(*dependencies, kotlin(root + "Screen.kt", """
            package io.github.anastaci1a.localica.ui
            fun screen(anchor: GlassAnchor) {
                val contact = anchor.contact
                val state = contact.state
                state.impulseScale += 1f
                val expansion = contact.expansion
                expansion.bind()
                rememberGlassAnchor().also { it.futureBindingField = true }
                anchor.let { borrowed -> borrowed.contact.modal = true }
            }
        """.trimIndent())).allowMissingSdk().testModes(TestMode.DEFAULT).run().expectErrorCount(4)
    }

    fun testCalibrationPresenterAliasesCannotReplaceTheOwner() {
        val source = """
            package io.github.anastaci1a.localica.ui
            import io.github.anastaci1a.localica.ui.components.LocalPopupBodyPresenter as Presenter
            import io.github.anastaci1a.localica.ui.components.provides
            fun screen() { println(Presenter provides 2) }
        """.trimIndent()
        lint().files(*dependencies, kotlin(root + "Screen.kt", source))
            .allowMissingSdk().testModes(TestMode.DEFAULT).run().expectErrorCount(1)
    }

    fun testCalibrationHomeAndFlightWritesAndCollectionMutatorsFail() {
        lint().files(*dependencies, kotlin(root + "Screen.kt", """
            package io.github.anastaci1a.localica.ui
            fun screen(anchor: GlassAnchor) {
                val home = anchor.popupHome ?: return
                home.mounted = false
                home.flights.clear()
                home.children.add(home)
                val flight = home.flights.first()
                flight.chromeTrigger = false
                flight.phase = { 0f }
            }
        """.trimIndent())).allowMissingSdk().testModes(TestMode.DEFAULT).run().expectErrorCount(5)
    }

    fun testCalibrationCallableReferencesCannotConstructOrWriteAnchors() {
        lint().files(*dependencies, kotlin(root + "Screen.kt", """
            package io.github.anastaci1a.localica.ui
            fun screen(anchor: GlassAnchor) {
                val make = ::GlassAnchor
                val field = anchor::face
                field.set({})
            }
        """.trimIndent())).allowMissingSdk().testModes(TestMode.DEFAULT).run().expectErrorCount(2)
    }
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
            lint().files(*dependencies, kotlin(root + "InterfaceComponents.kt", "package io.github.anastaci1a.localica.ui\n" + sanctioned))
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
        lint().files(*dependencies.filter { it !== homeApi }.toTypedArray(), kotlin(root + "GlassPopupHome.kt", homeSource + "\n" + """
            fun bindHome() { println(LocalGlassPopupHome) }
            fun forbidden() { androidx.compose.ui.window.Dialog {} }
        """.trimIndent())).allowMissingSdk().testModes(TestMode.DEFAULT).run().expectErrorCount(1)
    }

    fun testOwnerCannotGrowAnotherRoleImplementation() {
        lint().files(*dependencies.filter { it !== homeApi }.toTypedArray(), kotlin(root + "GlassPopupHome.kt", homeSource + "\n" + """
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

    fun testOnlyPinnedShellsCanHostHomes() {
        val source = "fun screen() { GlassPopupHome(rememberGlassPopupHome()) {} }"
        for (file in listOf("Screen.kt", "components/ControlProgress.kt")) {
            lint().files(*dependencies, kotlin(root + file, "package io.github.anastaci1a.localica.ui\n" + source))
                .allowMissingSdk().testModes(TestMode.DEFAULT).run().expectErrorCount(2)
        }
        for (file in listOf("LocalicaScreen.kt", "PlayerSurface.kt", "QueueSurface.kt", "Theme.kt")) {
            lint().files(*dependencies, kotlin(root + file, "package io.github.anastaci1a.localica.ui\n" + source))
                .allowMissingSdk().testModes(TestMode.DEFAULT).run().expectClean()
        }
    }

    fun testCompoundAndUnaryAnchorWritesFail() {
        lint().files(*dependencies, kotlin(root + "Screen.kt", """
            package io.github.anastaci1a.localica.ui
            fun mutate(anchor: GlassAnchor) {
                anchor.depthScale += 1f
                anchor.depthScale -= 1f
                anchor.depthScale *= 2f
                anchor.depthScale /= 2f
                anchor.depthScale %= 2f
                anchor.depthScale++
                ++anchor.depthScale
                anchor.depthScale--
                --anchor.depthScale
            }
        """.trimIndent())).allowMissingSdk().testModes(TestMode.DEFAULT).run().expectErrorCount(9)
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
