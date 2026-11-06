package io.github.anastaci1a.localica.lint

import com.android.tools.lint.client.api.UElementHandler
import com.android.tools.lint.detector.api.*
import com.intellij.psi.*
import org.jetbrains.uast.*
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.uast.visitor.AbstractUastVisitor

private const val UI = "io.github.anastaci1a.localica.ui"
private val mutators = mapOf(
    "MotionFloat" to setOf("retarget", "retargetSpring", "drive", "release", "initialize"),
    "MotionRect" to setOf("retarget", "retargetSpring", "drive", "release", "initialize"),
    "MotionColor" to setOf("retarget", "retargetSpring", "drive", "release", "initialize"),
    "ExpandCollapse" to setOf("target", "contactBegin", "contactMove", "contactRelease", "contactCancel", "setBasis", "bind"),
    "PersistentShape" to setOf("retarget", "drive"),
    "SurfaceNode" to setOf("request", "transfer", "dismiss"),
)
private val reads = setOf("value", "velocity", "sample", "valueAt", "velocityAt", "sampleAt", "outline", "outlineAt")
private val views = mutators.keys + setOf("MotionValue", "MotionRectView", "MotionColorView", "ExpansionView", "ShapeView", "SurfaceView", "ShapeFrame", "Kinematic")
private val deferred = setOf("layout", "drawBehind", "drawWithContent", "drawWithCache", "onDrawBehind", "onDrawWithContent", "Canvas", "offset", "graphicsLayer", "placeWithLayer")
private val immediate = setOf("run", "let", "also", "apply", "with")
private val surfaces = setOf("Popup", "Presence", "Drawer", "Sheet", "ModalBottomSheet", "ModalDrawerSheet")
private val containers = setOf("Box", "Column", "Row", "Scaffold", "BoxWithConstraints")
private val dimensions = setOf("padding", "size", "height", "width", "offset", "spacedBy", "requiredSize", "requiredWidth", "requiredHeight", "sizeIn", "widthIn", "heightIn")
private val transforms = setOf("alpha", "scaleX", "scaleY", "translationX", "translationY", "rotation", "rotationX", "rotationY", "rotationZ")

/** All analysis state belongs to one source handler, never to the registry or a singleton. */
internal class UiPolicyDetector : Detector(), SourceCodeScanner {
    override fun getApplicableUastTypes() = listOf(
        UFile::class.java, UCallExpression::class.java, USimpleNameReferenceExpression::class.java,
        UBinaryExpression::class.java, UBinaryExpressionWithType::class.java,
        UParameter::class.java, UMethod::class.java, UAnnotation::class.java, UImportStatement::class.java,
        UTypeReferenceExpression::class.java, UIfExpression::class.java, USwitchExpression::class.java,
        UVariable::class.java, UClass::class.java,
    )

    override fun createUastHandler(context: JavaContext): UElementHandler = Analysis(context)

    private class Analysis(val context: JavaContext) : UElementHandler() {
        private val pkg = context.uastFile?.packageName.orEmpty()
        private val layer = layer(pkg) ?: if (context.uastFile?.classes.orEmpty().any {
            it.qualifiedName == "io.github.anastaci1a.localica.MainActivity"
        }) "screens" else null
        private val policy = layer in setOf("components", "layout", "screens")
        private val emitted = mutableSetOf<Pair<UiRuleId, Int>>()
        private val dependencyRanges = mutableListOf<Triple<String, Int, Int>>()
        private val assignments by lazy {
            val result = mutableMapOf<PsiElement, MutableList<UExpression>>()
            context.uastFile?.accept(object : AbstractUastVisitor() {
                override fun visitBinaryExpression(node: UBinaryExpression): Boolean {
                    if (node.operator.text == "=") {
                        val symbol = (node.leftOperand as? UReferenceExpression)?.resolve()?.navigationElement
                        if (symbol != null) result.getOrPut(symbol) { mutableListOf() }.add(node.rightOperand)
                    }
                    return false
                }
            })
            result
        }

        private fun report(rule: UiRuleId, node: UElement, detail: String = rule.policy, at: Location? = null) {
            if (!context.isEnabled(rule.issue)) return
            val location = at ?: if (node is UCallExpression) context.getNameLocation(node) else context.getLocation(node)
            if (emitted.add(rule to (location.start?.offset ?: -1))) {
                // Do not let an invalid suppression suppress the audit of that same declaration.
                if (rule == UiRuleId.UnreviewedUiEscape) {
                    location.source = null
                    context.client.report(context, Incident(rule.issue, detail, location), TextFormat.RAW)
                } else context.report(rule.issue, node, location, detail)
            }
        }

