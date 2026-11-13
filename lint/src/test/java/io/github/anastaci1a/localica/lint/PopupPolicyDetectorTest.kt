package io.github.anastaci1a.localica.lint

import com.android.tools.lint.checks.infrastructure.LintDetectorTest
import com.android.tools.lint.checks.infrastructure.TestFiles.kotlin
import com.android.tools.lint.checks.infrastructure.TestMode
import com.android.tools.lint.detector.api.Detector

class PopupPolicyDetectorTest : LintDetectorTest() {
    override fun getDetector(): Detector = PopupPolicyDetector()
    override fun getIssues() = LocalicaIssueRegistry().issues.filter { it.implementation.detectorClass == PopupPolicyDetector::class.java }
    private fun appFiles(vararg files: com.android.tools.lint.checks.infrastructure.TestFile) =
        lint().projects(project().name("app").files(*files))
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
        open class GlassAnchor {
            val popupHome: GlassPopupHome? = null
            var face: (() -> Unit)? = null
            var depthScale: Float = 1f
            var futureBindingField: Boolean = false
            val contact = GlassContact()
        }
        class GlassContact { var modal = false; val state = GlassBodyState(); val expansion = GlassExpansion() }
        class GlassBodyState { var impulseScale = 1f }
        class GlassExpansion { fun bind() {} }
        val LocalGlassPopupHome = 1
        fun glassPopupProjection() {}
        fun menu(motionKind: Int) {}
        fun rememberGlassAnchor() = GlassAnchor()
        fun rememberGlassPopupHome() = Unit
        fun GlassPopupHome(home: Unit, content: () -> Unit) {}
        fun GlassPopupHome(content: () -> Unit) {}
        fun Modifier.glassAnchor(anchor: GlassAnchor, role: TriggerRole = TriggerRole.Surface, face: (() -> Unit)? = null) = this
        fun GlassAnchor.captureInk() {}
        fun GlassAnchor.glassAnchor(role: TriggerRole.MaterialSurface) = { _: Int, _: Boolean -> Unit }
        fun materialRole() = TriggerRole.MaterialSurface(1)
    """).indented()
    private val native = kotlin("src/main/java/androidx/compose/ui/window/Dialog.kt", "package androidx.compose.ui.window\nfun Dialog(body: () -> Unit) {}")
    private val homeSource = """
        package io.github.anastaci1a.localica.ui
        open class GlassPopupHome {
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
        appFiles(*dependencies, kotlin(root + "Screen.kt", """
            package io.github.anastaci1a.localica.ui
            import io.github.anastaci1a.localica.ui.components.TriggerRole as Role
            fun screen() { println(Role.Selection); println(Role.Lyrics); println(Role.Navigation) }
        """.trimIndent())).allowMissingSdk().testModes(TestMode.DEFAULT).run().expectErrorCount(3)
    }

    fun testCalibrationShadowRegistrationIsOnlyForGlassShadow() {
        appFiles(*dependencies, kotlin(root + "Screen.kt", """
            package io.github.anastaci1a.localica.ui
            fun screen(anchor: GlassAnchor) { anchor.glassAnchor(materialRole()) }
        """.trimIndent())).allowMissingSdk().testModes(TestMode.DEFAULT).run().expectErrorCount(1)
    }

    fun testCalibrationNestedAnchorContactWritesFail() {
        appFiles(*dependencies, kotlin(root + "Screen.kt", """
            package io.github.anastaci1a.localica.ui
            fun screen(anchor: GlassAnchor) {
                anchor.contact.modal = true
                anchor.contact.state.impulseScale = 2f
                anchor.contact.expansion.bind()
            }
        """.trimIndent())).allowMissingSdk().testModes(TestMode.DEFAULT).run().expectErrorCount(3)
    }

