package io.github.anastaci1a.localica.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.R

/** All editors share clear/focus/keyboard behavior; the trailing action is never a bubble. */
@Composable internal fun ClearableTextField(value: String, onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier, label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null, leadingIcon: @Composable (() -> Unit)? = null,
    singleLine: Boolean = false,
    minLines: Int = 1, maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    isError: Boolean = false, enabled: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    colors: TextFieldColors = TextFieldDefaults.colors()) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val visible by animateFloatAsState(if (value.isEmpty()) 0f else 1f,
        tween(if (LocalReducedMotion.current) 0 else 140), label = "Clear input")
    TextField(value, onValueChange, modifier.focusRequester(focus).clearTextFocusOnBack(),
        label = label, placeholder = placeholder, leadingIcon = leadingIcon,
        trailingIcon = {
            if (value.isNotEmpty() || visible > 0f) ActionIconButton(enabled = enabled && value.isNotEmpty(),
                onClick = { onValueChange(""); focus.requestFocus(); keyboard?.show() },
                modifier = Modifier.graphicsLayer { alpha = visible }) {
                Icon(painterResource(R.drawable.ic_close), "Clear text", Modifier.size(22.dp))
            }
        }, singleLine = singleLine, minLines = minLines, maxLines = maxLines,
        isError = isError, enabled = enabled, keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions, colors = colors)
}

@Composable internal fun GlassTextField(value: String, onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier, label: @Composable (() -> Unit)? = null, singleLine: Boolean = false,
    minLines: Int = 1, maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    isError: Boolean = false, enabled: Boolean = true, keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default) {
    val ink = LocalContentColor.current
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LiquidGlassSurface(Modifier.fillMaxWidth(), cornerRadius = 28.dp, pressFeedback = false, shadow = false) {
            ClearableTextField(value, onValueChange, Modifier.fillMaxWidth(), label = label, singleLine = singleLine,
                minLines = minLines, maxLines = maxLines, isError = isError, enabled = enabled,
                keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
                colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent, disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent, focusedTextColor = ink, unfocusedTextColor = ink))
        }
    }
}