        override fun visitFile(node: UFile) {
            // PSI auditing still sees declarations which lint prunes after Suppress("all").
            node.sourcePsi?.accept(object : PsiRecursiveElementWalkingVisitor() {
                override fun visitElement(element: PsiElement) {
                    if (element is KtAnnotationEntry && element.shortName?.asString() == "Suppress") {
                        val values = element.valueArguments.mapNotNull {
                            val expression = it.getArgumentExpression()?.toUElementOfType<UExpression>()
                            expression?.let { value -> ConstantEvaluator.evaluate(context, value) as? String }
                        }
                        if ("all" in values) report(UiRuleId.UnreviewedUiEscape, node, at = context.getLocation(element))
                    }
                    if (element is KtAnnotationEntry || element is PsiAnnotation) {
                        val annotation = element.toUElementOfType<UAnnotation>()
                        if (annotation != null) auditAnnotation(annotation, node)
                    }
                    if (element is PsiComment) {
                        val ids = Regex("^//\\s*noinspection\\s+(.+)").find(element.text)?.groupValues?.get(1)
                            ?.split(',', ' ')?.filter { it.isNotBlank() }.orEmpty()
                        val next = generateSequence(element.nextSibling) { it.nextSibling }
                            .firstOrNull { it !is PsiWhiteSpace && it !is PsiComment }?.toUElementOfType<UMethod>()
                        if (ids.any { id -> (id == "all" || UiRuleId.entries.any { it.name == id }) &&
                                (next == null || !escapeAllowed(next, id)) }) {
                            report(UiRuleId.UnreviewedUiEscape, node, at = context.getLocation(element))
                        }
                    }
                    super.visitElement(element)
                }
            })
        }

        private fun annotationIds(node: UAnnotation): Set<String> {
            val ids = mutableSetOf<String>()
            fun add(value: Any?) {
                when (value) {
                    is String -> ids.add(value)
                    is Array<*> -> value.forEach { add(it) }
                    is Iterable<*> -> value.forEach { add(it) }
                }
            }
            node.attributeValues.forEach { attribute ->
                attribute.accept(object : AbstractUastVisitor() {
                    override fun visitElement(element: UElement): Boolean {
                        if (element is UExpression) add(ConstantEvaluator.evaluate(context, element))
                        return false
                    }
                })
            }
            return ids
        }

        private fun auditAnnotation(annotation: UAnnotation, file: UFile? = null) {
            if (annotation.qualifiedName !in setOf("kotlin.Suppress", "java.lang.SuppressWarnings", "android.annotation.SuppressLint")) return
            val global = annotation.asSourceString().startsWith("@file:")
            for (id in annotationIds(annotation)) {
                if (id == "all" || UiRuleId.entries.any { it.name == id }) {
                    if (global || !escapeAllowed(annotation, id)) {
                        report(UiRuleId.UnreviewedUiEscape, file ?: annotation, at = context.getLocation(annotation))
                    }
                }
            }
        }

        override fun visitTypeReferenceExpression(node: UTypeReferenceExpression) {
            val name = (node.type as? PsiClassType)?.resolve()?.qualifiedName ?: return
            edge(node, name)
            if (policy && name.startsWith("androidx.compose.animation.core.")) report(UiRuleId.RawUiMotion, node)
        }

        override fun visitIfExpression(node: UIfExpression) {
            if (policy && node.condition !is UBinaryExpression && derived(node.condition)) report(UiRuleId.RawMotionMap, node.condition)
        }

        override fun visitSwitchExpression(node: USwitchExpression) {
            if (policy && derived(node.expression)) report(UiRuleId.RawMotionMap, node)
        }

        override fun visitImportStatement(node: UImportStatement) {
            val ref = node.importReference ?: return
            val target = (ref as? UResolvable)?.resolve()
            val name = symbolName(target) ?: node.asSourceString().removePrefix("import ").removeSuffix(";").trim()
            edge(node, name)
        }

