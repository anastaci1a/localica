package io.github.anastaci1a.localica

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executors

/**
 * Explicit fixed-action bridge, protected by manifest DUMP plus OwnedRoot's private host
 * authorization. DUMP is a privileged/development guard, not proof of a Shell caller or a fresh
 * owned device. The lead must preflight its existing availability; there is no weaker fallback.
 * Activity launch/finish and log messages are not completion receipts or target persisted grants.
 */
class NavigationRandomCorpusGrantActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Recreation/process restoration is not authority to replay a host operation.
        if (savedInstanceState != null) {
            finish()
            return
        }
        val accepted = try {
            NavigationRandomCorpusOwnedRoot.isFixedRequest(intent)
        } catch (_: RuntimeException) {
            false
        }
        if (!accepted) {
            Log.w(TAG, "request rejected")
            finish()
            return
        }
        val action = requireNotNull(intent.action)
        val appContext = applicationContext
        // Never infer launcher identity from Binder.getCallingUid/getCallingPackage here.
        // Only the fixed action crosses this boundary; all paths, pins and targets stay internal.
        worker.execute {
            try {
                val completion = NavigationRandomCorpusOwnedRoot.execute(appContext, action)
                Log.i(TAG, "action=$action status=${completion.status} category=${completion.category}")
            } catch (_: Exception) {
                // Do not log private inputs or manufacture a receipt when execution cannot return one.
                Log.e(TAG, "action=$action status=failed category=unhandled-exception")
            } finally {
                main.post { finish() }
            }
        }
    }

    private companion object {
        const val TAG = "NavigationCorpusBridge"
        val main = Handler(Looper.getMainLooper())
        // One process-local worker, shared across bridge instances; OwnedRoot also serializes state.
        val worker = Executors.newSingleThreadExecutor { task ->
            Thread(task, "navigation-corpus-bridge").apply { isDaemon = true }
        }
    }
}
