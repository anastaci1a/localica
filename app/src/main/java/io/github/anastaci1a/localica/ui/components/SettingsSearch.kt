package io.github.anastaci1a.localica.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import io.github.anastaci1a.localica.ui.engine.SettingsHighlightView
import io.github.anastaci1a.localica.ui.engine.SettingsScrollMotion
import io.github.anastaci1a.localica.ui.engine.rememberSettingsHighlight as rememberHighlightOwner
import io.github.anastaci1a.localica.ui.engine.rememberSettingsScroll as rememberScrollOwner

/** Settings rows receive read-only presentation; its finite pulse belongs to the root clock. */
@Stable internal class SettingsHighlight(private val presentation: SettingsHighlightView) {
    fun value(): Float = presentation.value()
    fun ContentDrawScope.draw(tint: Color) { with(presentation) { this@draw.draw(tint) } }
}

@Composable internal fun rememberSettingsHighlight(shown: Boolean, reduced: Boolean): SettingsHighlight {
    val owner = rememberHighlightOwner(shown, reduced)
    return remember(owner) { SettingsHighlight(owner) }
}

/** Navigation submits a destination; the Engine owner drives the existing native scroll mutator. */
@Stable internal class SettingsScroll(private val owner: SettingsScrollMotion) {
    suspend fun scrollTo(target: Int) { owner.scrollTo(target) }
}

@Composable internal fun rememberSettingsScroll(scroll: ScrollState): SettingsScroll {
    val owner = rememberScrollOwner(scroll)
    return remember(owner) { SettingsScroll(owner) }
}