        override fun visitSimpleNameReferenceExpression(node: USimpleNameReferenceExpression) {
            if (layer == null || parents(node).any { it is UTypeReferenceExpression }) return
            val symbol = node.resolve()
            if (symbol is PsiPackage) return
            val name = symbolName(symbol) ?: return
            edge(node, name)
            if (policy && (name.contains(".animation.core.") || name.endsWith(".AnimationSpec"))) {
                report(UiRuleId.RawUiMotion, node)
            }
            if (policy && (name.contains("LocalBottomShadows") || name.contains("clearGlassShadow") || name.contains("bottomGlassShadow"))) {
                report(UiRuleId.RawShadowOrZOrder, node)
            }
            if (policy && name.startsWith("androidx.compose.ui.unit.") && (node.identifier in setOf("dp", "sp") || (symbol as? PsiMethod)?.name in setOf("getDp", "getSp"))) {
                val qualified = node.uastParent as? UQualifiedReferenceExpression
                if (qualified != null && numeric(qualified.receiver)) report(UiRuleId.LiteralUiSpacing, qualified)
            }
            if (policy && symbol is PsiField && symbol.name in transforms && insideLayer(node)) {
                if ((node.uastParent as? UBinaryExpression)?.leftOperand == node) report(UiRuleId.RawPresentationTransform, node)
            }
        }

        override fun visitCallExpression(node: UCallExpression) {
            val method = node.resolve()
            val name = method?.name ?: node.methodName.orEmpty()
            if (method == null) {
                if (layer != null && name in reads + mutators.values.flatten() + dimensions + containers + setOf("tween", "spring", "graphicsLayer", "withFrameNanos")) {
                    report(UiRuleId.UiLayerDependency, node, "Unresolved policy API $name; source verification is incomplete")
                }
                return
            }
            val owner = method.containingClass?.qualifiedName.orEmpty()
            edge(node, "$owner.$name")
            val compose = owner.startsWith("androidx.compose.")
            if (policy) {
                if (owner.startsWith("androidx.compose.animation.") ||
                    (name in setOf("animateScrollToItem", "animateScrollBy", "scrollBy") && foundation(owner) && !scrollAllowed(node))) {
                    report(UiRuleId.RawUiMotion, node)
                }
                if ((name in setOf("withFrameNanos", "withFrameMillis") && compose) ||
                    owner.startsWith("android.view.Choreographer") ||
                    (owner == "java.lang.System" && name == "nanoTime") ||
                    (owner == "android.os.SystemClock" && name == "uptimeMillis") ||
                    ((name == "launch" && owner.startsWith("kotlinx.coroutines") || name == "LaunchedEffect" && compose) && containsMotion(node))) {
                    report(UiRuleId.IndependentUiClock, node)
                }
                if ((compose && name == "alpha") || (compose && name == "placeWithLayer") ||
                    (compose && name == "graphicsLayer" && containsTransform(node))) {
                    report(UiRuleId.RawPresentationTransform, node)
                }
                if ((compose && name in setOf("zIndex", "shadow", "dropShadow", "innerShadow", "saveLayer")) ||
                    name.startsWith("clearGlassShadow") || name.startsWith("bottomGlassShadow") ||
                    (compose && name in setOf("place", "placeRelative") && suppliedArgument(node, "zIndex") != null)) {
                    report(UiRuleId.RawShadowOrZOrder, node)
                }
                if (compose && name in setOf("RoundedCornerShape", "GenericShape", "Path") && stateDependent(node) && surfaceShape(node)) {
                    report(UiRuleId.ReplacePersistentShape, node)
                }
                if ((compose && name in setOf("rememberGraphicsLayer", "drawLayer")) ||
                    (hostsSurface(node) && conditional(node))) report(UiRuleId.UnownedSurfaceLifetime, node)
                val mutableType = mutableKind(node.receiverType) ?: mutableKind(method.containingClass?.let { context.evaluator.getClassType(it) })
                if (mutableType != null && name in mutators.getValue(mutableType) && !owned(node.receiver, node, mutableType)) {
                    report(UiRuleId.RawMotionMap, node)
                }
                if (name in setOf("coerceIn", "coerceAtLeast", "coerceAtMost", "min", "max", "minOf", "maxOf") && derived(node)) {
                    report(UiRuleId.RawMotionMap, node)
                }
                if (compose && name in dimensions && node.valueArguments.any { numeric(it) }) report(UiRuleId.LiteralUiSpacing, node)
            }
            if (layer == "screens" && compose && (name in containers || name == "padding" || name.endsWith("BarsPadding") || name.endsWith("InsetsPadding"))) {
                report(UiRuleId.NonPresetScreenLayout, node)
            }
            if (layer != "spec" && owner.startsWith("$UI.spec.") && method.isConstructor && method.containingClass?.name in setOf("UiSpec", "ResolvedUiSpec")) {
                report(UiRuleId.UnvalidatedUiSpec, node)
            }
        }

