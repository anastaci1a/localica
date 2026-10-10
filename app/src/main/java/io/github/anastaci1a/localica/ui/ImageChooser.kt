package io.github.anastaci1a.localica.ui

import android.Manifest
import android.content.Intent
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.editing.CoverImages

@Composable
internal fun ImageChooser(onDismiss: () -> Unit, onChosen: (String) -> Unit) {
    val context = LocalContext.current
    val images = remember { CoverImages(context) }
    var revision by remember { mutableIntStateOf(0) }
    val recent by produceState(emptyList<String>(), revision) { value = images.recent() }
    fun chosen(uri: Uri?) { if (uri != null) {
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        images.remember(uri.toString()); onChosen(uri.toString())
    } }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia(), ::chosen)
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), ::chosen)
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { revision++ }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Choose artwork") }, text = {
        Column {
            Text("Recent images")
            if (recent.isEmpty()) Text("Choose an image or allow access to show recent photos.", style = MaterialTheme.typography.bodySmall)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(recent) { uri ->
                    val cover by produceState<android.graphics.Bitmap?>(null, uri) {
                        value = runCatching { images.read(uri, 128).bytes.let { BitmapFactory.decodeByteArray(it, 0, it.size) } }.getOrNull()
                    }
                    cover?.let { Image(it.asImageBitmap(), "Choose recent image", Modifier.size(80.dp).clickable { chosen(Uri.parse(uri)) }, contentScale = ContentScale.Crop) }
                }
            }
            TextButton(onClick = { permission.launch(when {
                Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
                Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
                else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            }) }) { Text("Allow recent photos") }
            TextButton(onClick = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text("Open gallery") }
            TextButton(onClick = { files.launch(arrayOf("image/*")) }) { Text("Browse files") }
        }
    }, confirmButton = {}, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
