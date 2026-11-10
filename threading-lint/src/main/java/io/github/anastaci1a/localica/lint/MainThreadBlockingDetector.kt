package io.github.anastaci1a.localica.lint

import com.android.tools.lint.client.api.IssueRegistry
import com.android.tools.lint.client.api.Vendor
import com.android.tools.lint.client.api.UElementHandler
import com.android.tools.lint.detector.api.*
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMember
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiClass
import org.jetbrains.uast.*
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.lexer.KtTokens

class ThreadingRegistry : IssueRegistry() {
    override val api = CURRENT_API
    override val vendor = Vendor(vendorName = "Localica", identifier = "io.github.anastaci1a.localica",
        feedbackUrl = "https://github.com/anastaci1a/localica/issues/772")
    override val issues = listOf(MainThreadBlockingDetector.ISSUE)
}

/** Resolve owners so suspend await and unrelated query APIs remain legal. */
class MainThreadBlockingDetector : Detector(), SourceCodeScanner {
    override fun getApplicableUastTypes() = listOf(UCallExpression::class.java)
    override fun createUastHandler(context: JavaContext) = object : UElementHandler() {
        override fun visitCallExpression(node: UCallExpression) {
            val method = node.resolve() ?: return
            if (blocking(context, method) && onMain(node)) {
                context.report(ISSUE, node, context.getLocation(node),
                    "${method.containingClass?.qualifiedName}.${method.name} can block Main; prepare the result on IO or Default and publish it to the UI.")
            }
        }
    }

    private fun blocking(context: JavaContext, method: PsiMethod): Boolean {
        val owner = method.containingClass ?: return false
        val name = method.name
        val qualified = owner.qualifiedName.orEmpty()
        val evaluator = context.evaluator
        if (qualified == "java.io.File") return !method.isConstructor && name !in pureFileOperations
        if (qualified == "java.nio.file.Files") return true
        if (qualified in streams) return method.isConstructor || name in streamOperations
        if (qualified.startsWith("kotlin.io.FilesKt")) return name in fileExtensions
        if (qualified == "android.graphics.BitmapFactory" || qualified == "android.graphics.ImageDecoder")
            return name.startsWith("decode")
        if (evaluator.extendsClass(owner, "android.content.Context", false))
            return name in setOf("getFilesDir", "getCacheDir", "getNoBackupFilesDir", "getCodeCacheDir",
                "getExternalFilesDir", "getExternalFilesDirs", "getExternalCacheDir", "getExternalCacheDirs", "getSharedPreferences")
        if (evaluator.extendsClass(owner, "android.content.ContentResolver", false))
            return name in setOf("query", "openInputStream", "openOutputStream", "openFileDescriptor", "openAssetFileDescriptor", "loadThumbnail")
        if (qualified == "java.security.MessageDigest") return name in setOf("digest", "update")
        if (qualified == "java.lang.Thread") return name in setOf("sleep", "join")
        if (qualified == "java.lang.Object") return name == "wait"
        if (qualified.startsWith("kotlinx.coroutines.BuildersKt") && name == "runBlocking") return true
        if (qualified == "java.util.concurrent.CountDownLatch") return name == "await"
        if (qualified == "java.util.concurrent.Semaphore") return name in setOf("acquire", "acquireUninterruptibly")
        if (qualified == "java.util.concurrent.CyclicBarrier") return name == "await"
        if (qualified.startsWith("java.util.concurrent.locks.")) return name in setOf("lock", "lockInterruptibly", "await", "awaitUninterruptibly")
        if (evaluator.extendsClass(owner, "java.util.concurrent.Future", false)) return name == "get"
        if (evaluator.extendsClass(owner, "android.content.SharedPreferences.Editor", false)) return name == "commit"
        if (owner.hasAnnotation("androidx.room.Dao")) {
            val suspending = method.parameterList.parameters.lastOrNull()?.type?.canonicalText
                ?.startsWith("kotlin.coroutines.Continuation") == true
            val deferred = method.returnType?.canonicalText.orEmpty().let {
                it.startsWith("kotlinx.coroutines.flow.Flow") || it.startsWith("androidx.lifecycle.LiveData")
            }
            return !suspending && !deferred
        }
        return false
    }

    private fun onMain(node: UElement): Boolean {
        var parent = node.uastParent
        while (parent != null) {
            if (parent is ULambdaExpression) {
                val call = generateSequence(parent.uastParent) { it.uastParent }
                    .takeWhile { it !is ULambdaExpression && it !is UMethod }
                    .filterIsInstance<UCallExpression>().firstOrNull()
                if (call != null) {
                    val method = call.resolve()
                    val name = method?.name ?: call.methodName
                    if (name == "addListener" && mainExecutor(argument(call, "executor"))) return true
                    if (name == "execute" && mainExecutor(call.receiver)) return true
                    if (name in setOf("withContext", "launch", "async", "runInterruptible") &&
                        method?.containingClass?.qualifiedName.orEmpty().startsWith("kotlinx.coroutines.")) {
                        val supplied = dispatcher(argument(call, "context"))
                        val effective = if (name == "launch" || name == "async")
                            combine(scopeDispatcher(call.receiver), supplied) else supplied
                        if (effective == Dispatcher.WORKER) return false
                        if (effective == Dispatcher.MAIN) return true
                    }
                    if (name in mainLambdas) return true
                }
            }
            if (parent is UMethod) {
                return parent.hasAnnotation("androidx.compose.runtime.Composable") ||
                    parent.hasAnnotation("androidx.annotation.MainThread") ||
                    parent.hasAnnotation("androidx.annotation.UiThread")
            }
            parent = parent.uastParent
        }
        return false
    }