        override fun visitBinaryExpression(node: UBinaryExpression) {
            if (!policy) return
            if (node.operator.text in setOf("<", ">", "<=", ">=", "==", "!=", "===", "!==") && (derived(node.leftOperand) || derived(node.rightOperand))) {
                report(UiRuleId.RawMotionMap, node)
            }
            if (node.operator.text == "=" && insideLayer(node) && node.leftOperand.asSourceString().substringAfterLast('.') in transforms) {
                report(UiRuleId.RawPresentationTransform, node)
            }
        }

        override fun visitBinaryExpressionWithType(node: UBinaryExpressionWithType) {
            if (policy && mutableKind(node.type) != null) report(UiRuleId.RawMotionMap, node)
        }

        override fun visitParameter(node: UParameter) {
            declaredType(node, node.type)
            if (layer in setOf("components", "layout") && mutableKind(node.type) != null) report(UiRuleId.RawMotionMap, node)
        }

        override fun visitVariable(node: UVariable) {
            val source = node.sourcePsi
            if (node !is UParameter && (source is PsiField || source is KtProperty && source.typeReference != null)) declaredType(node, node.type)
        }

        override fun visitClass(node: UClass) {
            node.javaPsi.superTypes.forEach { declaredType(node, it) }
        }

        private fun declaredType(node: UElement, type: PsiType?) {
            val resolved = type as? PsiClassType ?: return
            val name = resolved.resolve()?.qualifiedName ?: return
            edge(node, name)
            if (policy && name.startsWith("androidx.compose.animation.core.")) report(UiRuleId.RawUiMotion, node)
            resolved.parameters.forEach { declaredType(node, it) }
        }

        override fun visitMethod(node: UMethod) {
            val source = node.sourcePsi
            if (source is PsiMethod || source is KtNamedFunction && source.typeReference != null) declaredType(node, node.returnType)
            if (layer == null || !composable(node)) return
            scanComposition(node.uastBody, mutableSetOf())
        }

        private fun scanComposition(element: UElement?, seen: MutableSet<PsiElement>, bindings: Map<PsiElement, UExpression> = emptyMap()) {
            element?.accept(object : AbstractUastVisitor() {
                override fun visitLambdaExpression(node: ULambdaExpression): Boolean {
                    val call = parentCall(node)
                    // Inline scope functions execute immediately; stored/event/draw callbacks do not.
                    return call == null || call.methodName !in immediate && call.methodName != "invoke" && !isComposableLambda(call, node)
                }
                override fun visitCallExpression(node: UCallExpression): Boolean {
                    if (node.methodName == "invoke") {
                        val callee = (node.sourcePsi as? KtCallExpression)?.calleeExpression?.toUElementOfType<UExpression>()
                        scanInvokedLambda(node.receiver ?: callee, seen, bindings)
                    }
                    val method = node.resolve() ?: return false
                    if (isRead(node)) report(UiRuleId.MotionReadInComposition, node)
                    if (node.methodName !in deferred && !method.containingClass?.qualifiedName.orEmpty().startsWith("androidx.")) {
                        if (seen.add(method)) {
                            val arguments = method.parameterList.parameters.mapIndexedNotNull { index, parameter ->
                                node.getArgumentForParameter(index)?.let { parameter.navigationElement to it }
                            }.toMap()
                            scanComposition(method.toUElementOfType<UMethod>()?.uastBody, seen, bindings + arguments)
                            seen.remove(method)
                        }
                    }
                    return false
                }
                override fun visitSimpleNameReferenceExpression(node: USimpleNameReferenceExpression): Boolean {
                    val resolved = node.resolve()
                    if (resolved is PsiMethod && seen.add(resolved)) {
                        scanComposition(resolved.toUElementOfType<UMethod>()?.uastBody, seen, bindings)
                        seen.remove(resolved)
                    }
                    val property = (resolved?.navigationElement as? KtProperty) ?: (resolved?.toUElementOfType<UVariable>()?.sourcePsi as? KtProperty)
                    if (property?.hasDelegate() == true) {
                        val delegate = property.delegateExpression?.toUElementOfType<UExpression>()
                        val call = delegate as? UCallExpression
                        val target = symbolName(call?.resolve()).orEmpty()
                        if (target.startsWith("kotlin.") && call?.methodName == "lazy" ||
                            target.startsWith("androidx.compose.runtime.") && call?.methodName == "derivedStateOf") {
                            call?.valueArguments?.filterIsInstance<ULambdaExpression>()?.forEach { scanComposition(it.body, seen, bindings) }
                        } else {
                            val type = delegate?.getExpressionType() as? PsiClassType
                            type?.resolve()?.findMethodsByName("getValue", true)?.forEach { getter ->
                                if (seen.add(getter)) {
                                    scanComposition(getter.toUElementOfType<UMethod>()?.uastBody, seen, bindings)
                                    seen.remove(getter)
                                }
                            }
                        }
                    }
                    return false
                }
            })
        }

