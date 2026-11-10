package io.github.anastaci1a.localica.lint

import com.android.tools.lint.checks.infrastructure.LintDetectorTest
import com.android.tools.lint.checks.infrastructure.TestFile
import com.android.tools.lint.checks.infrastructure.TestFiles
import com.android.tools.lint.checks.infrastructure.TestFiles.kotlin
import com.android.tools.lint.checks.infrastructure.TestMode
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Issue

/**
 * #757 menu rules. Per rule: a positive and an owner (negative) fixture, an import alias, a fully qualified call,
 * an unrelated symbol with the same simple name, an unresolved call, and a sibling file in the owner's package.
 */
class MenuPolicyDetectorTest : LintDetectorTest() {
    override fun getDetector(): Detector = MenuPolicyDetector()
    override fun getIssues(): List<Issue> = MenuRuleId.entries.map { it.issue }

    private val livePolicy = "# current-step: M2\nbinder | app/src/test/Subject.kt | never | binder\nlegacy | app/src/test/Legacy.kt | M3 | later\nlegacy | app/src/test/Expired.kt | M2 | now\n"

    /** [where] is the package under `ui` and [file] the file name: together they decide whether the subject is the owner. */
    private fun check(rule: MenuRuleId, code: String, count: Int, where: String = "menu", file: String = "Subject.kt", policy: String? = livePolicy,
        errors: Boolean = false) {
        val files = stubs + kotlin("src/test/$file", "package io.github.anastaci1a.localica.ui.$where\n$code").indented() +
            listOfNotNull(policy?.let { TestFiles.source("config/menu-host-policy.txt", it) })
        val task = lint().files(*files.toTypedArray()).issues(rule.issue).allowMissingSdk().testModes(TestMode.DEFAULT)
        val result = (if (errors) task.allowCompilationErrors() else task).run()
        if (count == 0) result.expectClean() else {
            result.expectMatches("(?s).*src/test/$file:[0-9]+: Error: .*\\[${rule.name}].*\\n$count errors?.*")
            result.expectErrorCount(count)
        }
    }

    fun testDeclarationPurity() {
        val rule = MenuRuleId.MenuDeclarationPurity
        check(rule, "import androidx.compose.material3.Text\nimport androidx.compose.foundation.layout.Spacer\nval x = 1", 2, "pages")
        check(rule, "import androidx.compose.material3.Text as Label\nval x = 1", 1, "pages")
        check(rule, "import androidx.compose.ui.*\nval x = 1", 1, "pages")
        check(rule, "import androidx.compose.runtime.Composable\nimport androidx.compose.runtime.remember\n@Composable fun state(): Int = remember { 1 }", 0, "pages")
        check(rule, "import androidx.compose.runtime.Composable\n@Composable fun Emits() {}", 1, "pages")
        check(rule, "@androidx.compose.runtime.Composable fun Emits() {}", 1, "pages")
        // The same code is the binder's and the layout's business, not a page's.
        check(rule, "import androidx.compose.material3.Text\nimport androidx.compose.runtime.Composable\n@Composable fun Emits() {}", 0, "menu")
        check(rule, "import other.compose.ui.Thing\nfun plain() {}", 0, "pages", errors = true)
    }

    fun testTypography() {
        val rule = MenuRuleId.UnownedSettingsTypography
        val body = "import androidx.compose.material3.Text\nimport androidx.compose.runtime.Composable\n@Composable fun Row() { Text(\"a\") }"
        check(rule, body, 1)
        check(rule, body, 1, "layout.menu", "MenuSlots.kt")
        check(rule, body, 0, "layout.menu", "MenuMetrics.kt")
        check(rule, body, 0, "components")
        check(rule, "import androidx.compose.material3.Text as Label\nimport androidx.compose.runtime.Composable\n@Composable fun Row() { Label(\"a\") }", 1)
        check(rule, "import androidx.compose.runtime.Composable\n@Composable fun Row() { androidx.compose.material3.Text(\"a\") }", 1)
        check(rule, "class Binding { class Text(val run: (String) -> Unit) }\nfun bind() = Binding.Text {}", 0)
        check(rule, "fun Text(value: Int) = value\nfun use() = Text(1)", 0)
        check(rule, "fun use() { Text(\"missing\") }", 1, errors = true)
        check(rule, "import androidx.compose.ui.text.TextStyle\nfun style() = TextStyle(fontSize = 12)", 2)
        check(rule, "import androidx.compose.material3.MaterialTheme\nfun family() = MaterialTheme.typography", 1)
        check(rule, "import androidx.compose.material3.MaterialTheme\nfun family() = MaterialTheme.typography", 0, "layout.menu", "MenuMetrics.kt")
        check(rule, "class Local { val typography = 1 }\nfun family() = Local().typography", 0)
    }

