package io.github.anastaci1a.localica.lint

import com.android.tools.lint.checks.infrastructure.LintDetectorTest
import org.junit.Test

class MainThreadBlockingDetectorTest : LintDetectorTest() {
    override fun getDetector() = MainThreadBlockingDetector()
    override fun getIssues() = listOf(MainThreadBlockingDetector.ISSUE)

    private val composable = kotlin("""
        package androidx.compose.runtime
        annotation class Composable
    """)
    private val contexts = kotlin("""
        package kotlinx.coroutines
        object Dispatchers {
            val IO: kotlin.coroutines.CoroutineContext = kotlin.coroutines.EmptyCoroutineContext
            val Default: kotlin.coroutines.CoroutineContext = kotlin.coroutines.EmptyCoroutineContext
            val Main: kotlin.coroutines.CoroutineContext = kotlin.coroutines.EmptyCoroutineContext
        }
        fun withContext(context: Any, block: () -> Unit) = block()
    """)
    private val mainExecutor = java("""
        package androidx.core.content;
        public class ContextCompat {
            public static java.util.concurrent.Executor getMainExecutor(Object context) { return null; }
        }
    """)

    @Test fun testCompositionAndMainCallbacksFailButWorkersRemainLegal() {
        lint().files(composable, contexts, kotlin("""
            package test
            import androidx.compose.runtime.Composable
            import kotlinx.coroutines.*
            import java.io.File
            @Composable fun screen() {
                File("cover").length()
                withContext(Dispatchers.IO) { File("cover").length() }
                withContext(Dispatchers.Default) { File("cover").length() }
                withContext(Dispatchers.IO + kotlin.coroutines.EmptyCoroutineContext) { File("cover").length() }
                withContext(Dispatchers.Main) { File("cover").length() }
            }
        """)).allowMissingSdk().run().expectErrorCount(2)
    }

    @Test fun testDrawMeasureAndNestedHelperLambdasAreMain() {
        lint().files(kotlin("""
            package test
            import java.io.File
            fun drawWithContent(block: () -> Unit) = block()
            fun measure(block: () -> Unit) = block()
            fun draw() {
                drawWithContent { listOf(1).forEach { File("cover").exists() } }
                measure { File("cover").listFiles() }
            }
            fun worker() { File("cover").length() }
        """)).allowMissingSdk().run().expectErrorCount(2)
    }

    @Test fun testUnrelatedNamesAndPureFileConstructionRemainLegal() {
        lint().files(composable, kotlin("""
            package test
            import androidx.compose.runtime.Composable
            import java.io.File
            class Result { fun get() = 1; fun query() = 1 }
            @Composable fun screen() { File("cover").path; Result().get(); Result().query() }
        """)).allowMissingSdk().run().expectClean()
    }

    @Test fun testMainScopeAndNestedWorkerOverrides() {
        lint().files(contexts, kotlin("""
            package kotlinx.coroutines
            class CoroutineScope(val context: Any) {
                fun launch(context: Any? = null, block: () -> Unit) = block()
            }
        """), kotlin("""
            package test
            import kotlinx.coroutines.*
            import java.io.File
            class Gateway {
                val scope = CoroutineScope(Dispatchers.Main)
                fun callback() {
                    scope.launch { File("state").delete() }
                    scope.launch(Dispatchers.IO) { File("state").delete() }
                }
            }
        """)).allowMissingSdk().run().expectErrorCount(1)
    }

    @Test fun testResolvedIoDecodeHashAndSynchronousDao() {
        lint().files(composable,
            java("""
                package android.graphics;
                public class BitmapFactory { public static Object decodeFile(String file) { return null; } }
            """),
            java("""
                package android.content;
                public class ContentResolver { public Object query(String uri) { return null; } }
            """),
            java("""
                package android.content;
                public class Context { public java.io.File getFilesDir() { return null; } }
            """),
            kotlin("package androidx.room; annotation class Dao"),
            kotlin("package kotlinx.coroutines.flow; interface Flow<T>"),
            kotlin("""
                package test
                import androidx.compose.runtime.Composable
                import androidx.room.Dao
                import kotlinx.coroutines.flow.Flow
                import java.io.*
                import java.security.MessageDigest
                @Dao interface Storage {
                    fun direct(): Int
                    suspend fun suspended(): Int
                    fun observed(): Flow<Int>
                }
                @Composable fun screen(dao: Storage, resolver: android.content.ContentResolver, context: android.content.Context) {
                    FileInputStream("state")
                    File("state").canonicalPath
                    context.filesDir
                    android.graphics.BitmapFactory.decodeFile("cover")
                    resolver.query("content://tracks")
                    MessageDigest.getInstance("SHA-256").digest(byteArrayOf(1))
                    dao.direct()
                    dao.observed()
                }
            """)).allowMissingSdk().run().expectErrorCount(7)
    }

