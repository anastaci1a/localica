package io.github.anastaci1a.localica

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.system.ErrnoException
import android.system.OsConstants
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnitRunner
import java.io.File
import java.io.FileDescriptor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/**
 * Ordinary invocations delegate unchanged to AndroidJUnitRunner. Only an explicit resident
 * opt-in selects the separate admission operation. Platform finish force-stops the target on
 * API 26; the resident path never calls it, finishes Activities, or reports terminal success.
 * The bounded operation
 * writes its own completion receipt; the ordinary app looper and instrumentation remain alive.
 * Host must prove a fresh, never-launched target before startup (which itself force-stops targets).
 * No retry, recovery, uninstall or process-lifetime guarantee is provided here.
 */
class NavigationRandomOwnedSession : AndroidJUnitRunner() {
    private var supplied: Bundle? = null
    private var residentMode = false
    internal val isResidentInvocation: Boolean get() = residentMode
    @Volatile private var claimedRoot: File? = null
    private val finishRefused = AtomicBoolean(false)
    private val resumed = mutableSetOf<Activity>()

    override fun callApplicationOnCreate(app: Application) {
        if (residentMode) app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) { resumed += activity }
            override fun onActivityPaused(activity: Activity) { resumed -= activity }
            override fun onActivityDestroyed(activity: Activity) { resumed -= activity }
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
        })
        super.callApplicationOnCreate(app)
    }

    internal fun hasResumedMainActivity(): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        return resumed.size == 1 && resumed.single() is MainActivity
    }

    override fun onCreate(arguments: Bundle?) {
        supplied = arguments?.let(::Bundle)
        residentMode = arguments?.containsKey("localicaOwnedSession") == true
        if (residentMode) {
            // Reject extra listener/debug/orchestrator arguments before AndroidJUnitRunner parses them.
            // Invalid resident input still takes only our refusing worker, never the ordinary path.
            try { NavigationRandomOwnedSessionProtocol.validate(argumentValues(requireNotNull(supplied))) }
            catch (_: Exception) { start(); return }
        }
        // The pinned parent registers monitoring and starts exactly one instrumentation worker.
        super.onCreate(arguments ?: Bundle())
    }

    override fun onStart() {
        if (!residentMode) { super.onStart(); return }
        var directory: File? = null
        var sessionId: String? = null
        val started = SystemClock.uptimeMillis()
        try {
            val args = requireNotNull(supplied) { "Missing resident-session arguments" }
            val values = argumentValues(args)
            sessionId = NavigationRandomOwnedSessionProtocol.validate(values)
            val ready = CountDownLatch(1)
            check(Handler(Looper.getMainLooper()).post { ready.countDown() }) { "Main looper rejected readiness barrier" }
            check(ready.await(30, TimeUnit.SECONDS)) { "Application initialization did not reach main-looper barrier" }
            check(targetContext.packageName == "io.github.anastaci1a.localica.debug") { "Wrong target package" }
            check(targetContext.applicationInfo.uid == Process.myUid()) { "Not executing as target UID" }
            check(targetContext.applicationContext is LocalicaApplication) { "Wrong target application" }
            // Canonicalize the framework-provided base once: /data/user/0 may alias /data/data.
            val files = targetContext.filesDir.canonicalFile
            requireOwnedDirectory(files)
            val root = File(files, NavigationRandomOwnedSessionProtocol.DIRECTORY)
            // mkdir is the exclusive one-shot claim. EEXIST refuses reuse, including partial failure.
            Os.mkdir(root.path, 448) // 0700; intentionally private, not shared-workspace permissions.
            directory = root
            claimedRoot = root
            requireOwnedDirectory(root)
            writeReceipt(root, "started.json", receipt("STARTED", sessionId, started))
            syncDirectory(files)
            // Instrumentation startup revokes transient grants. Host offers them only after STARTED,
            // then atomically publishes this session-bound private declaration before continuing.
            awaitHostReady(root, sessionId)
            writeReceipt(root, "host-ready-accepted.json", receipt("HOST_READY_DECLARATION_ACCEPTED", sessionId, started))
            InstrumentationRegistry.registerInstance(this, args)
            NavigationRandomCorpusOwnedTargetTest().verifyOwnedCorpusAndPersistQueue()
            writeReceipt(root, "operation.json", receipt("OPERATION_RETURNED", sessionId, started).apply {
                put("operationAssertionsReturned", true)
                put("phaseEvidenceRequiresIndependentReconciliation", true)
            })
        } catch (failure: Throwable) {
            val error = boundedFailure(failure)
            Log.e("LocalicaOwnedSession", error)
            directory?.let { root ->
                try {
                    writeReceipt(root, "failure.json", receipt("FAILED", sessionId, started).put("error", error))
                } catch (receiptFailure: Throwable) {
                    Log.e("LocalicaOwnedSession", "Failure receipt unavailable: " + boundedFailure(receiptFailure))
                }
            }
        }
        // Returning from onStart ends only this worker. Do not call finish or install a watchdog kill.
    }

    override fun finish(resultCode: Int, results: Bundle?) {
        if (!residentMode) { super.finish(resultCode, results); return }
        // Defensive refusal: terminal instrumentation finish would kill the admitted application.
        Log.e("LocalicaOwnedSession", "Terminal finish refused in resident session; code=$resultCode")
        if (finishRefused.compareAndSet(false, true)) claimedRoot?.let { root ->
            try {
                writeReceipt(root, "terminal-finish-refused.json", JSONObject().apply {
                    put("schema", 1); put("state", "PROTOCOL_VIOLATION")
                    put("resultCode", resultCode); put("instrumentationFinished", false)
                })
            } catch (failure: Throwable) { Log.e("LocalicaOwnedSession", boundedFailure(failure)) }
        }
    }

    private fun awaitHostReady(root: File, sessionId: String) {
        val file = File(root, NavigationRandomOwnedSessionProtocol.HOST_READY_FILE)
        val deadline = SystemClock.uptimeMillis() + NavigationRandomOwnedSessionProtocol.HOST_READY_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            requireOwnedDirectory(root)
            val stat = try { Os.lstat(file.path) } catch (failure: ErrnoException) {
                if (failure.errno != OsConstants.ENOENT) throw failure
                Thread.sleep(100)
                continue
            }
            require(OsConstants.S_ISREG(stat.st_mode) && stat.st_uid == Process.myUid() &&
                stat.st_gid == Process.myUid() && stat.st_nlink == 1L && stat.st_size == 36L &&
                (stat.st_mode and 4095) == 384) { "Unsafe host-ready declaration" }
            val fd = Os.open(file.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or
                OsConstants.O_NONBLOCK or OsConstants.O_CLOEXEC, 0)
            withOwnedDescriptor(fd) {
                fun unchanged() {
                    for (current in listOf(Os.fstat(fd), Os.lstat(file.path))) {
                        require(current.st_dev == stat.st_dev && current.st_ino == stat.st_ino &&
                            current.st_mode == stat.st_mode && current.st_uid == stat.st_uid &&
                            current.st_gid == stat.st_gid && current.st_nlink == stat.st_nlink &&
                            current.st_size == stat.st_size && current.st_mtime == stat.st_mtime &&
                            current.st_ctime == stat.st_ctime) { "Host-ready declaration changed" }
                    }
                }
                unchanged()
                val bytes = ByteArray(37)
                var count = 0
                while (count < bytes.size) {
                    val n = Os.read(fd, bytes, count, bytes.size - count)
                    if (n == 0) break
                    check(n > 0)
                    count += n
                }
                unchanged()
                NavigationRandomOwnedSessionProtocol.validateHostReady(bytes.copyOf(count), sessionId)
            }
            return
        }
        error("Host-ready deadline elapsed; session remains resident, no operation or retry")
    }

    private fun argumentValues(args: Bundle): Map<String, String> {
        require(args.keySet() == NavigationRandomOwnedSessionProtocol.keys) { "Unexpected argument keys" }
        return args.keySet().associateWith { key ->
            val value = args.get(key)
            require(value is String) { "Session arguments must be strings" }
            value
        }
    }

    private fun receipt(state: String, id: String?, started: Long) = JSONObject().apply {
        put("schema", 1)
        put("state", state)
        put("sessionId", id ?: JSONObject.NULL)
        put("pid", Process.myPid())
        put("uid", Process.myUid())
        put("startedUptimeMs", started)
        put("observedUptimeMs", SystemClock.uptimeMillis())
        put("instrumentationFinished", false)
        put("processSurvivalProven", false)
        put("terminalJUnitResult", false)
    }

    private fun requireOwnedDirectory(file: File) {
        val stat = Os.lstat(file.path)
        require(OsConstants.S_ISDIR(stat.st_mode) && stat.st_uid == Process.myUid()) { "Require target-owned directory" }
        require(file.canonicalPath == file.absolutePath) { "Directory alias is not permitted" }
    }

    private fun writeReceipt(root: File, name: String, value: JSONObject) {
        requireOwnedDirectory(root)
        val bytes = (value.toString() + "\n").toByteArray(Charsets.UTF_8)
        require(bytes.size <= NavigationRandomOwnedSessionProtocol.MAX_RECEIPT_BYTES)
        val fd = Os.open(File(root, name).path,
            OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or
                OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC, 384) // 0600
        withOwnedDescriptor(fd) {
            var offset = 0
            while (offset < bytes.size) {
                val written = Os.write(fd, bytes, offset, bytes.size - offset)
                check(written > 0) { "Receipt write made no progress" }
                offset += written
            }
            Os.fsync(fd)
        }
        syncDirectory(root)
    }

    private fun syncDirectory(directory: File) {
        val fd = Os.open(directory.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or
            OsConstants.O_CLOEXEC or OsConstants.O_NONBLOCK, 0)
        withOwnedDescriptor(fd) {
            require(OsConstants.S_ISDIR(Os.fstat(fd).st_mode))
            Os.fsync(fd)
        }
    }

    private inline fun withOwnedDescriptor(fd: FileDescriptor, body: () -> Unit) {
        var primary: Throwable? = null
        try { body() } catch (failure: Throwable) { primary = failure; throw failure }
        finally {
            try { Os.close(fd) } catch (failure: Throwable) {
                if (primary == null) throw failure
                if (primary !== failure) primary.addSuppressed(failure)
            }
        }
    }

    private fun boundedFailure(failure: Throwable): String {
        val output = StringBuilder()
        var current: Throwable? = failure
        repeat(8) {
            val item = current ?: return@repeat
            output.append(item.javaClass.name).append(": ").append(item.message?.take(1024)).append('\n')
            item.stackTrace.take(24).forEach { frame -> output.append(frame.toString().take(256)).append('\n') }
            current = item.cause?.takeIf { it !== item }
        }
        return output.toString().take(16000)
    }
}
