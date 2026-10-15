package io.github.anastaci1a.localica

import android.content.Intent
import android.net.Uri
import android.os.*
import android.provider.DocumentsContract
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.editing.ArtworkGrants
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class ArtworkGrantsTest {
    @Test fun realPersistedGrantsRemainBoundedProtectRootAndSurviveOwnerRestart() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val resolver = context.contentResolver
        val authority = ArtworkGrantActivity.AUTHORITY
        fun uri(id: Int) = "content://$authority/document/$id"
        val root = Uri.parse("content://$authority/tree/root")
        val prefs = context.getSharedPreferences("artwork-grant-test", 0)
        var recent = emptyList<String>()
        fun persisted() = resolver.persistedUriPermissions.map { it.uri.toString() }.toSet()
        fun owner() = ArtworkGrants(
            { prefs.getStringSet("owned", emptySet())!!.toSet() },
            { prefs.edit().putStringSet("owned", it).commit() }, { recent.toSet() },
            { it in persisted() }, { !DocumentsContract.isTreeUri(Uri.parse(it)) },
            { resolver.takePersistableUriPermission(Uri.parse(it), Intent.FLAG_GRANT_READ_URI_PERMISSION) },
            { if (it in persisted()) resolver.releasePersistableUriPermission(Uri.parse(it), Intent.FLAG_GRANT_READ_URI_PERMISSION) },
        )
        try {
            val completed = CompletableDeferred<Unit>()
            context.startActivity(Intent().setClassName(instrumentation.context.packageName, ArtworkGrantActivity::class.java.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("completion", object : ResultReceiver(Handler(Looper.getMainLooper())) {
                    override fun onReceiveResult(resultCode: Int, resultData: Bundle?) { completed.complete(Unit) }
                }))
            withTimeout(10000) { completed.await() }
            resolver.takePersistableUriPermission(root, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val original = owner()
            fun choose(id: Int) { recent = (listOf(uri(id)) + recent).distinct().take(12); original.selected(uri(id)) }
            choose(0)
            val crop = original.retain(uri(0)); val reader = original.retain(uri(0))
            resolver.openInputStream(Uri.parse(uri(0)))!!.use { stream ->
                repeat(150) { choose(it + 1) }
                assertTrue(uri(0) in persisted())
                assertEquals(137, stream.read())
                crop.close(); assertTrue(uri(0) in persisted())
            }
            reader.close(); assertFalse(uri(0) in persisted())
            assertEquals(12, persisted().count { it.startsWith("content://$authority/document/") })
            recent.forEach { resolver.openInputStream(Uri.parse(it))!!.use { stream -> assertEquals(137, stream.read()) } }
            assertTrue(root.toString() in persisted())
            // A restart drops process-local leases but reloads persisted ownership.
            recent = emptyList(); owner().prune()
            assertFalse(persisted().any { it.startsWith("content://$authority/document/") })
            assertTrue(root.toString() in persisted())
        } finally {
            resolver.persistedUriPermissions.filter { it.uri.authority == authority }.forEach {
                resolver.releasePersistableUriPermission(it.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            prefs.edit().clear().commit()
        }
    }
    @Test fun productionLedgerAndBudgetKeepTransientImageDecodable() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val base = instrumentation.targetContext
        val prefs = base.getSharedPreferences("artwork-production-" + java.util.UUID.randomUUID(), 0)
        val context = object : android.content.ContextWrapper(base) {
            override fun getApplicationContext(): android.content.Context = this
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences =
                if (name == "cover-images") prefs else super.getSharedPreferences(name, mode)
        }
        val resolver = base.contentResolver
        val authority = ArtworkGrantActivity.AUTHORITY
        fun uri(id: Int) = "content://$authority/document/$id"
        val root = Uri.parse("content://$authority/tree/root")
        val completed = CompletableDeferred<Unit>()
        try {
            base.startActivity(Intent().setClassName(instrumentation.context.packageName, ArtworkGrantActivity::class.java.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("completion", object : ResultReceiver(Handler(Looper.getMainLooper())) {
                    override fun onReceiveResult(resultCode: Int, resultData: Bundle?) { completed.complete(Unit) }
                }))
            withTimeout(10000) { completed.await() }
            resolver.takePersistableUriPermission(root, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val images = io.github.anastaci1a.localica.editing.CoverImages(context)
            images.selected(uri(0))
            val acquired = org.json.JSONArray(prefs.getString("owned-read-grants", "[]"))
            assertTrue((0 until acquired.length()).any { acquired.getString(it) == uri(0) })
            val decoded = images.read(uri(0))
            assertEquals(32, decoded.width); assertEquals(16, decoded.height)
            // Fill only to the conservative admission budget, never to the platform cap.
            for (id in 1..100) {
                if (resolver.persistedUriPermissions.size >= 64) break
                resolver.takePersistableUriPermission(Uri.parse(uri(id)), Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            assertTrue(resolver.persistedUriPermissions.size >= 64)
            images.selected(uri(159))
            assertFalse(resolver.persistedUriPermissions.any { it.uri.toString() == uri(159) })
            val ledger = org.json.JSONArray(prefs.getString("owned-read-grants", "[]"))
            assertFalse((0 until ledger.length()).any { ledger.getString(it) == uri(159) })
            images.retain(uri(159)).use {
                val transient = images.read(uri(159))
                assertEquals(32, transient.width); assertEquals(16, transient.height)
                assertTrue(images.recent(false).contains(uri(159)))
            }
            assertTrue(resolver.persistedUriPermissions.any { it.uri == root })
        } finally {
            resolver.persistedUriPermissions.filter { it.uri.authority == authority }.forEach {
                resolver.releasePersistableUriPermission(it.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            prefs.edit().clear().commit()
        }
    }

}