    private enum class Dispatcher { MAIN, WORKER, NONE, UNKNOWN }

    /** ContinuationInterceptor is a context key: the rightmost dispatcher wins. */
    private fun combine(left: Dispatcher, right: Dispatcher): Dispatcher = when (right) {
        Dispatcher.NONE -> left
        Dispatcher.MAIN, Dispatcher.WORKER -> right
        // Unknown values never establish a worker escape. Preserve a possible Main owner.
        Dispatcher.UNKNOWN -> if (left == Dispatcher.MAIN) Dispatcher.MAIN else Dispatcher.UNKNOWN
    }

    private fun argument(call: UCallExpression, name: String): UExpression? {
        val parameters = call.resolve()?.parameterList?.parameters ?: return null
        val index = parameters.indexOfFirst {
            it.name == name || (name == "executor" && it.type.canonicalText == "java.util.concurrent.Executor") ||
                (name == "context" && it.type.canonicalText == "kotlin.coroutines.CoroutineContext")
        }
        return if (index >= 0) call.getArgumentForParameter(index) else null
    }

    private fun unwrap(expression: UExpression): UExpression = when (expression) {
        is UParenthesizedExpression -> unwrap(expression.expression)
        is UNamedExpression -> unwrap(expression.expression)
        else -> expression
    }

    private fun resolve(expression: UExpression): PsiElement? =
        (expression as? UResolvable)?.resolve()
            ?: (expression as? UQualifiedReferenceExpression)?.selector?.let { (it as? UResolvable)?.resolve() }
            ?: expression.sourcePsi?.references?.firstNotNullOfOrNull { it.resolve() }

    private fun initializer(symbol: PsiElement?): UExpression? {
        val variable = (symbol as? UVariable) ?: symbol?.toUElementOfType<UVariable>()
            ?: symbol?.navigationElement?.toUElementOfType<UVariable>()
        val property = (variable?.sourcePsi as? KtProperty) ?: symbol?.navigationElement as? KtProperty
        if (property != null) {
            // A val may still be virtual, delegated, or computed on every access.
            if (property.isVar || property.hasModifier(KtTokens.OPEN_KEYWORD) ||
                property.hasModifier(KtTokens.OVERRIDE_KEYWORD) || property.hasModifier(KtTokens.ABSTRACT_KEYWORD) ||
                property.getter != null || property.hasDelegate() || property.receiverTypeReference != null) return null
            return property.initializer?.toUElementOfType<UExpression>()
        }
        if (variable == null) return null
        // A mutable alias or caller-supplied parameter cannot prove a worker boundary.
        if (variable.javaPsi is PsiParameter ||
            (variable.javaPsi as? PsiModifierListOwner)?.hasModifierProperty(PsiModifier.FINAL) != true) return null
        return variable.uastInitializer
    }

    private fun dispatcher(expression: UExpression?, depth: Int = 0): Dispatcher {
        if (expression == null) return Dispatcher.NONE
        if (depth > 16) return Dispatcher.UNKNOWN
        val value = unwrap(expression)
        if (value is UBinaryExpression && value.operator == UastBinaryOperator.PLUS) {
            if (!contextPlus(value.resolveOperator())) return Dispatcher.UNKNOWN
            return combine(dispatcher(value.leftOperand, depth + 1), dispatcher(value.rightOperand, depth + 1))
        }
        val symbol = resolve(value)
        val member = symbol as? PsiMember
        val owner = member?.containingClass?.qualifiedName.orEmpty()
        val name = (symbol as? PsiNamedElement)?.name.orEmpty()
        if (owner == "kotlinx.coroutines.Dispatchers") return when (name.removePrefix("get")) {
            "Main" -> Dispatcher.MAIN
            "IO", "Default" -> Dispatcher.WORKER
            else -> Dispatcher.UNKNOWN
        }
        if (owner == "kotlinx.coroutines.MainCoroutineDispatcher" && name == "getImmediate") return Dispatcher.MAIN
        val typeOwner = (symbol as? PsiClass)?.qualifiedName ?: owner
        if (typeOwner == "kotlinx.coroutines.NonCancellable" || typeOwner == "kotlin.coroutines.EmptyCoroutineContext") return Dispatcher.NONE
        val call = value as? UCallExpression ?: (value as? UQualifiedReferenceExpression)?.selector as? UCallExpression
        if (call != null) {
            val method = call.resolve()
            val qualified = method?.containingClass?.qualifiedName.orEmpty()
            if (method?.isConstructor == true && qualified == "kotlinx.coroutines.CoroutineName") return Dispatcher.NONE
            if (name in setOf("Job", "SupervisorJob", "CoroutineExceptionHandler") && qualified.startsWith("kotlinx.coroutines."))
                return Dispatcher.NONE
            if (name == "limitedParallelism" && qualified.startsWith("kotlinx.coroutines."))
                return dispatcher(call.receiver, depth + 1)
            if (name == "plus" && call.receiver != null && contextPlus(method))
                return combine(dispatcher(call.receiver, depth + 1), dispatcher(call.valueArguments.firstOrNull(), depth + 1))
        }
        return initializer(symbol)?.let { dispatcher(it, depth + 1) } ?: Dispatcher.UNKNOWN
    }

