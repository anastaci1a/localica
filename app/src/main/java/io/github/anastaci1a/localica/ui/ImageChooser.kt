package io.github.anastaci1a.localica.ui

import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import io.github.anastaci1a.localica.R
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.editing.CoverImages

/** Optional decoder-entry observation; callbacks run in the decoder context. */
internal val LocalImagePreviewDecodeObserver = staticCompositionLocalOf<(() -> Unit)?> { null }

@Composable
internal fun ImageChooser(onDismiss: () -> Unit, onChosen: (String) -> Unit) {
    ImageChooser(rememberArtworkWorkflow("standalone", startChoosing = true), onDismiss, onChosen)
}

@Composable
internal fun ImageChooser(workflow: ArtworkWorkflow, onDismiss: () -> Unit, onChosen: (String) -> Unit) {
    val context = LocalContext.current
    val images = remember { CoverImages(context) }
    val decodeObserver = LocalImagePreviewDecodeObserver.current
    val traceId = remember { java.util.UUID.randomUUID().toString().take(8) }
    fun trace(message: String) {
        if (io.github.anastaci1a.localica.BuildConfig.DEBUG)
            android.util.Log.d("Chooser660", "id=$traceId target=${workflow.target} $message")
    }
    DisposableEffect(workflow) {
        trace("enter stage=${workflow.stage}")
        onDispose { trace("dispose stage=${workflow.stage}") }
    }
    var revision by remember { mutableIntStateOf(0) }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { revision++ }
    val hasPermission = remember(revision) {
        fun granted(permission: String) = androidx.core.content.ContextCompat.checkSelfPermission(context, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (Build.VERSION.SDK_INT >= 33) granted(Manifest.permission.READ_MEDIA_IMAGES) ||
            (Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))
        else granted(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    val recent by produceState(emptyList<String>(), revision, hasPermission) {
        trace("recent start revision=$revision permission=$hasPermission previous=${value.size}")
        try {
            value = images.recent(includeDevicePhotos = hasPermission)
            trace("recent delivered revision=$revision count=${value.size}")
        } finally { trace("recent end revision=$revision") }
    }
    SideEffect { trace("compose stage=${workflow.stage} revision=$revision recent=${recent.size}") }
    fun Modifier.traceMeasure(label: String): Modifier = layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        trace("measure $label constraints=$constraints size=${placeable.width}x${placeable.height}")
        layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
    }
    var unavailableRecent by remember(recent, revision) { mutableStateOf(false) }
    fun chosen(uri: Uri?) { if (uri != null) {
        runCatching { images.selected(uri.toString()) }; revision++; workflow.crop(uri.toString())
    } }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia(), ::chosen)
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), ::chosen)
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { revision++ }
    if (workflow.stage == ArtworkStage.Chooser) GlassAlertDialog(onDismissRequest = onDismiss, title = { Text("Choose artwork") }, text = {
        Column(Modifier.traceMeasure("body"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (hasPermission && recent.isEmpty()) Text("No recent photos", style = MaterialTheme.typography.bodySmall)
            if (unavailableRecent) Text("Some recent images are unavailable. Choose them again using gallery or files.", style = MaterialTheme.typography.bodySmall)
            val recentScroll = androidx.compose.foundation.lazy.rememberLazyListState()
            if (recent.isNotEmpty()) LazyRow(Modifier.traceMeasure("recent-row").modalScroll(recentScroll), state = recentScroll,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(recent) { uri ->
                    val cover by produceState<android.graphics.Bitmap?>(null, uri, recent, revision) {
                        trace("decode start item=${recent.indexOf(uri)} revision=$revision")
                        value = try { images.decode(uri, 512) { decodeObserver?.invoke() } }
                        catch (cancelled: kotlinx.coroutines.CancellationException) {
                            trace("decode cancelled item=${recent.indexOf(uri)} revision=$revision")
                            throw cancelled
                        }
                        catch (failure: Exception) {
                            trace("decode failed item=${recent.indexOf(uri)} type=${failure.javaClass.simpleName}")
                            null
                        }
                        trace("decode delivered item=${recent.indexOf(uri)} revision=$revision bitmap=${value?.let { "${it.width}x${it.height}" }}")
                        if (value == null) unavailableRecent = true
                    }
                    cover?.let { Image(it.asImageBitmap(), "Choose recent image", Modifier.testTag("recent-artwork-$uri").size(104.dp).clip(RoundedCornerShape(16.dp)).clickable { chosen(Uri.parse(uri)) }, contentScale = ContentScale.Crop) }
                }
            }
            if (!hasPermission) {
            Text("Allow photo access to also see recent device photos.", style = MaterialTheme.typography.bodySmall)
            GlassActionButton(AppIcon.TabAlbums, "Allow recent photos", Modifier.fillMaxWidth()) { permission.launch(when {
                Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
                Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
                else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            }) }
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            GlassActionButton(AppIcon.TabAlbums, "Open gallery", Modifier.traceMeasure("gallery").fillMaxWidth()) {
                gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
            GlassActionButton(AppIcon.TabFolders, "Browse files", Modifier.traceMeasure("files").fillMaxWidth()) { files.launch(arrayOf("image/*")) }
        }
    }, confirmButton = {}, dismissButton = { ActionTextButton(onClick = onDismiss) { Text("Cancel") } })
    workflow.cropUri?.let { uri ->
        DisposableEffect(uri) {
            val lease = images.retain(uri)
            onDispose { lease.close() }
        }
        ArtworkCrop(uri, workflow::cropCancelled, onChosen)
    }
}