        private fun scanInvokedLambda(expression: UExpression?, seen: MutableSet<PsiElement>, bindings: Map<PsiElement, UExpression>) {
            when (val value = unwrap(expression)) {
                is ULambdaExpression -> scanComposition(value.body, seen, bindings)
                is UReferenceExpression -> {
                    val symbol = value.resolve() ?: return
                    if (seen.add(symbol)) {
                        scanInvokedLambda(bindings[symbol.navigationElement] ?: symbol.toUElementOfType<UVariable>()?.uastInitializer, seen, bindings)
                        seen.remove(symbol)
                    }
                }
            }
        }

        private fun isComposableLambda(call: UCallExpression, lambda: ULambdaExpression): Boolean {
            if (call.methodName in deferred) return false
            val method = call.resolve() ?: return false
            val index = call.valueArguments.indexOfFirst { it == lambda || (it as? UNamedExpression)?.expression == lambda }
            val parameter = method.parameterList.parameters.getOrNull(index) ?: return false
            return parameter.type.annotations.any { it.qualifiedName == "androidx.compose.runtime.Composable" }
        }

        private fun isRead(node: UCallExpression): Boolean = node.methodName in reads &&
            (motionType(node.receiverType) || node.resolve()?.containingClass?.let { motionClass(it) } == true)

        private fun derived(node: UElement?, seen: MutableSet<PsiElement> = mutableSetOf()): Boolean {
            if (node == null) return false
            if (node is UCallExpression) {
                if (isRead(node)) return true
                if (derived(node.receiver, seen)) return true
                val method = node.resolve()
                if (method != null && seen.add(method) && derived(method.toUElementOfType<UMethod>()?.uastBody, seen)) return true
            }
            if (node is UReferenceExpression) {
                val symbol = node.resolve()
                if (symbol != null && seen.add(symbol)) {
                    if (symbol is PsiVariable && (derived(symbol.toUElementOfType<UVariable>()?.uastInitializer, seen) ||
                        assignments[symbol.navigationElement].orEmpty().any { derived(it, seen) })) return true
                    if (symbol is PsiMethod && derived(symbol.toUElementOfType<UMethod>()?.uastBody, seen)) return true
                }
            }
            var result = false
            node.accept(object : AbstractUastVisitor() {
                override fun visitElement(child: UElement): Boolean {
                    if (child === node) return false
                    if (derived(child, seen)) result = true
                    return true
                }
            })
            return result
        }

