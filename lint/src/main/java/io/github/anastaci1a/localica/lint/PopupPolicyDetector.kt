package io.github.anastaci1a.localica.lint

import com.android.tools.lint.client.api.UElementHandler
import com.android.tools.lint.detector.api.*
import com.intellij.psi.PsiMember
import org.jetbrains.uast.*

/** Hard rules 11–12: the shared popup owners are exact source paths, not screen conventions. */
internal class PopupPolicyDetector : Detector(), SourceCodeScanner {
    private val popupOwnerPaths by lazy {
        requireNotNull(javaClass.getResourceAsStream("/popup-owners.txt")).bufferedReader().use { it.readLines().map { row -> row.split("=", limit = 2) }.groupBy({ it[0] }, { it[1] }) }
    }
    private val popupRoles by lazy {
        requireNotNull(javaClass.getResourceAsStream("/popup-roles.txt")).bufferedReader().use { it.readLines().toSet() }
    }
    override fun getApplicableUastTypes() = listOf(UCallExpression::class.java,
        USimpleNameReferenceExpression::class.java, UBinaryExpression::class.java, UClass::class.java, UVariable::class.java)

    override fun createUastHandler(context: JavaContext): UElementHandler? {
        val path = context.file.invariantSeparatorsPath
        val relative = "app/src/main/java/" + path.substringAfterLast("/src/main/java/", missingDelimiterValue = path)
        return object : UElementHandler() {
            private fun report(rule: UiRuleId, node: UElement) {
                if (relative !in popupOwnerPaths[rule.name].orEmpty()) context.report(rule.issue, node, context.getLocation(node), rule.policy)
            }

            override fun visitSimpleNameReferenceExpression(node: USimpleNameReferenceExpression) {
                val resolved = node.resolve()
                val name = resolved?.let { (it as? com.intellij.psi.PsiNamedElement)?.name } ?: node.identifier
                if (Regex("(?:get)?Local.*Popup.*Home.*").matches(name)) report(UiRuleId.ScreenPopupHome, node)
                val owner = (resolved as? PsiMember)?.containingClass?.name.orEmpty()
                if (owner in setOf("PopupKind", "PopupMotionKind")) report(UiRuleId.ScreenPopupMotion, node)
            }

            override fun visitCallExpression(node: UCallExpression) {
                val method = node.resolve() ?: return
                val name = method.name
                val owner = method.containingClass?.qualifiedName.orEmpty()
                if (name in setOf("glassPopupHome", "glassPopupProjection")) report(UiRuleId.ScreenPopupHome, node)
                val motionParameters = setOf("motionKind", "popupKind", "popupLaw", "motionLaw", "law", "quiet", "originPresentation", "contactPresentation")
                if (method.parameterList.parameters.withIndex().any { (i, p) ->
                        p.name in motionParameters && node.getArgumentForParameter(i) != null }) report(UiRuleId.ScreenPopupMotion, node)
                if (name in setOf("glassAnchor", "rememberPresence", "rememberFaceExpansion", "PopupActionPill", "ActionIcon") &&
                    method.parameterList.parameters.withIndex().any { (i, p) -> p.type.canonicalText == "boolean" && p.name !in setOf("visible", "shown", "expanded", "menuVisible", "enabled", "glass", "quietInputEnabled") && node.getArgumentForParameter(i) != null }) report(UiRuleId.ScreenPopupMotion, node)
                if (method.isConstructor && method.containingClass?.name == "GlassPopupHome") report(UiRuleId.ScreenPopupHome, node)
                if (method.isConstructor && method.containingClass?.name == "GlassAnchor") report(UiRuleId.ScreenPopupTrigger, node)
                if (method.containingClass?.name == "GlassAnchor" && !method.isConstructor && !name.startsWith("get") && name != "prepareContextPlacement") report(UiRuleId.ScreenPopupTrigger, node)
                if (name in setOf("PopupActionPill", "GlassMenu", "GlassMorphPopup", "GlassDialog", "Popup", "glassAnchor") &&
                    method.parameterList.parameters.withIndex().any { (i, p) -> p.name in setOf("shadowOpacity", "opacity") && node.getArgumentForParameter(i) != null }) report(UiRuleId.ScreenPopupTrigger, node)
                if (name == "NavigationUtilityPill") report(UiRuleId.ScreenPopupTrigger, node)
                if ((method.isConstructor && owner in setOf("android.app.Dialog", "android.widget.PopupWindow", "android.app.AlertDialog.Builder")) || (owner.startsWith("androidx.compose.ui.window.") && name in setOf("Popup", "Dialog")) ||
                    (owner.startsWith("androidx.compose.material") && name in setOf("AlertDialog", "BasicAlertDialog", "DropdownMenu", "ModalBottomSheet"))) {
                    report(UiRuleId.ScreenPopupDrawPath, node)
                }
            }

            override fun visitBinaryExpression(node: UBinaryExpression) {
                if (node.operator.text != "=") return
                val left = (node.leftOperand as? UQualifiedReferenceExpression)?.selector ?: node.leftOperand
                val symbol = (left as? UReferenceExpression)?.resolve() as? PsiMember ?: return
                if (symbol.containingClass?.name == "GlassAnchor") {
                    report(UiRuleId.ScreenPopupTrigger, node)
                }
            }

            override fun visitVariable(node: UVariable) {
                val type = node.type.canonicalText
                if (type.contains("CompositionLocal<") && listOf("GlassPopupHome", "HomeRef", "PopupBodyPresenter").any { type.contains(it) }) report(UiRuleId.ScreenPopupHome, node)
            }

            override fun visitClass(node: UClass) {
                if (context.file.extension == "kt" && node.sourcePsi !is org.jetbrains.kotlin.psi.KtClassOrObject) return
                if ("$relative:" + node.name in popupRoles) return
                if (Regex(".*Popup.*(Home|Anchor|Trigger).*|.*(Home|Anchor|Trigger).*Popup.*|GlassAnchor|HomeRef").matches(node.name.orEmpty()) ||
                    node.javaPsi.supers.any { it.name in setOf("GlassAnchor", "GlassPopupHome", "HomeRef") }) report(UiRuleId.DuplicatePopupRole, node)
            }
        }
    }
}
