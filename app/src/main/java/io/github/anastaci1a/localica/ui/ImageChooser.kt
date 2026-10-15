package io.github.anastaci1a.localica.ui

import android.Manifest
import android.graphics.BitmapFactory
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.editing.CoverImages

@Composable
internal fun ImageChooser(onDismiss: () -> Unit, onChosen: (String) -> Unit) {
    val context = LocalContext.current
    val images = remember { CoverImages(context) }
    var cropUri by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { revision++ }
    val hasPermission = remember(revision) {
        fun granted(permission: String) = androidx.core.content.ContextCompat.checkSelfPermission(context, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (Build.VERSION.SDK_INT >= 33) granted(Manifest.permission.READ_MEDIA_IMAGES) ||
            (Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))
        else granted(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    val recent by produceState(emptyList<String>(), revision, hasPermission) { value = images.recent(includeDevicePhotos = hasPermission) }
    var unavailableRecent by remember(recent, revision) { mutableStateOf(false) }
    fun chosen(uri: Uri?) { if (uri != null) {
        runCatching { images.selected(uri.toString()) }; revision++; cropUri = uri.toString()
    } }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia(), ::chosen)
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), ::chosen)
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { revision++ }
    if (cropUri == null) GlassAlertDialog(onDismissRequest = onDismiss, title = { Text("Choose artwork") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (hasPermission && recent.isEmpty()) Text("No recent photos", style = MaterialTheme.typography.bodySmall)
            if (unavailableRecent) Text("Some recent images are unavailable. Choose them again using gallery or files.", style = MaterialTheme.typography.bodySmall)
            if (recent.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(recent) { uri ->
                    val cover by produceState<android.graphics.Bitmap?>(null, uri, recent, revision) {
                        value = try { images.read(uri, 512).bytes.let { BitmapFactory.decodeByteArray(it, 0, it.size) } }
                        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                        catch (_: Exception) { null }
                        if (value == null) unavailableRecent = true
                    }
                    cover?.let { Image(it.asImageBitmap(), "Choose recent image", Modifier.size(104.dp).clip(RoundedCornerShape(16.dp)).clickable { chosen(Uri.parse(uri)) }, contentScale = ContentScale.Crop) }
                }
            }
            if (!hasPermission) {
            Text("Allow photo access to also see recent device photos.", style = MaterialTheme.typography.bodySmall)
            GlassActionButton(R.drawable.ic_albums, "Allow recent photos", Modifier.fillMaxWidth()) { permission.launch(when {
                Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
                Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
                else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            }) }
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            GlassActionButton(io.github.anastaci1a.localica.R.drawable.ic_albums, "Open gallery", Modifier.fillMaxWidth()) {
                gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
            GlassActionButton(R.drawable.ic_folder, "Browse files", Modifier.fillMaxWidth()) { files.launch(arrayOf("image/*")) }
        }
    }, confirmButton = {}, dismissButton = { ActionTextButton(onClick = onDismiss) { Text("Cancel") } })
    cropUri?.let { uri ->
        DisposableEffect(uri) {
            val lease = images.retain(uri)
            onDispose { lease.close() }
        }
        ArtworkCrop(uri, { cropUri = null }, onChosen)
    }
}