        private fun owned(receiver: UExpression?, at: UElement, kind: String, seen: MutableSet<PsiElement> = mutableSetOf()): Boolean {
            val ownerMethod = enclosingMethod(at) ?: return false
            if (!composable(ownerMethod)) return false
            val value = unwrap(receiver)
            if (value is UReferenceExpression) {
                val symbol = value.resolve() ?: return false
                if (symbol !is PsiLocalVariable || !seen.add(symbol)) return false
                val variable = symbol.toUElementOfType<UVariable>() ?: return false
                if (enclosingMethod(variable) != ownerMethod) return false
                return owned(variable.uastInitializer, at, kind, seen)
            }
            if (value is UCallExpression) {
                val method = value.resolve() ?: return false
                if (method.name != "remember$kind" || !symbolName(method).orEmpty().startsWith("$UI.engine.")) return false
                if (enclosingMethod(value) != ownerMethod) return false
                // A locally stored callback can escape its creating composition.
                var parent: UElement? = at.uastParent
                while (parent != null && parent != ownerMethod) {
                    if (parent is ULambdaExpression) {
                        val call = parentCall(parent) ?: return false
                        val allowed = call.methodName in immediate || call.valueArguments.withIndex().any { (index, arg) ->
                            val parameter = (arg as? UNamedExpression)?.name ?: call.resolve()?.parameterList?.parameters?.getOrNull(index)?.name
                            parameter in setOf("onClick", "onDrag", "onRelease", "onDismissRequest") &&
                                arg.sourcePsi?.textRange?.contains(parent.sourcePsi?.textRange ?: return@any false) == true
                        }
                        if (!allowed) return false
                    }
                    parent = parent.uastParent
                }
                return true
            }
            return false
        }

        private fun scrollAllowed(node: UCallExpression): Boolean {
            // Both target and profile provenance are mandatory; raw specs still report at their definition.
            return fromScrollToken(node) && derived(node)
        }

        private fun fromScrollToken(node: UElement?, seen: MutableSet<PsiElement> = mutableSetOf()): Boolean {
            if (node == null) return false
            if (node is UReferenceExpression) {
                val symbol = node.resolve()
                if (symbolName(symbol).orEmpty().startsWith("$UI.spec.MotionProfile.Scroll")) return true
                if (symbol != null && seen.add(symbol)) {
                    if (fromScrollToken(symbol.toUElementOfType<UVariable>()?.uastInitializer, seen)) return true
                }
            }
            if (node is UCallExpression) {
                if (fromScrollToken(node.receiver, seen)) return true
                val method = node.resolve()
                if (method != null && seen.add(method) && fromScrollToken(method.toUElementOfType<UMethod>()?.uastBody, seen)) return true
            }
            var found = false
            node.accept(object : AbstractUastVisitor() {
                override fun visitElement(child: UElement): Boolean {
                    if (child === node) return false
                    if (fromScrollToken(child, seen)) found = true
                    return true
                }
            })
            return found
        }

        private fun numeric(node: UElement?, seen: MutableSet<PsiElement> = mutableSetOf()): Boolean {
            if (node is ULiteralExpression) return node.value is Number
            if (node is UNamedExpression) return numeric(node.expression, seen)
            if (node is UReferenceExpression) {
                val symbol = node.resolve()
                // Spec declarations are validated data, even when their implementation is numeric.
                if (symbolName(symbol).orEmpty().startsWith("$UI.spec.")) return false
                if (symbol != null && seen.add(symbol)) return numeric(symbol.toUElementOfType<UVariable>()?.uastInitializer, seen)
            }
            return when (node) {
                is UQualifiedReferenceExpression -> numeric(node.receiver, seen)
                is UParenthesizedExpression -> numeric(node.expression, seen)
                is UPrefixExpression -> numeric(node.operand, seen)
                is UBinaryExpression -> numeric(node.leftOperand, seen) && numeric(node.rightOperand, seen)
                else -> false
            }
        }

        private fun containsMotion(node: UElement): Boolean {
            var result = false
            node.accept(object : AbstractUastVisitor() {
                override fun visitCallExpression(call: UCallExpression): Boolean {
                    val kind = mutableKind(call.receiverType)
                    if (kind != null && call.methodName in mutators.getValue(kind) ||
                        call.methodName in setOf("animateTo", "animateBy") && symbolName(call.resolve()).orEmpty().startsWith("androidx.compose.animation.")) result = true
                    return false
                }
            })
            return result
        }

        private fun containsTransform(node: UElement): Boolean {
            var result = false
            node.accept(object : AbstractUastVisitor() {
                override fun visitBinaryExpression(expression: UBinaryExpression): Boolean {
                    if (expression.operator.text == "=" && expression.leftOperand.asSourceString().substringAfterLast('.') in transforms) result = true
                    return false
                }
            })
            // Named graphicsLayer overloads also assign presentation properties.
            return result || (node as? UCallExpression)?.let { call -> transforms.any { suppliedArgument(call, it) != null } } == true
        }

