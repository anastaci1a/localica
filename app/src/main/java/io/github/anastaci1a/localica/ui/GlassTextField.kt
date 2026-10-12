package io.github.anastaci1a.localica.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Shared editable semantics/IME behavior with a wrapping label and glass decoration. */
@Composable internal fun GlassTextField(value: String, onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier, label: @Composable (() -> Unit)? = null, singleLine: Boolean = false,
    minLines: Int = 1, maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    isError: Boolean = false, enabled: Boolean = true, keyboardOptions: KeyboardOptions = KeyboardOptions.Default) {
    val ink = LocalContentColor.current
    BasicTextField(value, onValueChange, modifier.fillMaxWidth().clearTextFocusOnBack().semantics { if (isError) error("Invalid value") },
        enabled = enabled, singleLine = singleLine, minLines = minLines, maxLines = maxLines,
        keyboardOptions = keyboardOptions, textStyle = MaterialTheme.typography.bodyLarge.copy(color = ink),
        cursorBrush = SolidColor(ink), decorationBox = { input ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (label != null) Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    CompositionLocalProvider(LocalContentColor provides if (isError) MaterialTheme.colorScheme.error else ink) {
                        ProvideTextStyle(MaterialTheme.typography.bodyMedium) { label() }
                    }
                }
                LiquidGlassSurface(Modifier.fillMaxWidth(), cornerRadius = 20.dp, pressFeedback = false, shadow = false) {
                    Box(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 14.dp)) { input() }
                }
            }
        })
}