    @Test fun testMainExecutorCompletionCannotUseBlockingFutureGet() {
        lint().files(mainExecutor, kotlin("""
            package test
            import java.util.concurrent.CompletableFuture
            import java.util.concurrent.Executor
            import androidx.core.content.ContextCompat.getMainExecutor
            fun addListener(callback: () -> Unit, executor: Executor) = executor.execute(callback)
            fun watch(future: CompletableFuture<String>) {
                addListener({ future.get() }, getMainExecutor(null))
            }
        """)).allowMissingSdk().run().expectErrorCount(1)
    }

    @Test fun testLifecycleScopeDefaultsToMainButExplicitIoOverridesIt() {
        lint().files(contexts, kotlin("""
            package kotlinx.coroutines
            class CoroutineScope(val context: Any) {
                fun launch(context: Any? = null, block: () -> Unit) = block()
            }
        """), kotlin("""
            package androidx.lifecycle
            import kotlinx.coroutines.CoroutineScope
            class Owner
            val Owner.lifecycleScope: CoroutineScope get() = CoroutineScope(Any())
        """), kotlin("""
            package test
            import androidx.lifecycle.*
            import kotlinx.coroutines.*
            import java.io.File
            fun callback(owner: Owner) {
                owner.lifecycleScope.launch { File("state").length() }
                owner.lifecycleScope.launch(Dispatchers.IO) { File("state").length() }
            }
        """)).allowMissingSdk().run().expectErrorCount(1)
    }

    @Test fun testDrawCallbackImportAliasKeepsItsMainContract() {
        lint().files(kotlin("""
            package drawing
            fun drawBehind(block: () -> Unit) = block()
        """), kotlin("""
            package test
            import drawing.drawBehind as paint
            import java.io.File
            fun render() { paint { File("state").exists() } }
        """)).allowMissingSdk().run().expectErrorCount(1)
    }

    @Test fun testContextNamesCannotHideMainAndRightmostDispatcherWins() {
        lint().files(composable, contexts, kotlin("""
            package kotlinx.coroutines
            class CoroutineName(val name: String) : kotlin.coroutines.AbstractCoroutineContextElement(Key) {
                companion object Key : kotlin.coroutines.CoroutineContext.Key<CoroutineName>
            }
        """), kotlin("""
            package test
            import androidx.compose.runtime.Composable
            import kotlinx.coroutines.*
            import kotlinx.coroutines.Dispatchers.IO as Background
            import kotlinx.coroutines.Dispatchers.Main as Ui
            import java.io.File
            val uiContext = Ui + CoroutineName("IO")
            val workerContext = Ui + Background
            fun screen() {
                withContext(Dispatchers.Main + CoroutineName("IO")) { File("state").length() }
                withContext(Dispatchers.IO + Dispatchers.Main) { File("state").length() }
                withContext(Dispatchers.Main + Dispatchers.IO) { File("state").length() }
                withContext(Background + Ui) { File("state").length() }
                withContext(Ui + CoroutineName("Default")) { File("state").length() }
                withContext(Background + CoroutineName("Main")) { File("state").length() }
                withContext(uiContext) { File("state").length() }
                withContext(workerContext) { File("state").length() }
            }
        """)).allowMissingSdk().run().expectErrorCount(5)
    }

    @Test fun testMainExecutorExecuteAndImmutableListenerAliases() {
        lint().files(mainExecutor, kotlin("""
            package test
            import androidx.core.content.ContextCompat.getMainExecutor as uiExecutor
            import java.util.concurrent.Executor
            import java.io.File
            fun addListener(callback: () -> Unit, executor: Executor) = executor.execute(callback)
            fun observe() {
                uiExecutor(null).execute { File("state").length() }
                val first = uiExecutor(null)
                val second = first
                second.execute { File("state").length() }
                addListener({ File("state").length() }, second)
            }
        """)).allowMissingSdk().run().expectErrorCount(3)
    }

    @Test fun testExtensionLaunchMapsContextWithoutConfusingItsScopeReceiver() {
        lint().files(composable, contexts, kotlin("""
            package kotlinx.coroutines
            class CoroutineScope(val context: kotlin.coroutines.CoroutineContext)
            fun CoroutineScope.launch(context: kotlin.coroutines.CoroutineContext = kotlin.coroutines.EmptyCoroutineContext,
                block: () -> Unit) = block()
        """), kotlin("""
            package test
            import kotlinx.coroutines.*
            import androidx.compose.runtime.Composable
            import java.io.File
            val scope = CoroutineScope(Dispatchers.Main)
            @Composable fun screen() {
                scope.launch(Dispatchers.IO) { File("state").length() }
                scope.launch(Dispatchers.Main) { File("state").length() }
                scope.launch(context = Dispatchers.IO) { File("state").length() }
                scope.launch { File("state").length() }
            }
        """)).allowMissingSdk().run().expectErrorCount(2)
    }
}