        private fun hostsSurface(call: UCallExpression, seen: MutableSet<PsiElement> = mutableSetOf()): Boolean {
            if (call.methodName in surfaces) return true
            val method = call.resolve() ?: return false
            if (!seen.add(method)) return false
            var found = false
            method.toUElementOfType<UMethod>()?.uastBody?.accept(object : AbstractUastVisitor() {
                override fun visitCallExpression(node: UCallExpression): Boolean {
                    if (hostsSurface(node, seen)) found = true
                    return false
                }
            })
            return found
        }

        private fun insideLayer(node: UElement): Boolean = parents(node).filterIsInstance<UCallExpression>().any {
            it.methodName == "graphicsLayer" && symbolName(it.resolve()).orEmpty().startsWith("androidx.compose.")
        }
        private fun conditional(node: UElement) = parents(node).any { it is UIfExpression || it is USwitchExpression }
        private fun stateDependent(node: UElement): Boolean = conditional(node) || parents(node).filterIsInstance<UCallExpression>().any {
            it.methodName == "remember" && it.valueArguments.any { arg -> arg !is ULambdaExpression }
        }
        private fun surfaceShape(node: UElement): Boolean {
            // A standalone plot/texture Path is fine; Shape constructors are always outlines.
            if ((node as? UCallExpression)?.resolve()?.containingClass?.name != "Path") return true
            return parents(node).any {
                it is UCallExpression && it.methodName in setOf("GenericShape", "clip", "background", "Surface", "drawOutline") ||
                    it is UVariable && it.name?.contains("shape", ignoreCase = true) == true
            }
        }
        private fun specDependency(node: UElement, target: String): Boolean {
            if (target.startsWith("$UI.spec.") || target == "$UI.spec") return true
            val method = parents(node).filterIsInstance<UMethod>().firstOrNull()?.name
            val glass = "io.github.anastaci1a.localica.navigation.GlassAppearance"
            if (target == glass || target.startsWith("$glass.")) return method == "uiSpec"
            val provider = node is UVariable && node.name == "LocalUiSpec" || parents(node).filterIsInstance<UVariable>().any { it.name == "LocalUiSpec" } || method == "getLocalUiSpec"
            val providerImport = node is UImportStatement && context.uastFile?.classes.orEmpty().any { clazz ->
                clazz.fields.any { it.name == "LocalUiSpec" }
            }
            if (target in setOf("androidx.compose.runtime.staticCompositionLocalOf", "androidx.compose.runtime.CompositionLocalKt.staticCompositionLocalOf", "androidx.compose.runtime.ProvidableCompositionLocal")) return provider || providerImport
            val settings = "io.github.anastaci1a.localica.navigation.InterfaceSettings"
            if (target == settings || target.startsWith("$settings.")) {
                if (parents(node).filterIsInstance<UMethod>().firstOrNull()?.name == "uiSpec") return true
                return node is UImportStatement && context.uastFile?.classes.orEmpty().any { clazz -> clazz.methods.any { it.name == "uiSpec" } }
            }
            return target.startsWith("kotlin.") || target.startsWith("java.lang.") || target.startsWith("java.util.") ||
                target in setOf("androidx.compose.runtime.Immutable", "androidx.compose.runtime.Stable") ||
                target.startsWith("androidx.compose.ui.unit.")
        }

        private fun edge(node: UElement, target: String) {
            val source = layer ?: return
            val destination = layer(target)
            val rootClockProvider = pkg == UI && context.file.name == "Theme.kt" && target in setOf("$UI.engine.UiClockKt.ProvideUiClock", "$UI.engine.ProvideUiClock") && (node is UImportStatement || parents(node).filterIsInstance<UMethod>().firstOrNull()?.name == "LocalicaTheme")
            val prohibited = when (source) {
                "spec" -> !specDependency(node, target)
                "engine" -> destination in setOf("components", "layout", "screens")
                "components" -> destination in setOf("layout", "screens") || target.startsWith("androidx.compose.animation.core.")
                "layout" -> destination == "screens"
                // A2 explicitly permits these unmigrated hosts to acquire Engine ownership in Stage2.
                "screens" -> destination == "engine" && !rootClockProvider && !(pkg == UI && context.file.name in setOf("PlayerSurface.kt", "QueueSurface.kt", "LocalicaScreen.kt"))
                else -> false
            }
            if (prohibited) {
                val location = context.getLocation(node)
                val start = location.start?.offset ?: -1
                val end = location.end?.offset ?: start
                if (dependencyRanges.any { (symbol, first, last) -> symbol == target && start <= last && end >= first }) return
                dependencyRanges.add(Triple(target, start, end))
                report(UiRuleId.UiLayerDependency, node, if (source == "spec") "${UiRuleId.UiLayerDependency.policy}: $target" else UiRuleId.UiLayerDependency.policy)
            }
        }

