package io.github.anastaci1a.localica.lint

import com.android.tools.lint.client.api.UElementHandler
import com.android.tools.lint.detector.api.*
import com.intellij.psi.PsiMember
import org.jetbrains.uast.*

/** Hard rules 11–12: the shared popup owners are exact source paths, not screen conventions. */
internal class PopupPolicyDetector : Detector(), SourceCodeScanner {
    private data class Ownership(val rule: UiRuleId, val capability: String = rule.name)
    private val popupOwnerPaths by lazy {
        requireNotNull(javaClass.getResourceAsStream("/popup-owners.txt")).bufferedReader().use { it.readLines().map { row -> row.split("=", limit = 2) }.groupBy({ it[0] }, { it[1] }) }
    }
    private val popupHosts by lazy {
        requireNotNull(javaClass.getResourceAsStream("/popup-hosts.txt")).bufferedReader().use { it.readLines().associate { row -> val parts = row.split("="); parts[0] to parts[1].split(",").map(String::toInt) } }
    }
    private val popupRoles by lazy {
        requireNotNull(javaClass.getResourceAsStream("/popup-roles.txt")).bufferedReader().use { it.readLines().toSet() }
    }
    // #854 review: each shared popup/motion API's whole formal list and its motion-selecting formals, one table with the Node gate.
    private class PinnedApi(val name: String, val owner: String, val key: String, val parameters: Set<String>, val motion: Set<String>)
    private val popupApis by lazy {
        requireNotNull(javaClass.getResourceAsStream("/popup-api.txt")).bufferedReader().use { it.readLines().filter(String::isNotBlank).map { row ->
            val cells = row.split("|")
            PinnedApi(cells[0], cells[1].substringBeforeLast('.') + "." + cells[2].substringAfterLast('/').removeSuffix(".kt") + "Kt", cells[3],
                cells[4].split(",").toSet(), cells[5].split(",").filter(String::isNotEmpty).toSet())
        } }
    }
    // #854 round 3 (R2-1): shared entry points classified as not popup/motion APIs (name|file|reason).
    private val popupExclusions by lazy {
        requireNotNull(javaClass.getResourceAsStream("/popup-api-exclusions.txt")).bufferedReader().use { it.readLines().filter(String::isNotBlank).map { row -> row.substringBefore('|') }.toSet() }
    }
    private val ruleOwnerFiles by lazy { listOf("ScreenPopupHome", "ScreenPopupMotion", "ScreenPopupTrigger", "ScreenPopupDrawPath").flatMap { popupOwnerPaths[it].orEmpty() }.toSet() }
    private fun sharedPopupFile(path: String) = path in ruleOwnerFiles || Regex("/localica/ui/(components|engine)/").containsMatchIn(path)
    // #862: the window's one MotionDurationScale, its key and the app's WindowMotionScale (#854 round 2, G1); only InterfaceMotion.kt
    // provides or names them, beyond an index read.
    private val windowScale = "io.github.anastaci1a.localica.ui.WindowMotionScale"
    private val motionScaleTypes = setOf("androidx.compose.ui.MotionDurationScale", "androidx.compose.ui.MotionDurationScale.Key", windowScale)
    // #854 round 2 (G2): a shared-content handle exposes only its slots to screens.
    private val sharedContent = "io.github.anastaci1a.localica.ui.components.PopupSharedContent"
    private val sharedSlots = setOf("Compact", "Expanded", "CompactCopy", "ExpandedCopy")
    // #854: the popup motion kinds. Only the motion owners and the spec that declares them (PopupKindModel) may name them.
    private val popupKinds = setOf("PopupKind", "PopupMotionKind")
    // Explicit rule-11 debt: exact provider expression and maximum occurrence count.
    // Adoption is tracked separately; these are not lint baselines or owner grants.
    private val popupRatchet by lazy {
        requireNotNull(javaClass.getResourceAsStream("/popup-ratchet.txt")).bufferedReader().use { it.readLines().map { row -> row.split("|") } }
    }
    override fun getApplicableUastTypes() = listOf(UCallExpression::class.java,
        USimpleNameReferenceExpression::class.java, UBinaryExpression::class.java, UPrefixExpression::class.java, UObjectLiteralExpression::class.java,
        UPostfixExpression::class.java, UCallableReferenceExpression::class.java, UClassLiteralExpression::class.java, UClass::class.java, UVariable::class.java)

