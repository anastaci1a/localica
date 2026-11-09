package io.github.anastaci1a.localica.ui

import androidx.compose.runtime.staticCompositionLocalOf
import io.github.anastaci1a.localica.toast.ToastQueue

/** One application queue, also captured by the existing dialog-local context. */
internal val LocalToastQueue = staticCompositionLocalOf<ToastQueue?> { null }
