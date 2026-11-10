package io.github.anastaci1a.localica.lint

import com.android.tools.lint.client.api.UElementHandler
import com.android.tools.lint.detector.api.*
import com.intellij.psi.PsiMember
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiTypes
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.uast.*
import java.io.File

private const val MENU_UI = "io.github.anastaci1a.localica.ui"
private const val LAYOUT = "$MENU_UI.layout.menu"
private const val BINDER = "$MENU_UI.menu"
private const val PAGES = "$MENU_UI.pages"
private val composeUi = listOf("androidx.compose.ui", "androidx.compose.foundation", "androidx.compose.material3", "androidx.compose.animation")
private val baseReads = setOf("StockUiSpec", "LocalUiSpec", "StockMenuSpec")
private val metricTypes = setOf("Dp", "TextUnit", "TextStyle", "PaddingValues")
private val horizontalPadding = setOf("start", "end", "horizontal", "left", "right")
private val choiceOwners = setOf("MenuChoiceSlot", "MenuTabStripSlot")

/**
 * #757 menu rules: role, admission and scaffold policy in the menu packages. Literal sizes stay with
 * `LiteralUiSpacing` and raw containers with `NonPresetScreenLayout`; the anchor policy stays with
 * `GlassPopupWithoutAnchor`. Each rule names its one owner; a sibling file in the same package is not the owner.
 */
internal enum class MenuRuleId(val policy: String) {
    MenuDeclarationPurity("Declare pages as plain data without Compose UI"),
    UnownedSettingsTypography("Emit menu text through MenuText and the role table"),
    ParallelSettingsRowMetrics("Read menu metrics through the one resolver"),
    UnownedSettingsSectionSpacing("Leave vertical gaps to the menu scaffold and slots"),
    UnmeasuredSettingsMarker("Draw markers through the leading slot and MarkerTone"),
    UnfittedSegmentedControl("Place horizontal choices through the choice and tab strip slots"),
    FixedSettingsStatisticGrid("Lay out statistics through the stat grid slot"),
    MenuInternalOptIn("Opt into MenuInternal only from a file the host policy lists");

    val issue: Issue by lazy {
        Issue.create(name, policy, "$policy. Enforces the Localica menu system (#757).",
            Category.CORRECTNESS, 8, Severity.ERROR, Implementation(MenuPolicyDetector::class.java, Scope.JAVA_FILE_SCOPE))
    }
}

/** The one reader of `app/config/menu-host-policy.txt` on the lint side; the schema is the one `MenuHostPolicyTest` reads on main. */
internal class MenuHostPolicy(val step: String, val entries: List<Entry>) {
    class Entry(val kind: String, val path: String, val expires: String)

    /** Null when no entry lists [file]; otherwise whether that entry is still live at the current step. */
    fun live(file: String): Boolean? {
        val normal = file.replace('\\', '/')
        val entry = entries.firstOrNull { normal.endsWith("/" + it.path) || normal.endsWith("/" + it.path.removePrefix("app/")) } ?: return null
        return entry.expires == "never" || steps.indexOf(entry.expires) > steps.indexOf(step)
    }

    companion object {
        const val PATH = "config/menu-host-policy.txt"
        val steps = (0..7).map { "M$it" }
        private val kinds = setOf("binder", "hosted", "legacy")

        /** Null for an unparsable policy: no step, an unknown field count or kind, a wildcard or directory, a bad expiry, a duplicate. */
        fun parse(text: String): MenuHostPolicy? {
            val step = Regex("""^# current-step: (\w+)$""", RegexOption.MULTILINE).find(text)?.groupValues?.get(1)?.takeIf { it in steps } ?: return null
            val entries = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.map { line ->
                val fields = line.split("|").map { it.trim() }
                if (fields.size != 4 || fields.any { it.isEmpty() }) return null
                val (kind, path, expires) = fields
                if (kind !in kinds || !path.endsWith(".kt") || '*' in path || path.startsWith("/")) return null
                if (if (kind == "legacy") expires !in steps else expires != "never" && expires !in steps) return null
                Entry(kind, path, expires)
            }
            if (entries.map { it.path }.toSet().size != entries.size) return null
            return MenuHostPolicy(step, entries)
        }

        /** Looks in the linted project and its parents, so the app module and a root project both find `app/config`. */
        fun load(project: File): MenuHostPolicy? {
            var dir: File? = project
            repeat(3) {
                val at = dir ?: return null
                listOf(File(at, PATH), File(at, "app/$PATH")).firstOrNull { it.isFile }?.let { return parse(it.readText()) }
                dir = at.parentFile
            }
            return null
        }
    }
}

