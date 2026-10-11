package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun LibraryLicenses() {
    var open by remember { mutableStateOf(false) }
    val context = LocalContext.current
    TextButton(onClick = { open = true }) { Text("Open-source licenses") }
    if (open) {
        val text by produceState("Loading licenses…") { value = withContext(Dispatchers.IO) {
            listOf("NOTICE.txt", "inter-OFL.txt", "jaudiotagger-LGPL-2.1.txt").joinToString("\n\n") {
                context.assets.open("licenses/$it").bufferedReader().use { reader -> reader.readText() }
            }
        } }
        GlassAlertDialog(onDismissRequest = { open = false }, title = { Text("Open-source licenses") },
            text = { Text(text, Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall) },
            confirmButton = { TextButton(onClick = { open = false }) { Text("Close") } })
    }
}
