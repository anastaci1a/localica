package io.github.anastaci1a.localica

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.anastaci1a.localica.library.AndroidLibraryQueryPreferences
import io.github.anastaci1a.localica.playback.PlaybackConnection
import io.github.anastaci1a.localica.ui.LibraryViewModel
import io.github.anastaci1a.localica.ui.LocalicaScreen
import io.github.anastaci1a.localica.ui.LocalicaTheme

class MainActivity : ComponentActivity() {
    private lateinit var model: LibraryViewModel
    private var scannerRequest by mutableIntStateOf(0)
    private fun consumeScannerIntent(intent: Intent?) {
        if (intent?.getBooleanExtra("show-tag-jobs", false) == true) {
            (application as LocalicaApplication).tagEditing.showJobs(); intent.removeExtra("show-tag-jobs")
        }
        if (intent?.getBooleanExtra("show-scanner", false) == true) { scannerRequest++; intent.removeExtra("show-scanner") }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        model = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                LibraryViewModel((application as LocalicaApplication).library, PlaybackConnection(applicationContext),
                    (application as LocalicaApplication).playlists, (application as LocalicaApplication).scanner, (application as LocalicaApplication).statistics.counts) as T
        })[LibraryViewModel::class.java]
        consumeScannerIntent(intent)
        if (scannerRequest == 0 && io.github.anastaci1a.localica.scanner.ScanPreferences(this).scanOnLaunch) {
            lifecycleScope.launch {
                model.library.first { !it.loading }
                model.scanNewFiles()
            }
        }
        setContent {
            val library by model.library.collectAsStateWithLifecycle()
            val playback by model.playback.state.collectAsStateWithLifecycle()
            val queues by model.playback.queues.state.collectAsStateWithLifecycle()
            val playlist by model.playlist.collectAsStateWithLifecycle()
            val picker = rememberLauncherForActivityResult(LocalFolderPicker()) { uri ->
                uri?.let { model.addFolder(it.toString()) }
            }
            LocalicaTheme {
                io.github.anastaci1a.localica.ui.TagEditorHost((application as LocalicaApplication).tagEditing) {
                LocalicaScreen(library, playback, model::play, model.playback::toggle, model.playback::seekTo,
                    onChooseFolder = { picker.launch(library.snapshot.folders.firstOrNull()?.uri?.let(Uri::parse)) },
                    onRescan = model::scan, onCancelScan = model::cancelScan,
                    onRemoveFolder = model::removeFolder,
                    queues = queues, queueController = model.playback.queues,
                    onPrevious = model.playback::previous, onNext = model.playback::next,
                    playlist = playlist, playlistController = model.playlists,
                    onScanAll = model::scanAll, scannerRequest = scannerRequest, onRetryPlayback = model.playback::retry)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::model.isInitialized) model.refreshAccess()
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); consumeScannerIntent(intent) }
}

private class LocalFolderPicker : ActivityResultContracts.OpenDocumentTree() {
    override fun createIntent(context: Context, input: Uri?): Intent = super.createIntent(context, input)
        .putExtra(Intent.EXTRA_LOCAL_ONLY, true)
}