internal class MenuPolicyDetector : Detector(), SourceCodeScanner {
    override fun getApplicableUastTypes() = listOf(UFile::class.java, UImportStatement::class.java, UCallExpression::class.java,
        USimpleNameReferenceExpression::class.java, UMethod::class.java, UAnnotation::class.java)

    override fun createUastHandler(context: JavaContext): UElementHandler = Analysis(context)

    private class Analysis(val context: JavaContext) : UElementHandler() {
        private val pkg = context.uastFile?.packageName.orEmpty()
        private val file = context.file.name
        private val menu = listOf(LAYOUT, BINDER, PAGES).any { pkg == it || pkg.startsWith("$it.") }
        private val policy by lazy { MenuHostPolicy.load(context.project.dir) }

        private fun owner(vararg files: String) = pkg == LAYOUT && file in files
        private fun report(rule: MenuRuleId, node: UElement, detail: String = rule.policy) = context.report(rule.issue, node, context.getLocation(node), detail)

        /** The declaring class or file facade of a resolved symbol (empty for a local), or null when it cannot be resolved. */
        private fun home(node: UResolvable): String? = node.resolve()?.let { (it as? PsiMember)?.containingClass?.qualifiedName ?: "" }
        /** A library name counts when it resolves into [roots], and also when it cannot be resolved: verification is then incomplete. */
        private fun library(home: String?, vararg roots: String) = home == null || roots.any { home.startsWith("$it.") }
        private fun suffix(home: String?) = if (home == null) " (unresolved; source verification is incomplete)" else ""
        private fun insideCall(node: UElement, names: Set<String>): Boolean =
            generateSequence(node.uastParent) { it.uastParent }.any { it is UCallExpression && (it.methodName ?: it.methodIdentifier?.name) in names }
        private fun insideMethod(node: UElement, name: String): Boolean = generateSequence(node.uastParent) { it.uastParent }.any { it is UMethod && it.name == name }
        private fun argumentNames(node: UCallExpression): List<String?> =
            (node.sourcePsi as? KtCallExpression)?.valueArguments?.map { it.getArgumentName()?.asName?.asString() }.orEmpty()

        override fun visitFile(node: UFile) {
            if (pkg == BINDER && policy == null) report(MenuRuleId.MenuInternalOptIn, node, "The menu host policy file ${MenuHostPolicy.PATH} is missing or unparsable")
        }

        override fun visitImportStatement(node: UImportStatement) {
            if (pkg != PAGES && !pkg.startsWith("$PAGES.")) return
            val name = node.importReference?.asSourceString().orEmpty()
            if (composeUi.any { name == it || name.startsWith("$it.") }) report(MenuRuleId.MenuDeclarationPurity, node, "A page declaration imports $name")
        }

        override fun visitMethod(node: UMethod) {
            val composable = node.uAnnotations.any { it.qualifiedName == "androidx.compose.runtime.Composable" || it.qualifiedName == null && it.sourcePsi?.text?.endsWith("Composable") == true }
            if (!composable) return
            if ((pkg == PAGES || pkg.startsWith("$PAGES.")) && node.returnType == PsiTypes.voidType()) {
                report(MenuRuleId.MenuDeclarationPurity, node, "A page declaration has a composable that emits: ${node.name}")
            }
            // Binder and slot entry points take roles and content, never a size, padding or style.
            val entry = (pkg == BINDER || pkg == LAYOUT && file != "MenuMetrics.kt") && !node.hasModifierProperty("private")
            if (entry) for (parameter in node.uastParameters) {
                val type = (parameter.sourcePsi as? KtParameter)?.typeReference?.text?.removeSuffix("?")?.substringAfterLast('.') ?: continue
                if (type in metricTypes) report(MenuRuleId.ParallelSettingsRowMetrics, parameter, "${node.name} takes a $type parameter")
            }
        }

        override fun visitAnnotation(node: UAnnotation) {
            val text = node.sourcePsi?.text.orEmpty()
            if (!Regex("""\bOptIn\b""").containsMatchIn(text) || !Regex("""\bMenuInternal\b""").containsMatchIn(text)) return
            val policy = policy ?: return report(MenuRuleId.MenuInternalOptIn, node, "The menu host policy file ${MenuHostPolicy.PATH} is missing or unparsable")
            when (policy.live(context.file.path)) {
                null -> report(MenuRuleId.MenuInternalOptIn, node)
                false -> report(MenuRuleId.MenuInternalOptIn, node, "The host policy line for this file expired; the policy is at ${policy.step}")
                true -> {}
            }
        }

