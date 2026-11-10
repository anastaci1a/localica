package io.github.anastaci1a.localica.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp

/** Space covered by floating controls, applied inside scroll content rather than to its viewport. */
internal val LocalBrowseObscuredInset = staticCompositionLocalOf { 0.dp }

/** The panel owning the added scroll room; nested windows may replace this owner. */
internal val LocalBrowseSelectionPanel = staticCompositionLocalOf<FloatingSelectionPanelState?> { null }
