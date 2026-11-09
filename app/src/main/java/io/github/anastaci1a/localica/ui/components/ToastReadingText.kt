package io.github.anastaci1a.localica.ui.components

import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.text.style.TextDirection
import io.github.anastaci1a.localica.ui.engine.*
import io.github.anastaci1a.localica.ui.spec.*

/** One finite required reading pass; accessible semantics retain the full untruncated message. */
@Composable internal fun ToastReadingText(key: Any, message: String, paused: Boolean, style: TextStyle,
    pending: () -> Unit, completed: () -> Unit, ready: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val measure = rememberTextMeasurer()
    val paragraph = measure.measure(message, style.copy(textDirection = TextDirection.Content),
        maxLines = 1, softWrap = false)
    val spec = LocalUiSpec.current.spec.motion.getValue(MotionProfile.Marquee) as MarqueeSpec
    val speed = with(LocalDensity.current) { spec.speedDpPerSecond * density }
    val rtl = message.isNotEmpty() && paragraph.getParagraphDirection(0) == ResolvedTextDirection.Rtl
    val ink = LocalContentColor.current
    ReadingTextHost(key, message, paragraph, speed, paused, pending, completed, ready, ink, rtl, modifier)
}
