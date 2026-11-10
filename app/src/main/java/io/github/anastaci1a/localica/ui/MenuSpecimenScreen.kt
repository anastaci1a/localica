package io.github.anastaci1a.localica.ui

import androidx.compose.runtime.Composable
import io.github.anastaci1a.localica.ui.components.ControlProgress
import io.github.anastaci1a.localica.ui.menu.MenuPageHost
import io.github.anastaci1a.localica.ui.pages.PageId
import io.github.anastaci1a.localica.ui.pages.PageSource
import io.github.anastaci1a.localica.ui.pages.SpecimenPreview
import io.github.anastaci1a.localica.ui.pages.SpecimenSource

/** Root wiring of one declared page: the state holder runs here, `declare` runs on every recomposition. */
@Composable
internal fun <S> MenuPage(source: PageSource<S>, onNavigate: (String) -> Unit = {}, previews: Map<String, @Composable () -> Unit> = emptyMap()) {
    MenuPageHost(source.declare(source.state()), onNavigate, previews)
}

private val specimenPreviews: Map<String, @Composable () -> Unit> = mapOf(SpecimenPreview.Swatch.key to { ControlProgress({ 1f }) })

/** #757 specimen page. Not linked from any shipped page; debug builds reach it through `MenuSpecimenActivity`. */
@Composable
internal fun MenuSpecimenScreen(onClose: () -> Unit) {
    SettingsPage(PageId.Specimen.title, onClose) { MenuPage(SpecimenSource, previews = specimenPreviews) }
}