    fun testParallelMetrics() {
        val rule = MenuRuleId.ParallelSettingsRowMetrics
        val read = "import io.github.anastaci1a.localica.ui.spec.StockUiSpec\nfun base() = StockUiSpec"
        check(rule, read, 1)
        check(rule, read, 1, "layout.menu", "MenuSlots.kt")
        check(rule, read, 0, "layout.menu", "MenuMetrics.kt")
        check(rule, "import io.github.anastaci1a.localica.ui.spec.StockMenuSpec as Stock\nfun base() = Stock", 1, "pages")
        check(rule, "fun base() = io.github.anastaci1a.localica.ui.spec.StockMenuSpec", 1)
        check(rule, "fun base(StockUiSpec: Int) = StockUiSpec", 0)
        check(rule, "fun base() = LocalUiSpec", 1, errors = true)
        val entry = "import androidx.compose.runtime.Composable\nimport androidx.compose.ui.unit.Dp\n"
        check(rule, entry + "@Composable fun MenuRowSlot(title: String, height: Dp, inset: Dp? = null) {}", 2, "layout.menu", "MenuSlots.kt")
        check(rule, entry + "@Composable fun Bind(style: androidx.compose.ui.text.TextStyle) {}", 1)
        check(rule, entry + "@Composable private fun Leading(height: Dp?) {}\n@Composable fun Surface(surface: @Composable (corner: Dp) -> Unit) {}", 0, "layout.menu", "MenuSlots.kt")
        check(rule, entry + "@Composable fun MenuValueText(width: Dp?) {}", 0, "layout.menu", "MenuMetrics.kt")
        check(rule, entry + "class Dp\n@Composable fun Plain(size: Int) {}\nfun notComposable(size: androidx.compose.ui.unit.Dp) {}", 0)
    }

    fun testSectionSpacing() {
        val rule = MenuRuleId.UnownedSettingsSectionSpacing
        val head = "import androidx.compose.foundation.layout.*\nimport androidx.compose.ui.Modifier\n"
        val body = head + "fun gaps() { Spacer(Modifier); Arrangement.spacedBy(8); Modifier.padding(top = 4); Modifier.padding(vertical = 4); Modifier.padding(4) }"
        check(rule, body, 5)
        check(rule, body, 5, "pages")
        check(rule, body, 5, "layout.menu", "MenuMetrics.kt")
        check(rule, body, 0, "layout.menu", "MenuSlots.kt")
        check(rule, body, 0, "layout.menu", "MenuScaffold.kt")
        check(rule, head + "fun sides() { Modifier.padding(start = 4, end = 4); Modifier.padding(horizontal = 4) }", 0)
        check(rule, "import androidx.compose.foundation.layout.Spacer as Gap\nimport androidx.compose.ui.Modifier\nfun gaps() { Gap(Modifier) }", 1)
        check(rule, "import androidx.compose.ui.Modifier\nfun gaps() { androidx.compose.foundation.layout.Spacer(Modifier) }", 1)
        check(rule, "class Box { fun padding(top: Int) = top }\nfun Spacer(count: Int) = count\nfun gaps() = Box().padding(top = 1) + Spacer(2)", 0)
        check(rule, "fun gaps() { Spacer(1) }", 1, errors = true)
    }

    fun testMarker() {
        val rule = MenuRuleId.UnmeasuredSettingsMarker
        val body = "import androidx.compose.foundation.shape.CircleShape\nimport androidx.compose.ui.graphics.Color\nimport androidx.compose.ui.graphics.drawscope.drawCircle\n" +
            "fun dot() { drawCircle(Color(0xFF00FF00)); CircleShape.hashCode() }"
        check(rule, body, 3)
        check(rule, body, 3, "layout.menu", "MenuMetrics.kt")
        check(rule, body, 0, "layout.menu", "MenuSlots.kt")
        check(rule, "import androidx.compose.ui.graphics.Color as Ink\nfun dot() = Ink(1L)", 1)
        check(rule, "fun dot() = androidx.compose.ui.graphics.Color(1L)", 1)
        check(rule, "class Color(val name: String)\nfun dot(CircleShape: Int) = Color(\"red\").name.length + CircleShape", 0)
        check(rule, "fun dot() { drawCircle(1) }", 1, errors = true)
    }