        override fun visitCallExpression(node: UCallExpression) {
            if (!menu) return
            // The declared name, so an import alias cannot hide a call; a constructor is named by its class.
            val resolved = node.resolve()
            val name = (if (node.kind == UastCallKind.CONSTRUCTOR_CALL || resolved?.isConstructor == true) resolved?.containingClass?.name ?: node.classReference?.resolvedName else resolved?.name)
                ?: node.methodName ?: node.methodIdentifier?.name ?: node.classReference?.resolvedName ?: return
            val names = argumentNames(node)
            if ("fontSize" in names && !owner("MenuMetrics.kt")) report(MenuRuleId.UnownedSettingsTypography, node, "fontSize outside the role table")
            when (name) {
                "Text", "TextStyle" -> home(node).let { if (library(it, "androidx.compose") && !owner("MenuMetrics.kt")) report(MenuRuleId.UnownedSettingsTypography, node, "$name outside MenuText" + suffix(it)) }
                "Spacer", "spacedBy" -> home(node).let { if (library(it, "androidx.compose") && !owner("MenuScaffold.kt", "MenuSlots.kt")) report(MenuRuleId.UnownedSettingsSectionSpacing, node, "$name outside the scaffold and slots" + suffix(it)) }
                "padding" -> home(node).let {
                    val vertical = names.isEmpty() || names.any { it == null || it !in horizontalPadding }
                    if (vertical && library(it, "androidx.compose") && !owner("MenuScaffold.kt", "MenuSlots.kt")) report(MenuRuleId.UnownedSettingsSectionSpacing, node, "vertical padding outside the scaffold and slots" + suffix(it))
                }
                "drawCircle", "Color" -> home(node).let { if (library(it, "androidx.compose") && !owner("MenuSlots.kt")) report(MenuRuleId.UnmeasuredSettingsMarker, node, "$name outside the leading slot" + suffix(it)) }
                "GlassChoices" -> home(node).let {
                    val horizontal = (node.sourcePsi as? KtCallExpression)?.valueArguments.orEmpty().any { arg -> arg.getArgumentName()?.asName?.asString() == "horizontal" && arg.getArgumentExpression()?.text != "false" }
                    val owned = insideCall(node, choiceOwners) || pkg == BINDER && insideMethod(node, "MenuTabs")
                    if (horizontal && (it == null || it.substringBeforeLast('.', "") == MENU_UI) && !owned) report(MenuRuleId.UnfittedSegmentedControl, node, "A horizontal choice lens outside the choice and tab strip slots" + suffix(it))
                }
                "chunked", "repeat" -> home(node).let {
                    val grid = name == "chunked" || node.sourcePsi?.text.orEmpty().contains("weight(")
                    if (grid && library(it, "kotlin") && !(owner("MenuSlots.kt") && insideMethod(node, "MenuStatGridSlot"))) report(MenuRuleId.FixedSettingsStatisticGrid, node, "$name outside the stat grid slot" + suffix(it))
                }
            }
        }

        override fun visitSimpleNameReferenceExpression(node: USimpleNameReferenceExpression) {
            if (!menu || generateSequence(node.uastParent) { it.uastParent }.any { it is UImportStatement }) return
            val receiver = (node.uastParent as? UQualifiedReferenceExpression)?.takeIf { it.selector == node }?.receiver?.asSourceString()?.substringAfterLast('.')
            // The declared name, so an import alias cannot hide a read.
            val declared = (node.resolve() as? PsiNamedElement)?.name?.let { if (it.startsWith("get") && it.length > 3) it.substring(3) else it } ?: node.identifier
            when (if (declared in baseReads || declared == "CircleShape") declared else node.identifier) {
                in baseReads -> home(node).let { if (library(it, MENU_UI) && !owner("MenuMetrics.kt")) report(MenuRuleId.ParallelSettingsRowMetrics, node, "$declared read outside the resolver" + suffix(it)) }
                "CircleShape" -> home(node).let { if (library(it, "androidx.compose") && !owner("MenuSlots.kt")) report(MenuRuleId.UnmeasuredSettingsMarker, node, "CircleShape outside the leading slot" + suffix(it)) }
                "typography" -> if (receiver == "MaterialTheme" && !owner("MenuMetrics.kt")) report(MenuRuleId.UnownedSettingsTypography, node, "MaterialTheme.typography outside the role table")
                "Tab" -> if (receiver == "Role" && !(owner("MenuSlots.kt") && insideMethod(node, "MenuTabStripSlot")) && !(pkg == BINDER && insideMethod(node, "MenuTabs"))) {
                    report(MenuRuleId.UnfittedSegmentedControl, node, "Role.Tab outside the tab strip")
                }
            }
        }
    }
}
