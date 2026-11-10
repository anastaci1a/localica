package io.github.anastaci1a.localica.lint

import com.android.tools.lint.client.api.IssueRegistry
import com.android.tools.lint.client.api.Vendor
import com.android.tools.lint.client.api.UElementHandler
import com.android.tools.lint.detector.api.*
import com.intellij.psi.PsiMethod
import org.jetbrains.uast.*

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
                    val name = call.resolve()?.name ?: call.methodName
                    if (name == "addListener" && call.valueArguments.any {
                            it !is ULambdaExpression && Regex("\\bgetMainExecutor\\s*\\(").containsMatchIn(it.asSourceString())
                        }) return true
                    if (name in setOf("withContext", "launch", "async", "runInterruptible")) {
                        val dispatcher = call.valueArguments.filterNot { it is ULambdaExpression }
                            .joinToString { it.asSourceString() }
                        if (Regex("(?:Dispatchers\\.|\\b)(IO|Default)\\b").containsMatchIn(dispatcher)) return false
                        if (Regex("(?:Dispatchers\\.|\\b)Main\\b").containsMatchIn(dispatcher)) return true
                        // Kotlin properties can resolve to their light getter, rather than a field.
                        val resolved = (call.receiver as? UResolvable)?.resolve()
                            ?: call.receiver?.sourcePsi?.references?.firstNotNullOfOrNull { it.resolve() }
                        if (resolved is PsiMethod && resolved.name in setOf("getLifecycleScope", "getViewModelScope") &&
                            resolved.containingClass?.qualifiedName.orEmpty().startsWith("androidx.lifecycle.")) return true
                        val declaration = resolved?.navigationElement
                        val scope = declaration?.toUElementOfType<UVariable>()?.uastInitializer?.asSourceString()
                            ?: declaration?.text.orEmpty()
                        val scopeDispatcher = Regex("\\bDispatchers\\s*\\.\\s*(Main|IO|Default)\\b")
                            .find(scope)?.groupValues?.get(1)
                        if (scopeDispatcher == "Main") return true
                        if (scopeDispatcher == "IO" || scopeDispatcher == "Default") return false
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
