package io.github.anastaci1a.localica.diagnostics

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import io.github.anastaci1a.localica.lastfm.LastFmMatchingReport
import io.github.anastaci1a.localica.lastfm.SavedLastFm
import io.github.anastaci1a.localica.library.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal object LastFmMatchingExport {
    suspend fun prepare(context: Context, saved: SavedLastFm, library: List<Track>): Intent {
        val json = withContext(Dispatchers.Default) { LastFmMatchingReport.json(saved.snapshot, library, saved.mappings) }
        val file = withContext(Dispatchers.IO) {
            val directory = File(context.cacheDir, "${DiagnosticsExport.DirectoryName}/lastfm-matching").apply { mkdirs() }
            File(directory, "localica-lastfm-matching.json").also { it.writeText(json, Charsets.UTF_8) }
        }
        return shareIntent(context, file)
    }

    internal fun shareIntent(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(context, DiagnosticsExport.authority(context), file)
        val send = Intent(Intent.ACTION_SEND).setType("application/json")
            .putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_SUBJECT, "Last.fm matching report")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri("Last.fm matching report", uri)
        return Intent.createChooser(send, "Export matching report").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