    /** Only platform context combination has the right-biased dispatcher contract. */
    private fun contextPlus(method: PsiMethod?): Boolean = method?.name == "plus" &&
        method.containingClass?.qualifiedName in setOf("kotlin.coroutines.CoroutineContext",
            "kotlin.coroutines.CoroutineContext.Element", "kotlin.coroutines.EmptyCoroutineContext",
            "kotlin.coroutines.CombinedContext") &&
        method.parameterList.parameters.singleOrNull()?.type?.canonicalText == "kotlin.coroutines.CoroutineContext"

    private fun scopeDispatcher(expression: UExpression?, depth: Int = 0): Dispatcher {
        if (expression == null || depth > 16) return Dispatcher.UNKNOWN
        val value = unwrap(expression)
        val symbol = resolve(value)
        val method = symbol as? PsiMethod
        val owner = method?.containingClass?.qualifiedName.orEmpty()
        if (method?.name in setOf("getLifecycleScope", "getViewModelScope") && owner.startsWith("androidx.lifecycle."))
            return Dispatcher.MAIN
        val call = value as? UCallExpression ?: (value as? UQualifiedReferenceExpression)?.selector as? UCallExpression
        if (call != null && owner.startsWith("kotlinx.coroutines.")) {
            if (method?.name == "MainScope") return Dispatcher.MAIN
            if (method?.name == "CoroutineScope" || (method?.isConstructor == true && owner == "kotlinx.coroutines.CoroutineScope"))
                return dispatcher(argument(call, "context"), depth + 1)
        }
        return initializer(symbol)?.let { scopeDispatcher(it, depth + 1) } ?: Dispatcher.UNKNOWN
    }

    private fun mainExecutor(expression: UExpression?, depth: Int = 0): Boolean {
        if (expression == null || depth > 16) return false
        val value = unwrap(expression)
        val symbol = resolve(value)
        val method = symbol as? PsiMethod
        if (mainExecutorProducer(method)) return true
        return initializer(symbol)?.let { mainExecutor(it, depth + 1) } ?: false
    }

    private fun mainExecutorProducer(method: PsiMethod?, depth: Int = 0): Boolean {
        if (method?.name != "getMainExecutor" || depth > 16) return false
        if (method.containingClass?.qualifiedName == "androidx.core.content.ContextCompat") return true
        if (method.parameterList.parametersCount != 0) return false
        if (method.containingClass?.qualifiedName in setOf("android.content.Context", "android.content.ContextWrapper")) return true
        return method.findSuperMethods().any { mainExecutorProducer(it, depth + 1) }
    }

    companion object {
        private val pureFileOperations = setOf("getPath", "getAbsolutePath", "getAbsoluteFile", "getName", "getParent", "getParentFile",
            "isAbsolute", "toString", "toPath", "compareTo", "equals", "hashCode")
        private val streams = setOf("java.io.FileInputStream", "java.io.FileOutputStream", "java.io.RandomAccessFile", "java.io.FileReader", "java.io.FileWriter")
        private val streamOperations = setOf("read", "write", "readFully", "skip", "seek", "close", "flush", "available", "length", "setLength")
        private val fileExtensions = setOf("readText", "readBytes", "readLines", "forEachLine", "useLines", "writeText", "writeBytes", "appendText", "appendBytes", "copyTo", "copyRecursively", "deleteRecursively", "inputStream", "outputStream", "reader", "writer", "bufferedReader", "bufferedWriter")
        private val mainLambdas = setOf("drawBehind", "drawWithContent", "drawWithCache", "Canvas", "Layout", "SubcomposeLayout",
            "layout", "onGloballyPositioned", "onSizeChanged", "onPlaced", "measure", "MeasurePolicy", "graphicsLayer", "runOnUiThread")
        val ISSUE = Issue.create("MainThreadBlocking", "Blocking work on the UI thread",
            "UI composition, drawing, measurement and Main callbacks must consume prepared in-memory data. Move blocking IO and heavy decode/hash work to a worker dispatcher.",
            Category.PERFORMANCE, 10, Severity.ERROR,
            Implementation(MainThreadBlockingDetector::class.java, Scope.JAVA_FILE_SCOPE))
    }
}