    fun testSegmentedControl() {
        val rule = MenuRuleId.UnfittedSegmentedControl
        val head = "import io.github.anastaci1a.localica.ui.GlassChoices\nimport io.github.anastaci1a.localica.ui.layout.menu.*\n"
        check(rule, head + "fun row() { GlassChoices(listOf(\"a\"), horizontal = true) }", 1)
        check(rule, head + "fun row() { GlassChoices(listOf(\"a\"), horizontal = false); GlassChoices(listOf(\"a\")) }", 0)
        check(rule, head + "fun row() { MenuChoiceSlot { GlassChoices(listOf(\"a\"), horizontal = true) }; MenuTabStripSlot { GlassChoices(listOf(\"a\"), horizontal = true) } }", 0)
        check(rule, head + "fun MenuTabs() { GlassChoices(listOf(\"a\"), horizontal = true) }", 0)
        check(rule, head + "fun MenuTabs() { GlassChoices(listOf(\"a\"), horizontal = true) }", 1, "pages")
        check(rule, "import io.github.anastaci1a.localica.ui.GlassChoices as Lens\nfun row() { Lens(listOf(\"a\"), horizontal = true) }", 1)
        check(rule, "fun row() { io.github.anastaci1a.localica.ui.GlassChoices(listOf(\"a\"), horizontal = true) }", 1)
        check(rule, "fun GlassChoices(labels: Int, horizontal: Boolean) = labels\nfun row() = GlassChoices(1, horizontal = true)", 0)
        check(rule, "fun row() { GlassChoices(1, horizontal = true) }", 1, errors = true)
        val role = "import androidx.compose.ui.semantics.Role\n"
        check(rule, role + "fun role() = Role.Tab", 1)
        check(rule, role + "fun role() = Role.Button", 0)
        check(rule, role + "fun MenuTabStripSlot() = Role.Tab", 0, "layout.menu", "MenuSlots.kt")
        check(rule, role + "fun MenuTabStripSlot() = Role.Tab", 1, "layout.menu", "MenuScaffold.kt")
        check(rule, "enum class Kind { Tab }\nfun role() = Kind.Tab", 0)
    }

    fun testStatisticGrid() {
        val rule = MenuRuleId.FixedSettingsStatisticGrid
        val body = "fun weight(value: Float) = value\nfun grid(cells: List<String>) { cells.chunked(2); repeat(2) { weight(1f) } }"
        check(rule, body, 2)
        check(rule, body, 2, "layout.menu", "MenuSlots.kt")
        check(rule, "fun weight(value: Float) = value\nfun MenuStatGridSlot(cells: List<String>) { cells.chunked(2); repeat(2) { weight(1f) } }", 0, "layout.menu", "MenuSlots.kt")
        check(rule, "fun MenuStatGridSlot(cells: List<String>) { cells.chunked(2) }", 1, "layout.menu", "MenuScaffold.kt")
        check(rule, "fun grid() { repeat(2) { println(it) } }", 0)
        check(rule, "fun grid(cells: List<String>) { kotlin.collections.listOf(\"a\").chunked(2) }", 1)
        check(rule, "class Bag { fun chunked(size: Int) = size }\nfun grid() = Bag().chunked(2)", 0)
        check(rule, "fun grid() { missing.chunked(2) }", 1, errors = true)
        check(rule, body, 0, "components")
    }

    fun testInternalOptIn() {
        val rule = MenuRuleId.MenuInternalOptIn
        val body = "import io.github.anastaci1a.localica.ui.MenuInternal\n@OptIn(MenuInternal::class) fun host() {}"
        val file = "@file:OptIn(io.github.anastaci1a.localica.ui.MenuInternal::class)\n"
        // Listed and live; any package may be listed, and being unlisted fails in any package.
        check(rule, body, 0)
        check(rule, body, 0, file = "Legacy.kt")
        check(rule, body, 1, file = "Expired.kt")
        check(rule, body, 1, file = "Sibling.kt")
        check(rule, body, 1, "components", "Other.kt")
        check(rule, "import io.github.anastaci1a.localica.ui.MenuInternal as Marker\n@OptIn(Marker::class) fun host() {}", 0)
        check(rule, "@OptIn(io.github.anastaci1a.localica.ui.MenuInternal::class) fun host() {}", 1, file = "Sibling.kt")
        check(rule, "@RequiresOptIn annotation class Other\n@OptIn(Other::class) fun host() {}", 0, file = "Sibling.kt")
        // A missing or unparsable policy is itself a finding: on the opt-in, and on every binder file.
        check(rule, body, 2, policy = null)
        check(rule, "fun plain() {}", 1, policy = null)
        check(rule, "fun plain() {}", 1, policy = "binder | app/src/test/Subject.kt | never | no step line\n")
        check(rule, "fun plain() {}", 0, "components", policy = null)
        check(rule, "fun plain() {}", 0)
    }

