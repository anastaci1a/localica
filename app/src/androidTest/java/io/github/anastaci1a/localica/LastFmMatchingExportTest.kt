package io.github.anastaci1a.localica

import android.content.Intent
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.diagnostics.LastFmMatchingExport
import io.github.anastaci1a.localica.lastfm.SavedLastFm
import io.github.anastaci1a.localica.lastfm.LastFmSnapshot
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LastFmMatchingExportTest {
    @Suppress("DEPRECATION")
    @Test fun nativeShareSheetReceivesReadableJsonWithOnlyReadPermission() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val chooser = LastFmMatchingExport.prepare(context, SavedLastFm(snapshot = LastFmSnapshot("PRIVATE_ACCOUNT", 1, emptyList())), emptyList())
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, send.action); assertEquals("application/json", send.type)
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(0, send.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val uri = send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
        assertEquals("content", uri.scheme); assertEquals(uri, send.clipData!!.getItemAt(0).uri)
        val text = context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
        assertEquals("localica.lastfm-matching", JSONObject(text).getString("format"))
        assertFalse(text.contains("PRIVATE_ACCOUNT"))
    }
}
