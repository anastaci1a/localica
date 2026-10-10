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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        model = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                LibraryViewModel((application as LocalicaApplication).library, PlaybackConnection(applicationContext),
                    (application as LocalicaApplication).playlists) as T
        })[LibraryViewModel::class.java]
        setContent {
            val library by model.library.collectAsStateWithLifecycle()
            val playback by model.playback.state.collectAsStateWithLifecycle()
            val queues by model.playback.queues.state.collectAsStateWithLifecycle()
            val playlist by model.playlist.collectAsStateWithLifecycle()
            val picker = rememberLauncherForActivityResult(LocalFolderPicker()) { uri ->
                uri?.let { model.scan(it.toString()) }
            }
            LocalicaTheme {
                LocalicaScreen(library, playback, model::play, model.playback::toggle, model.playback::seekTo,
                    onChooseFolder = { picker.launch(library.snapshot.folders.firstOrNull()?.uri?.let(Uri::parse)) },
                    onRescan = model::scan, onCancelScan = model::cancelScan,
                    onRemoveFolder = model::removeFolder,
                    queues = queues, queueController = model.playback.queues,
                    onPrevious = model.playback::previous, onNext = model.playback::next,
                    playlist = playlist, playlistController = model.playlists)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::model.isInitialized) model.refreshAccess()
    }
}

private class LocalFolderPicker : ActivityResultContracts.OpenDocumentTree() {
    override fun createIntent(context: Context, input: Uri?): Intent = super.createIntent(context, input)
        .putExtra(Intent.EXTRA_LOCAL_ONLY, true)
}