    override fun createUastHandler(context: JavaContext): UElementHandler? {
        val path = context.file.invariantSeparatorsPath
        // A matching package path in another Gradle module is never an owner.
        val relative = if (context.project.name == "app") "app/src/main/java/" + path.substringAfterLast("/src/main/java/", missingDelimiterValue = path) else path
        return object : UElementHandler() {
            private val providerSites = mutableSetOf<com.intellij.psi.PsiElement>()
            private val ratchetSites = mutableMapOf<List<String>, MutableSet<com.intellij.psi.PsiElement>>()
            private val hostCalls = mutableMapOf<String, Int>()
            private fun hostCall(name: String, node: UElement) {
                val index = if (name == "rememberGlassPopupHome") 0 else 1
                val count = (hostCalls[name] ?: 0) + 1
                hostCalls[name] = count
                if (count > (popupHosts[relative]?.get(index) ?: 0)) report(UiRuleId.ScreenPopupHome, node)
            }
            private val reported = mutableSetOf<Pair<UiRuleId, UElement>>()
            private fun report(rule: UiRuleId, node: UElement) {
                if (relative !in popupOwnerPaths[rule.name].orEmpty() && reported.add(rule to node)) context.report(rule.issue, node, context.getLocation(node), rule.policy)
            }

            private fun report(ownership: Ownership, node: UElement) {
                if (relative !in popupOwnerPaths[ownership.capability].orEmpty() && reported.add(ownership.rule to node))
                    context.report(ownership.rule.issue, node, context.getLocation(node), ownership.rule.policy)
            }

            private fun memberRule(symbol: PsiMember?): Ownership? = when ((symbol as? com.intellij.psi.PsiClass)?.qualifiedName ?: symbol?.containingClass?.qualifiedName) {
                "io.github.anastaci1a.localica.ui.GlassAnchor" -> Ownership(UiRuleId.ScreenPopupTrigger)
                "io.github.anastaci1a.localica.ui.GlassPopupHome", "io.github.anastaci1a.localica.ui.GlassPopupFlight" -> Ownership(UiRuleId.ScreenPopupHome)
                "io.github.anastaci1a.localica.ui.GlassContact", "io.github.anastaci1a.localica.ui.GlassExpansion",
                "io.github.anastaci1a.localica.ui.GlassBodyState", "io.github.anastaci1a.localica.ui.GlassMomentum" ->
                    Ownership(UiRuleId.ScreenPopupTrigger, "GlassContactMutation")
                else -> null
            }

            // Follow collection handles and local aliases back to their resolved
            // owner; spelling a local 'home' or 'flights' is not authority.
            private fun ownedReceiver(expression: UExpression?, seen: MutableSet<UElement> = mutableSetOf()): Ownership? {
                if (expression == null || !seen.add(expression)) return null
                val type = expression.getExpressionType() as? com.intellij.psi.PsiClassType
                if (type?.resolve()?.qualifiedName == "androidx.compose.runtime.MutableState" &&
                    type.parameters.singleOrNull()?.canonicalText == "io.github.anastaci1a.localica.ui.GlassShape")
                    return Ownership(UiRuleId.ScreenPopupTrigger, "GlassContactMutation")
                memberRule((expression.getExpressionType() as? com.intellij.psi.PsiClassType)?.resolve())?.let { return it }
                val reference = when (expression) {
                    is UQualifiedReferenceExpression -> expression.selector
                    else -> expression
                }
                val resolved = (reference as? UResolvable)?.resolve()
                memberRule(resolved as? PsiMember)?.let { return it }
                val initializer = (resolved as? com.intellij.psi.PsiVariable)?.toUElementOfType<UVariable>()?.uastInitializer
                return ownedReceiver(initializer, seen) ?: when (expression) {
                    is UQualifiedReferenceExpression -> ownedReceiver(expression.receiver, seen)
                    is UCallExpression -> ownedReceiver(expression.receiver, seen)
                    else -> null
                }
            }

            private fun namedRole(symbol: com.intellij.psi.PsiElement?): String? {
                val qualified = when (symbol) {
                    is com.intellij.psi.PsiClass -> symbol.qualifiedName
                    is PsiMember -> symbol.containingClass?.qualifiedName?.let {
                        if (it.endsWith(".TriggerRole") || it.endsWith(".HomeKind") || it.endsWith(".PopupVariant")) it + "." + symbol.name else it
                    }
                    else -> null
                } ?: return null
                val prefix = "io.github.anastaci1a.localica.ui.components."
                return qualified.removePrefix(prefix).takeIf { qualified.startsWith(prefix) &&
                    it.substringBefore('.') in setOf("TriggerRole", "HomeKind", "PopupVariant") && it.contains('.') }
            }

            private fun reportRole(symbol: com.intellij.psi.PsiElement?, node: UElement) {
                val role = namedRole(symbol) ?: return
                if (relative !in popupOwnerPaths[role].orEmpty()) context.report(UiRuleId.ScreenPopupMotion.issue, node, context.getLocation(node), "Named popup role $role is restricted to its pinned callers")
            }

            private fun surfaceWriter(method: com.intellij.psi.PsiMethod) =
                method.containingClass?.qualifiedName == "io.github.anastaci1a.localica.ui.GlassMorphKt" &&
                    method.name == "glassSurfaceAnchor" && method.parameterList.parameters.any { it.name == "source" }

            private fun toastView(symbol: PsiMember): Boolean =
                symbol.name in setOf("view", "setView", "getView") && symbol.containingClass?.qualifiedName == "android.widget.Toast"

            private fun registration(method: com.intellij.psi.PsiMethod) = method.name == "glassAnchor" &&
                method.parameterList.parameters.any { it.type.canonicalText.contains("TriggerRole.MaterialSurface") || it.type.canonicalText.contains("TriggerRole.RetainedSurface") }

            private fun reportRegistration(method: com.intellij.psi.PsiMethod, node: UElement) {
                if (surfaceWriter(method)) report(Ownership(UiRuleId.ScreenPopupTrigger, "SurfaceAnchorWriter"), node)
                if (registration(method) && relative !in popupOwnerPaths["ShadowRegistration"].orEmpty()) context.report(UiRuleId.ScreenPopupTrigger.issue, node, context.getLocation(node), "Only GlassShadow registers popup surface nodes")
            }

            private fun nativeClass(klass: com.intellij.psi.PsiClass): Boolean =
                setOf("android.app.Dialog", "android.app.AlertDialog", "androidx.appcompat.app.AlertDialog", "androidx.activity.ComponentDialog", "android.app.DialogFragment", "androidx.fragment.app.DialogFragment", "android.widget.PopupWindow", "android.widget.PopupMenu", "android.widget.ListPopupWindow", "android.app.AlertDialog.Builder", "androidx.appcompat.app.AlertDialog.Builder", "androidx.appcompat.widget.PopupMenu", "androidx.appcompat.widget.ListPopupWindow").any {
                    klass.qualifiedName == it || context.evaluator.extendsClass(klass, it, false)
                }

            private fun windowManager(method: com.intellij.psi.PsiMethod, receiver: UExpression?): Boolean {
                if (method.name != "addView") return false
                val type = (receiver?.getExpressionType() as? com.intellij.psi.PsiClassType)?.resolve()
                    ?: (receiver as? UResolvable)?.resolve() as? com.intellij.psi.PsiClass
                val owner = method.containingClass
                return listOfNotNull(type, owner).any { it.qualifiedName == "android.view.WindowManager" ||
                    context.evaluator.extendsClass(it, "android.view.WindowManager", false) ||
                    context.evaluator.implementsInterface(it, "android.view.WindowManager", false) }
            }

            private fun pinnedApi(method: com.intellij.psi.PsiMethod): PinnedApi? =
                popupApis.firstOrNull { it.name == method.name && it.owner == method.containingClass?.qualifiedName }

            // #862 / #854 review: the app's own sources may not provide a MotionDurationScale outside the window owner (InterfaceMotion.kt).
            private fun reportScale(node: UElement) {
                if (relative.startsWith("app/src/main/java/io/github/anastaci1a/")) report(Ownership(UiRuleId.ScreenPopupMotion, "MotionDurationScale"), node)
            }

            // An implementation or object of MotionDurationScale rescales every animation run in its context.
            private fun motionScale(klass: UClass) {
                if (klass.qualifiedName !in motionScaleTypes && context.evaluator.implementsInterface(klass.javaPsi, "androidx.compose.ui.MotionDurationScale", false)) reportScale(klass)
            }

            private fun scopedWindowManager(method: com.intellij.psi.PsiMethod, node: UCallExpression): Boolean =
                windowManager(method, node.receiver) || node.receiver == null && generateSequence(node.uastParent) { it.uastParent }
                    .filterIsInstance<UCallExpression>().any { scope ->
                        val receiver = when (scope.resolve()?.name) {
                            "with" -> scope.valueArguments.firstOrNull()
                            "apply", "run" -> scope.receiver
                            else -> null
                        }
                        windowManager(method, receiver)
                    }

            private fun argumentPins(method: com.intellij.psi.PsiMethod, node: UCallExpression) {
                val api = pinnedApi(method) ?: return
                method.parameterList.parameters.forEachIndexed { index, formal ->
                    // An extension's receiver is not an argument ($this$… / <this> in light classes).
                    if (formal.name.startsWith("$") || formal.name.startsWith("<") || node.getArgumentForParameter(index) == null) return@forEachIndexed
                    // #854 review (Astra 1): an argument for a formal outside the pinned surface (a new timing input added to the shared
                    // declaration) is red until popup-api.txt, and its review, admit it.
                    if (formal.name !in api.parameters) {
                        if (relative !in popupOwnerPaths[UiRuleId.ScreenPopupMotion.name].orEmpty())
                            context.report(UiRuleId.ScreenPopupMotion.issue, node, context.getLocation(node), "Argument "+formal.name+" is not part of "+api.name+"'s pinned surface")
                    } else if (formal.name in api.motion && relative !in popupOwnerPaths[api.key + formal.name].orEmpty())
                        context.report(UiRuleId.ScreenPopupMotion.issue, node, context.getLocation(node), "Popup argument "+formal.name+" is restricted to its pinned callers")
                }
            }

            override fun visitClassLiteralExpression(node: UClassLiteralExpression) {
                val klass = (node.type as? com.intellij.psi.PsiClassType)?.resolve() ?: return
                if (nativeClass(klass) || klass.qualifiedName == "android.view.WindowManager") report(UiRuleId.ScreenPopupDrawPath, node)
                memberRule(klass)?.let { report(it, node) }
                if (klass.qualifiedName in setOf("io.github.anastaci1a.localica.ui.components.HomeRef",
                        "io.github.anastaci1a.localica.ui.components.PopupState", "io.github.anastaci1a.localica.ui.components.PopupScope"))
                    report(UiRuleId.DuplicatePopupRole, node)
                reportRole(klass, node)
            }

            private fun privateEntry(method: com.intellij.psi.PsiMethod, node: UElement) {
                val name = method.name
                val owner = method.containingClass?.qualifiedName.orEmpty()
                val ingress = when {
                    owner == "io.github.anastaci1a.localica.ui.LocalicaUiRootKt" && name == "LocalicaUiRoot" -> name
                    owner == "io.github.anastaci1a.localica.ui.layout.LocalicaEngineRootKt" && name == "ProvideLocalicaEngineContext" -> name
                    owner == "io.github.anastaci1a.localica.ui.InterfaceMotionKt" && name in setOf("installLocalicaWindowMotion", "bindRespectSystemAnimationScale") -> name
                    else -> null
                }
                if (ingress != null) report(Ownership(UiRuleId.ScreenPopupMotion, "MotionIngress." + ingress), node)
                if (name in setOf("rememberPopupMotion", "rememberPopupWindowMotion", "rememberExpandCollapse") && owner.startsWith("io.github.anastaci1a.localica.ui.engine.")) report(UiRuleId.ScreenPopupMotion, node)
                if (name == "drawPopupProjection" && owner.startsWith("io.github.anastaci1a.localica.ui.engine.") && relative !in popupOwnerPaths["PopupProjection"].orEmpty()) report(UiRuleId.ScreenPopupDrawPath, node)
                if (toastView(method) && name == "setView") report(UiRuleId.ScreenPopupDrawPath, node)
                val klass = method.containingClass
                val nativeConstructor = method.isConstructor && klass != null && nativeClass(klass)
                val composeWindow = owner.startsWith("androidx.compose.ui.window.") && name in setOf("Popup", "Dialog")
                val materialWindow = owner.startsWith("androidx.compose.material") && name in setOf("AlertDialog", "BasicAlertDialog", "DropdownMenu", "ModalBottomSheet", "DatePickerDialog", "TooltipBox", "ExposedDropdownMenu", "ExposedDropdownMenuBox", "SearchBar")
                if (nativeConstructor || klass != null && nativeClass(klass) && name in setOf("show", "showNow", "showAsDropDown", "showAtLocation") || composeWindow || materialWindow) report(UiRuleId.ScreenPopupDrawPath, node)
            }

            // Intent is the exact stock declaration and formal parameter, never a
            // global parameter-name exemption a new motion API can borrow.
            private fun booleanIntent(method: com.intellij.psi.PsiMethod, index: Int): Boolean {
                val file = method.navigationElement.containingFile?.virtualFile?.path.orEmpty()
                val formal = method.parameterList.parameters[index]
                val declaration = when (method.name) {
                    "rememberPresence" -> "components/Presence.kt" to "shown"
                    "rememberFaceExpansion" -> "components/MorphFaces.kt" to "expanded"
                    "PopupActionPill" -> "GlassMorph.kt" to "menuVisible"
                    "ActionIcon" -> "InterfaceComponents.kt" to formal.name.takeIf { it in setOf("enabled", "glass", "quietInputEnabled") }
                    else -> return false
                }
                return file.endsWith("/app/src/main/java/io/github/anastaci1a/localica/ui/" + declaration.first) &&
                    formal.name == declaration.second && formal.type.canonicalText == "boolean"
            }

            override fun visitCallableReferenceExpression(node: UCallableReferenceExpression) {
                reportRole(node.resolve(), node)
                (node.resolve() as? com.intellij.psi.PsiClass)?.takeIf(::nativeClass)?.let { report(UiRuleId.ScreenPopupDrawPath, node) }
                (node.resolve() as? com.intellij.psi.PsiMethod)?.let {
                    reportRegistration(it, node)
                    if (pinnedApi(it) != null) report(UiRuleId.ScreenPopupMotion, node)
                    if (it.containingClass?.qualifiedName in motionScaleTypes) reportScale(node)
                    privateEntry(it, node)
                    if (windowManager(it, node.qualifierExpression)) report(UiRuleId.ScreenPopupDrawPath, node)
                    if (it.name in setOf("GlassPopupHome", "rememberGlassPopupHome") && !it.isConstructor) report(UiRuleId.ScreenPopupHome, node)
                    if (it.name in setOf("glassPopupHome", "glassPopupProjection")) report(UiRuleId.ScreenPopupHome, node)
                }
                (memberRule(node.resolve() as? PsiMember) ?: ownedReceiver(node.qualifierExpression))?.let { report(it, node) }
            }

            private fun motionLocal(symbol: com.intellij.psi.PsiElement?, seen: MutableSet<com.intellij.psi.PsiElement> = mutableSetOf()): String? {
                if (symbol == null || !seen.add(symbol)) return null
                val property = symbol.navigationElement as? org.jetbrains.kotlin.psi.KtProperty
                val fqn = property?.fqName?.asString()
                if (fqn in setOf("io.github.anastaci1a.localica.ui.LocalMotionSpeed",
                        "io.github.anastaci1a.localica.ui.LocalReducedMotion", "io.github.anastaci1a.localica.ui.spec.LocalUiSpec",
                        "io.github.anastaci1a.localica.ui.engine.LocalEngineContext",
                        "io.github.anastaci1a.localica.ui.engine.LocalUiClock",
                        "io.github.anastaci1a.localica.ui.engine.LocalClockOwner",
                        // #854/#862: the popup kind a surface publishes to its faces and the respected platform scale.
                        "io.github.anastaci1a.localica.ui.engine.LocalPopupKind", "io.github.anastaci1a.localica.ui.LocalRespectSystemAnimationScale")) return fqn
                val initializer = (symbol as? com.intellij.psi.PsiVariable)?.toUElementOfType<UVariable>()?.uastInitializer
                    ?: property?.initializer?.toUElementOfType<UExpression>()
                return motionLocal((initializer as? UResolvable)?.resolve(), seen)
            }

            override fun visitSimpleNameReferenceExpression(node: USimpleNameReferenceExpression) {
                val resolved = node.resolve()
                reportRole(resolved, node)
                // Resolve the declaration, including imported and local aliases. Infix
                // provider expressions do not consistently expose a UAST call receiver.
                val local = motionLocal(resolved)
                if (local != null) {
                    val operations = setOf("provides", "providesDefault", "providesComputed")
                    val provider = generateSequence(node.sourcePsi?.parent) { it.parent }
                        .takeWhile { it !is org.jetbrains.kotlin.psi.KtDeclaration }
                        .firstOrNull {
                            val receiver = when (it) {
                                is org.jetbrains.kotlin.psi.KtBinaryExpression -> it.left.takeIf { _ ->
                                    it.operationReference.getReferencedName() in operations }
                                is org.jetbrains.kotlin.psi.KtDotQualifiedExpression -> it.receiverExpression.takeIf { _ ->
                                    (it.selectorExpression as? org.jetbrains.kotlin.psi.KtCallExpression)?.calleeExpression?.text in operations }
                                else -> null
                            }
                            receiver?.textRange?.contains(node.sourcePsi?.textRange ?: return@firstOrNull false) == true
                        }
                    if (provider != null && providerSites.add(provider)) {
                        val debt = popupRatchet.singleOrNull { it[1] == relative && it[2] == local &&
                            it[3] == provider.text.replace(Regex("\\s+"), " ").trim() }
                        val admitted = debt != null && ratchetSites.getOrPut(debt) { mutableSetOf() }.apply { add(provider) }.size <= debt[4].toInt()
                        if (!admitted) report(Ownership(UiRuleId.ScreenPopupMotion, "MotionProvider." + local.substringAfterLast('.')), node)
                    }
                }
                // #862: naming the scale type or its key is an override, except the index read context[MotionDurationScale] and imports.
                val scaleSymbol = when (resolved) {
                    is com.intellij.psi.PsiClass -> resolved.qualifiedName
                    is PsiMember -> if (resolved.containingClass?.qualifiedName == windowScale) windowScale
                        else (resolved as? com.intellij.psi.PsiField)?.takeIf { it.name == "Key" }?.containingClass?.qualifiedName?.let { "$it.Key" }
                    else -> null
                }
                if (scaleSymbol in motionScaleTypes && (node.uastParent as? UArrayAccessExpression)?.indices?.any { it.sourcePsi == node.sourcePsi } != true &&
                    generateSequence(node.uastParent) { it.uastParent }.none { it is UImportStatement }) reportScale(node)
                if ((resolved as? PsiMember)?.containingClass?.qualifiedName == sharedContent && resolved !is com.intellij.psi.PsiClass &&
                    (resolved as? com.intellij.psi.PsiNamedElement)?.name !in sharedSlots) report(UiRuleId.ScreenPopupMotion, node)
                val name = resolved?.let { (it as? com.intellij.psi.PsiNamedElement)?.name } ?: node.identifier
                if (Regex("(?:get)?Local.*Popup.*Home.*").matches(name)) report(UiRuleId.ScreenPopupHome, node)
                if (name in setOf("LocalPopupBodyPresenter", "getLocalPopupBodyPresenter") &&
                    (resolved as? PsiMember)?.containingClass?.qualifiedName == "io.github.anastaci1a.localica.ui.components.PopupKt") report(UiRuleId.ScreenPopupHome, node)
                if (name in setOf("triggerRole", "getTriggerRole") &&
                    (resolved as? PsiMember)?.containingClass?.qualifiedName == "io.github.anastaci1a.localica.ui.GlassAnchor")
                    report(UiRuleId.ScreenPopupTrigger, node)
                val owner = (resolved as? PsiMember)?.containingClass?.name.orEmpty()
                // #854: the motion spec declares the kinds and the stock model's kind table; only it names kinds besides the motion owners.
                // A member, or the class itself as a callable-reference receiver, both name a kind.
                if ((owner in popupKinds || (resolved as? com.intellij.psi.PsiClass)?.name in popupKinds) &&
                    relative !in popupOwnerPaths["PopupKindModel"].orEmpty()) report(UiRuleId.ScreenPopupMotion, node)
            }

            override fun visitCallExpression(node: UCallExpression) {
                val method = node.resolve() ?: return
                val name = method.name
                reportRegistration(method, node)
                privateEntry(method, node)
                argumentPins(method, node)
                if (scopedWindowManager(method, node)) report(UiRuleId.ScreenPopupDrawPath, node)
                // #854: a type argument names a kind too (enumValueOf<PopupKind>, emptyList<PopupKind>).
                if (node.typeArguments.any { (it as? com.intellij.psi.PsiClassType)?.className in popupKinds } &&
                    relative !in popupOwnerPaths["PopupKindModel"].orEmpty()) report(UiRuleId.ScreenPopupMotion, node)
                if (node.typeArguments.any { (it as? com.intellij.psi.PsiClassType)?.resolve()?.qualifiedName in motionScaleTypes }) reportScale(node)
                // Round 2 (G1): constructing a WindowMotionScale or calling its members.
                if (method.containingClass?.qualifiedName == windowScale) reportScale(node)
                // Round 2 (G2): members, extensions and scope functions on a shared-content handle, except its slots.
                if (node.receiverType?.canonicalText?.removeSuffix("?") == sharedContent && name !in sharedSlots) report(UiRuleId.ScreenPopupMotion, node)
                // Round 4 (F8-3): an implicit receiver (with(shared) { x() }, shared.run { x() }) still resolves to the handle's own
                // member or extension, whatever UAST reports as the call's receiver type.
                if (name !in sharedSlots && !method.isConstructor && (method.containingClass?.qualifiedName == sharedContent ||
                        method.parameterList.parameters.firstOrNull()?.let { (it.name.startsWith("$") || it.name.startsWith("<")) && it.type.canonicalText == sharedContent } == true))
                    report(UiRuleId.ScreenPopupMotion, node)
                val owner = method.containingClass?.qualifiedName.orEmpty()
                if (name in setOf("GlassPopupHome", "rememberGlassPopupHome") && !method.isConstructor) hostCall(name, node)
                if (name in setOf("glassPopupHome", "glassPopupProjection")) report(UiRuleId.ScreenPopupHome, node)
                val motionParameters = setOf("motionKind", "popupKind", "popupLaw", "motionLaw", "law", "quiet", "originPresentation", "contactPresentation")
                if (method.parameterList.parameters.withIndex().any { (i, p) ->
                        p.name in motionParameters && node.getArgumentForParameter(i) != null }) report(UiRuleId.ScreenPopupMotion, node)
                if (name in setOf("glassAnchor", "rememberPresence", "rememberFaceExpansion", "PopupActionPill", "ActionIcon") &&
                    method.parameterList.parameters.withIndex().any { (i, p) -> p.type.canonicalText in setOf("boolean", "java.lang.Boolean") && !booleanIntent(method, i) && node.getArgumentForParameter(i) != null }) report(UiRuleId.ScreenPopupMotion, node)
                if (method.isConstructor && method.containingClass?.name == "GlassPopupHome") report(UiRuleId.ScreenPopupHome, node)
                if (method.isConstructor && method.containingClass?.name == "GlassAnchor") report(UiRuleId.ScreenPopupTrigger, node)
                if (method.containingClass?.name == "GlassAnchor" && !method.isConstructor && !name.startsWith("get") && name != "prepareContextPlacement") report(UiRuleId.ScreenPopupTrigger, node)
                if (method.containingClass?.name in setOf("GlassPopupHome", "GlassPopupFlight") && !method.isConstructor && !name.startsWith("get")) report(UiRuleId.ScreenPopupHome, node)
                memberRule(method)?.takeIf { it.capability == "GlassContactMutation" && !method.isConstructor &&
                    !name.startsWith("get") && name !in setOf("equals", "hashCode", "toString", "component1", "component2") }?.let { report(it, node) }
                if (name in setOf("add", "addAll", "clear", "remove", "removeAll", "removeAt", "removeIf", "retainAll", "set", "put", "putAll", "replace", "replaceAll", "sort", "sortWith", "sortBy", "sortDescending", "reverse", "shuffle", "fill")) {
                    ownedReceiver(node.receiver)?.let { report(it, node) }
                }
                if (!method.isConstructor && !method.hasModifierProperty(com.intellij.psi.PsiModifier.STATIC) &&
                    memberRule(method) == null && !name.startsWith("get") &&
                    name !in setOf("equals", "hashCode", "toString", "contains", "containsAll", "isEmpty", "size", "first", "last", "iterator", "hasNext", "next")) {
                    ownedReceiver(node.receiver)?.let { report(it, node) }
                }
                if (name in setOf("PopupActionPill", "GlassMenu", "GlassMorphPopup", "GlassDialog", "Popup", "glassAnchor") &&
                    method.parameterList.parameters.withIndex().any { (i, p) -> p.name in setOf("shadowOpacity", "opacity") && node.getArgumentForParameter(i) != null }) report(UiRuleId.ScreenPopupTrigger, node)
                if (name == "NavigationUtilityPill") report(UiRuleId.ScreenPopupTrigger, node)
                // Round 3 (R2-1): a composable shared entry point with neither a popup-api.txt row nor an exclusion. One incident per
                // call: a call another popup rule already reported is not reported again.
                val declaration = method.navigationElement as? org.jetbrains.kotlin.psi.KtNamedFunction
                val declared = declaration?.containingKtFile?.virtualFilePath?.let { "app/src/main/java/" + it.substringAfterLast("/src/main/java/") }
                if (declaration != null && declaration.isTopLevel && declared != null && sharedPopupFile(declared) && !sharedPopupFile(relative) &&
                    method.hasAnnotation("androidx.compose.runtime.Composable") && popupApis.none { it.name == name } && name !in popupExclusions &&
                    reported.none { it.second === node } && relative !in popupOwnerPaths[UiRuleId.ScreenPopupMotion.name].orEmpty())
                    context.report(UiRuleId.ScreenPopupMotion.issue, node, context.getLocation(node), "Unclassified shared popup entry point $name: add a popup-api.txt row or a popup-api-exclusions.txt reason")

            }

            override fun visitBinaryExpression(node: UBinaryExpression) {
                if (node.operator.text !in setOf("=", "+=", "-=", "*=", "/=", "%=")) return
                reportAnchorWrite(node.leftOperand, node)
            }

            override fun visitPrefixExpression(node: UPrefixExpression) {
                if (node.operator.text in setOf("++", "--")) reportAnchorWrite(node.operand, node)
            }

            override fun visitPostfixExpression(node: UPostfixExpression) {
                if (node.operator.text in setOf("++", "--")) reportAnchorWrite(node.operand, node)
            }

            private fun reportAnchorWrite(operand: UExpression, node: UElement) {
                val left = (operand as? UQualifiedReferenceExpression)?.selector ?: operand
                val symbol = (left as? UReferenceExpression)?.resolve() as? PsiMember ?: return
                val explicit = (operand as? UQualifiedReferenceExpression)?.receiver
                val implicit = if (explicit == null) generateSequence(node.uastParent) { it.uastParent }
                    .filterIsInstance<UCallExpression>().mapNotNull { scope ->
                        val receiver = when (scope.resolve()?.name) {
                            "with" -> scope.valueArguments.firstOrNull()
                            "apply", "run" -> scope.receiver
                            else -> null
                        }
                        if (receiver?.getExpressionType()?.canonicalText == symbol.containingClass?.qualifiedName) ownedReceiver(receiver) else null
                    }.firstOrNull() else null
                (memberRule(symbol) ?: ownedReceiver(explicit) ?: implicit)?.let { report(it, node) }
            }

            override fun visitVariable(node: UVariable) {
                val type = node.type.canonicalText
                if (type.contains("CompositionLocal<") && listOf("GlassPopupHome", "HomeRef", "PopupBodyPresenter").any { type.contains(it) }) report(UiRuleId.ScreenPopupHome, node)
            }

            override fun visitObjectLiteralExpression(node: UObjectLiteralExpression) = motionScale(node.declaration)

            override fun visitClass(node: UClass) {
                if (context.file.extension == "kt" && node.sourcePsi !is org.jetbrains.kotlin.psi.KtClassOrObject) return
                if (node !is UAnonymousClass) motionScale(node)
                if ("$relative:" + node.name in popupRoles) return
                if (node.qualifiedName in setOf("io.github.anastaci1a.localica.ui.GlassAnchor", "io.github.anastaci1a.localica.ui.GlassPopupHome", "io.github.anastaci1a.localica.ui.components.HomeRef") ||
                    setOf("io.github.anastaci1a.localica.ui.GlassAnchor", "io.github.anastaci1a.localica.ui.GlassPopupHome", "io.github.anastaci1a.localica.ui.components.HomeRef", "io.github.anastaci1a.localica.ui.components.PopupState", "io.github.anastaci1a.localica.ui.components.PopupScope").any { node.qualifiedName != it && (context.evaluator.extendsClass(node.javaPsi, it, false) || context.evaluator.implementsInterface(node.javaPsi, it, false)) }) report(UiRuleId.DuplicatePopupRole, node)
            }
        }
    }
}