    fun testCalibrationRoleConstructorsArePinnedIncludingAliases() {
        appFiles(*dependencies, kotlin(root + "Screen.kt", """
            package io.github.anastaci1a.localica.ui
            import io.github.anastaci1a.localica.ui.components.TriggerRole as Role
            import io.github.anastaci1a.localica.ui.components.TriggerRole.SelectionItem as Item
            fun screen() { println(Item(1)); println(Role.LyricsHeader(1, 2)); println(Role.MaterialSurface(1)); println(Role.RetainedSurface(1)) }
        """.trimIndent())).allowMissingSdk().testModes(TestMode.DEFAULT).run().expectErrorCount(4)
    }

    fun testCalibrationNestedCallableAndImplicitScopeReceiversFail() {
        appFiles(*dependencies, kotlin(root + "Screen.kt", """
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
        appFiles(*dependencies, kotlin(root + "Screen.kt", """
            package io.github.anastaci1a.localica.ui
            fun screen() { val factory = ::rememberGlassPopupHome; val projection = ::glassPopupProjection }
        """.trimIndent())).allowMissingSdk().testModes(TestMode.DEFAULT).run().expectErrorCount(2)
    }

    fun testPinnedRoleAndRegistrationCallersPass() {
        for ((file, role) in listOf("FloatingSelectionPanel.kt" to "Selection", "PlayerSurface.kt" to "Lyrics", "LocalicaScreen.kt" to "Navigation")) {
            appFiles(*dependencies, kotlin(root + file, "package io.github.anastaci1a.localica.ui\nimport io.github.anastaci1a.localica.ui.components.TriggerRole as Role\nfun screen() { println(Role.$role) }"))
                .allowMissingSdk().testModes(TestMode.DEFAULT).run().expectClean()
        }
        appFiles(*dependencies, kotlin(root + "GlassShadow.kt", "package io.github.anastaci1a.localica.ui\nfun screen(anchor: GlassAnchor) { anchor.glassAnchor(materialRole()) }"))
            .allowMissingSdk().testModes(TestMode.DEFAULT).run().expectClean()
    }

    fun testNestedContactAliasesAndScopeFunctionReceiversFail() {
        appFiles(*dependencies, kotlin(root + "Screen.kt", """
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
        appFiles(*dependencies, kotlin(root + "Screen.kt", source))
            .allowMissingSdk().testModes(TestMode.DEFAULT).run().expectErrorCount(1)
    }

    fun testCalibrationHomeAndFlightWritesAndCollectionMutatorsFail() {
        appFiles(*dependencies, kotlin(root + "Screen.kt", """
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
        appFiles(*dependencies, kotlin(root + "Screen.kt", """
            package io.github.anastaci1a.localica.ui
            fun screen(anchor: GlassAnchor) {
                val make = ::GlassAnchor
                val field = anchor::face
                field.set({})
            }
        """.trimIndent())).allowMissingSdk().testModes(TestMode.DEFAULT).run().expectErrorCount(3)
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
            "DuplicatePopupRole" to "class SecondPopupAnchor : GlassAnchor()",
        )
        for ((id, source) in mutants) {
            appFiles(*dependencies, kotlin(root + "Screen.kt", "package io.github.anastaci1a.localica.ui\n" + source))
                .issues(LocalicaIssueRegistry().issues.single { it.id == id })
                .allowMissingSdk().testModes(TestMode.DEFAULT).run().expectErrorCount(1)
            appFiles(*dependencies, kotlin(root + "InterfaceComponents.kt", "package io.github.anastaci1a.localica.ui\n" + sanctioned))
                .issues(LocalicaIssueRegistry().issues.single { it.id == id })
                .allowMissingSdk().testModes(TestMode.DEFAULT).run().expectClean()
            appFiles(*dependencies, kotlin(root + "components/ControlProgress.kt", "package io.github.anastaci1a.localica.ui.components\nimport io.github.anastaci1a.localica.ui.*\n" + source))
                .issues(LocalicaIssueRegistry().issues.single { it.id == id })
                .allowMissingSdk().testModes(TestMode.DEFAULT).run().expectErrorCount(1)
        }
    }

    fun testAliasedHomesAndNativeWindowsStillFail() {
        appFiles(*dependencies, kotlin(root + "Screen.kt", """
            package io.github.anastaci1a.localica.ui
            import androidx.compose.ui.window.Dialog as PrivateDialog
            import io.github.anastaci1a.localica.ui.LocalGlassPopupHome as LocalHome
            fun screen() { println(LocalHome); PrivateDialog {} }
        """.trimIndent())).allowMissingSdk().testModes(TestMode.DEFAULT).run().expectErrorCount(2)
    }

    fun testPerRuleOwnersCannotBorrowAnotherCapability() {
        appFiles(*dependencies.filter { it !== homeApi }.toTypedArray(), kotlin(root + "GlassPopupHome.kt", homeSource + "\n" + """
            fun bindHome() { println(LocalGlassPopupHome) }
            fun forbidden() { androidx.compose.ui.window.Dialog {} }
        """.trimIndent())).allowMissingSdk().testModes(TestMode.DEFAULT).run().expectErrorCount(1)
    }

    fun testOwnerCannotGrowAnotherRoleImplementation() {
        appFiles(*dependencies.filter { it !== homeApi }.toTypedArray(), kotlin(root + "GlassPopupHome.kt", homeSource + "\n" + """
            class SecondPopupHome : GlassPopupHome()
        """.trimIndent())).allowMissingSdk().testModes(TestMode.DEFAULT).run().expectErrorCount(1)
    }

    fun testNeighboringOwnerFilenameDoesNotBypassThePolicy() {
        appFiles(*dependencies, kotlin(root + "GlassMorphCopy.kt", """
            package io.github.anastaci1a.localica.ui
            fun copy() { glassPopupProjection() }
        """.trimIndent())).allowMissingSdk().testModes(TestMode.DEFAULT).run().expectErrorCount(1)
    }

    fun testAllAnchorFieldsAreProtectedIncludingFutureFieldsAndImplicitWriters() {
        appFiles(*dependencies, kotlin(root + "Screen.kt", """
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
            appFiles(*dependencies, kotlin(root + file, "package io.github.anastaci1a.localica.ui\n" + source))
                .allowMissingSdk().testModes(TestMode.DEFAULT).run().expectErrorCount(2)
        }
        for (file in listOf("LocalicaScreen.kt", "PlayerSurface.kt", "QueueSurface.kt")) {
            appFiles(*dependencies, kotlin(root + file, "package io.github.anastaci1a.localica.ui\n" + source))
                .allowMissingSdk().testModes(TestMode.DEFAULT).run().expectClean()
        }
    }

    fun testCompoundAndUnaryAnchorWritesFail() {
        appFiles(*dependencies, kotlin(root + "Screen.kt", """
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
        appFiles(*dependencies, kotlin(root + "components/Legacy.kt", """
            package io.github.anastaci1a.localica.ui.components
            fun rememberPresence(key: Any, shown: Boolean, renamedMotionSwitch: Boolean) {}
        """.trimIndent()), kotlin(root + "Screen.kt", """
            package io.github.anastaci1a.localica.ui
            import io.github.anastaci1a.localica.ui.components.rememberPresence
            fun screen() { rememberPresence("key", true, renamedMotionSwitch = true) }
        """.trimIndent())).allowMissingSdk().testModes(TestMode.DEFAULT).run().expectErrorCount(1)
    }
    fun testBlindNativeConstructorReferencesAndAliases() {
        appFiles(*dependencies,
            kotlin("src/main/java/android/app/Dialog.kt", "package android.app\nopen class Dialog"),
            kotlin(root + "Screen.kt", """
                package io.github.anastaci1a.localica.ui
                import android.app.Dialog as Window
                typealias OtherWindow = Window
                class CustomWindow : Window()
                fun screen() { val a = ::Window; val b = ::OtherWindow; CustomWindow() }
            """.trimIndent())).allowMissingSdk().testModes(TestMode.DEFAULT).run().expectErrorCount(4)
    }

    fun testBlindPrivateMotionEntryPointsAndReferences() {
        appFiles(*dependencies,
            kotlin(root + "engine/BlindMotion.kt", """
                package io.github.anastaci1a.localica.ui.engine
                fun rememberPopupMotion() {}
                fun rememberExpandCollapse() {}
                fun drawPopupProjection() {}
            """.trimIndent()), kotlin(root + "Screen.kt", """
                package io.github.anastaci1a.localica.ui
                import io.github.anastaci1a.localica.ui.engine.*
                fun screen() { rememberPopupMotion(); rememberExpandCollapse(); drawPopupProjection(); val project = ::drawPopupProjection }
            """.trimIndent())).allowMissingSdk().testModes(TestMode.DEFAULT).run().expectErrorCount(4)
    }

    private fun assertPolicyFlagCannotBorrowIntentName(type: String) {
        appFiles(*dependencies, kotlin(root + "components/Legacy.kt", """
            package io.github.anastaci1a.localica.ui.components
            fun rememberPresence(key: Any, visible: $type) {}
        """.trimIndent()), kotlin(root + "Screen.kt", """
            package io.github.anastaci1a.localica.ui
            import io.github.anastaci1a.localica.ui.components.rememberPresence
            fun screen() { rememberPresence("key", visible = true) }
        """.trimIndent())).allowMissingSdk().testModes(TestMode.DEFAULT).run().expectErrorCount(1)
    }

    fun testBlindPrimitivePolicyFlagCannotBorrowIntentName() = assertPolicyFlagCannotBorrowIntentName("Boolean")
    fun testBlindNullablePolicyFlagCannotBorrowIntentName() = assertPolicyFlagCannotBorrowIntentName("Boolean?")

    fun testBlindAliasedAndAnonymousRoleImplementations() {
        appFiles(*dependencies, kotlin(root + "components/Contracts.kt", """
            package io.github.anastaci1a.localica.ui.components
            interface PopupState
            interface PopupScope
        """.trimIndent()), kotlin(root + "Screen.kt", """
            package io.github.anastaci1a.localica.ui
            import io.github.anastaci1a.localica.ui.components.PopupState as State
            import io.github.anastaci1a.localica.ui.components.PopupScope as Scope
            class Innocent : State
            val alternate = object : Scope {}
        """.trimIndent())).allowMissingSdk().testModes(TestMode.DEFAULT).run().expectErrorCount(2)
    }

    fun testBlindForeignModuleCannotBorrowOwnerPath() {
        lint().projects(project().name("foreign").files(*dependencies.filter { it !== homeApi }.toTypedArray(),
            kotlin(root + "GlassPopupHome.kt", homeSource + "\nfun steal() { glassPopupProjection() }")))
            .issues(LocalicaIssueRegistry().issues.single { it.id == "ScreenPopupHome" })
            .allowMissingSdk().testModes(TestMode.DEFAULT).run().expectErrorCount(1)
    }

    fun testBlindExtendedNativePresenters() {
        appFiles(*dependencies,
            kotlin("src/main/java/android/widget/Windows.kt", "package android.widget\nclass PopupMenu\nclass ListPopupWindow"),
            kotlin("src/main/java/androidx/appcompat/app/AlertDialog.kt", "package androidx.appcompat.app\nclass AlertDialog { class Builder }"),
            kotlin("src/main/java/androidx/compose/material3/Presenters.kt", """
                package androidx.compose.material3
                fun DatePickerDialog() {}
                fun TooltipBox() {}
                fun ExposedDropdownMenu() {}
                fun SearchBar() {}
            """.trimIndent()), kotlin(root + "Screen.kt", """
                package io.github.anastaci1a.localica.ui
                import android.widget.PopupMenu
                import android.widget.ListPopupWindow
                import androidx.appcompat.app.AlertDialog
                import androidx.compose.material3.*
                fun screen() { PopupMenu(); ListPopupWindow(); AlertDialog.Builder(); DatePickerDialog(); TooltipBox(); ExposedDropdownMenu(); SearchBar() }
            """.trimIndent())).allowMissingSdk().testModes(TestMode.DEFAULT).run().expectErrorCount(7)
    }

    fun testBlindPinnedHostCannotRestoreDeletedUtilityHome() {
        appFiles(*dependencies, kotlin(root + "LocalicaScreen.kt", """
            package io.github.anastaci1a.localica.ui
            import io.github.anastaci1a.localica.ui.rememberGlassPopupHome as home
            fun screen() {
                val browse = home()
                val player = home()
                val queue = home()
                GlassPopupHome(browse) {}
                val utility = home()
                GlassPopupHome(utility) {}
            }
        """.trimIndent())).allowMissingSdk().testModes(TestMode.DEFAULT).run().expectErrorCount(2)
    }

    fun testProtectedConstructorReferencesAreIndependentlyGuarded() {
        appFiles(*dependencies, kotlin(root + "Screen.kt", """
            package io.github.anastaci1a.localica.ui
            import io.github.anastaci1a.localica.ui.GlassAnchor as Anchor
            import io.github.anastaci1a.localica.ui.GlassPopupFlight as Flight
            fun screen() { val makeAnchor = ::Anchor; val makeFlight = ::Flight }
        """.trimIndent())).allowMissingSdk().testModes(TestMode.DEFAULT).run().expectErrorCount(2)
    }


    fun test885SheetRoutingIsPinnedToItsExistingCallers() {
        val sheet = kotlin(root + "components/Sheet.kt", """
            package io.github.anastaci1a.localica.ui.components
            fun Sheet(state: Any, onSettled: (Boolean) -> Unit, returningChild: Any, content: () -> Unit) {}
        """.trimIndent())
        val drag = kotlin(root + "components/SheetDrag.kt", """
            package io.github.anastaci1a.localica.ui.components
            import androidx.compose.ui.Modifier
            fun Modifier.sheetDrag(target: Any, openingChild: Any? = null, canStart: () -> Boolean = { true }, onDraggingChanged: (Boolean) -> Unit = {}) = this
            fun Modifier.verticalSurfaceDrag(onDelta: (Float) -> Unit, onStart: suspend () -> Unit, onStop: suspend (Float) -> Unit, onCancel: suspend () -> Unit, canStart: () -> Boolean = { true }, onCatch: () -> Boolean = { false }) = this
        """.trimIndent())
        val cases = listOf(
            "LocalicaScreen.kt" to "Sheet(Any(), onSettled = {}, returningChild = Any()) {}",
            "PlayerSurface.kt" to "Modifier.sheetDrag(Any(), onDraggingChanged = {})",
            "QueueSurface.kt" to "Modifier.verticalSurfaceDrag({}, {}, {}, {})")
        for ((owner, call) in cases) {
            val imports = "package io.github.anastaci1a.localica.ui\nimport androidx.compose.ui.Modifier\nimport io.github.anastaci1a.localica.ui.components.*\n"
            appFiles(modifier, sheet, drag, kotlin(root + owner, imports + "fun f() { $call }")).allowMissingSdk().testModes(TestMode.DEFAULT).run().expectClean()
            for (form in 0..2) {
                val name = if (owner == "LocalicaScreen.kt") "Sheet" else if (owner == "PlayerSurface.kt") "sheetDrag" else "verticalSurfaceDrag"
                val source = if (form == 1) imports + "import io.github.anastaci1a.localica.ui.components.$name as route\nfun f() { " + call.replace(name, "route") + " }" else imports + "fun f() { " + (if (form == 2) call.replace("Sheet(", "io.github.anastaci1a.localica.ui.components.Sheet(").replace("Modifier.", "androidx.compose.ui.Modifier.") else call) + " }"
                appFiles(modifier, sheet, drag, kotlin(root + "Screen.kt", source)).allowMissingSdk().testModes(TestMode.DEFAULT).run().expectErrorCount(if (owner == "QueueSurface.kt") 4 else 2)
            }
        }
    }
}