        override fun visitAnnotation(node: UAnnotation) = auditAnnotation(node)

        private fun escapeAllowed(node: UElement, id: String): Boolean {
            if (id == "all" || layer != "engine" || !pkg.startsWith("$UI.engine.") ||
                !pkg.split('.').any { it in setOf("diagnostics", "platform", "readback", "window") }) return false
            val method = (node as? UMethod) ?: parents(node).filterIsInstance<UMethod>().firstOrNull() ?: return false
            val fqn = symbolName(method.javaPsi) ?: return false
            val line = (context.getLocation(method).start?.line ?: return false) + 1
            val manifest = sequenceOf(context.project.dir.resolve("ui-escapes.yaml"), context.project.dir.resolve("app/ui-escapes.yaml")).firstOrNull { it.isFile } ?: return false
            val entries = EscapeManifest.parse(manifest.readText()) ?: return false
            val matches = entries.filter { it.rule.name == id && it.symbol == "$fqn:$line" }
            if (matches.size != 1) return false
            val entry = matches.single()
            return EscapeManifest.isLive(entry,
                context.configuration.getOption(UiRuleId.UnreviewedUiEscape.issue, "knownSteps"),
                EscapeOptions.completed.getValue(context),
                EscapeOptions.release.getValue(context))
        }
    }
}

private fun layer(name: String): String? = when {
    name == "io.github.anastaci1a.localica.MainActivity" || name.startsWith("io.github.anastaci1a.localica.MainActivity.") -> "screens"
    name == UI || name.startsWith("$UI.") -> name.removePrefix(UI).trimStart('.').substringBefore('.').takeIf { it in setOf("engine", "spec", "components", "layout") } ?: "screens"
    else -> null
}
private fun symbolName(symbol: PsiElement?): String? = when (symbol) {
    is PsiPackage -> symbol.qualifiedName
    is PsiClass -> symbol.qualifiedName
    is PsiMember -> symbol.containingClass?.qualifiedName?.let { "$it.${symbol.name}" }
    else -> null
}
private fun motionClass(type: PsiClass): Boolean = (type.qualifiedName.orEmpty().startsWith("$UI.engine.") && type.name in views) || type.supers.any { motionClass(it) }
private fun motionType(type: PsiType?): Boolean = (type as? PsiClassType)?.resolve()?.let { motionClass(it) } == true
private fun mutableKind(type: PsiType?): String? {
    val clazz = (type as? PsiClassType)?.resolve() ?: return null
    return clazz.name?.takeIf { clazz.qualifiedName.orEmpty().startsWith("$UI.engine.") && it in mutators } ?: clazz.superTypes.firstNotNullOfOrNull { mutableKind(it) }
}
private fun foundation(owner: String) = owner.startsWith("androidx.compose.foundation.")
private fun parents(node: UElement): Sequence<UElement> = generateSequence(node.uastParent) { it.uastParent }
private fun enclosingMethod(node: UElement) = parents(node).filterIsInstance<UMethod>().firstOrNull()
private fun composable(method: UMethod) = method.uAnnotations.any { it.qualifiedName == "androidx.compose.runtime.Composable" }
private fun parentCall(node: UElement): UCallExpression? = parents(node).takeWhile { it !is UMethod && it !is ULambdaExpression }.filterIsInstance<UCallExpression>().firstOrNull()
private fun unwrap(expression: UExpression?): UExpression? = when (expression) {
    is UParenthesizedExpression -> unwrap(expression.expression)
    is UNamedExpression -> unwrap(expression.expression)
    else -> expression
}

private fun suppliedArgument(call: UCallExpression, name: String): UExpression? {
    val index = call.resolve()?.parameterList?.parameters?.indexOfFirst { it.name == name } ?: return null
    return if (index >= 0) call.getArgumentForParameter(index) else null
}