    fun testHostPolicySchema() {
        val good = "# current-step: M1\nbinder | app/src/a/Binder.kt | never | binder\nlegacy | app/src/a/Old.kt | M2 | until M2\n"
        val policy = MenuHostPolicy.parse(good)!!
        assertEquals(true, policy.live("/work/app/src/a/Binder.kt"))
        assertEquals(true, policy.live("/work/src/a/Old.kt"))
        assertNull(policy.live("/work/app/src/a/Other.kt"))
        assertNull(policy.live("/work/app/src/a/NotBinder.kt"))
        assertEquals(false, MenuHostPolicy.parse(good.replace("M1", "M2"))!!.live("/work/app/src/a/Old.kt"))
        assertEquals(false, MenuHostPolicy.parse(good.replace("M1", "M5"))!!.live("/work/app/src/a/Old.kt"))
        assertNull(MenuHostPolicy.parse(good.replace("# current-step: M1\n", "")))
        assertNull(MenuHostPolicy.parse(good.replace("M1", "M9")))
        assertNull(MenuHostPolicy.parse(good + "binder | app/src/a/*.kt | never | wildcard\n"))
        assertNull(MenuHostPolicy.parse(good + "binder | app/src/a | never | directory\n"))
        assertNull(MenuHostPolicy.parse(good + "legacy | app/src/a/New.kt | never | legacy lines must expire\n"))
        assertNull(MenuHostPolicy.parse(good + "owner | app/src/a/New.kt | never | unknown kind\n"))
        assertNull(MenuHostPolicy.parse(good + "binder | app/src/a/Binder.kt | never | duplicate\n"))
        assertNull(MenuHostPolicy.parse(good + "binder | app/src/a/New.kt | never\n"))
    }

    private val stubs: List<TestFile> = listOf(
        kotlin("package androidx.compose.runtime\n@Target(AnnotationTarget.FUNCTION, AnnotationTarget.TYPE) annotation class Composable\nfun <T> remember(factory: () -> T): T = factory()"),
        kotlin("package androidx.compose.material3\nfun Text(text: String, fontSize: Int = 0) {}\nobject MaterialTheme { val typography = 0 }"),
        kotlin("package androidx.compose.ui.text\nclass TextStyle(val fontSize: Int = 0)"),
        kotlin("package androidx.compose.ui\nobject Modifier"),
        kotlin("package androidx.compose.foundation.layout\nimport androidx.compose.ui.Modifier\n" +
            "fun Modifier.padding(all: Int = 0, start: Int = 0, top: Int = 0, end: Int = 0, bottom: Int = 0, horizontal: Int = 0, vertical: Int = 0): Modifier = this\n" +
            "fun Spacer(modifier: Modifier) {}\nobject Arrangement { fun spacedBy(space: Int) = space }"),
        kotlin("package androidx.compose.foundation.shape\nval CircleShape = Any()"),
        kotlin("package androidx.compose.ui.graphics\nclass Color(val argb: Long)"),
        kotlin("package androidx.compose.ui.graphics.drawscope\nfun drawCircle(color: Any) {}"),
        kotlin("package androidx.compose.ui.semantics\nenum class Role { Tab, Button }"),
        kotlin("package androidx.compose.ui.unit\nclass Dp"),
        kotlin("package io.github.anastaci1a.localica.ui\n@RequiresOptIn annotation class MenuInternal\nfun GlassChoices(labels: List<String>, horizontal: Boolean = false) {}"),
        kotlin("package io.github.anastaci1a.localica.ui.spec\nval StockUiSpec = 1\nval StockMenuSpec = 2\nval LocalUiSpec = 3"),
        kotlin("package io.github.anastaci1a.localica.ui.layout.menu\nfun MenuChoiceSlot(inline: () -> Unit) {}\nfun MenuTabStripSlot(strip: () -> Unit) {}"),
    )
}
