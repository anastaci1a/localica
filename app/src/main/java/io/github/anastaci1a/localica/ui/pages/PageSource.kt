package io.github.anastaci1a.localica.ui.pages

import androidx.compose.runtime.Composable
import io.github.anastaci1a.localica.ui.layout.menu.MenuIcon
import io.github.anastaci1a.localica.ui.layout.menu.PageDraft
import io.github.anastaci1a.localica.ui.layout.menu.PageRef

/** #757 page registry. M0 registers the specimen only; M2 moves the settings page tree here. */
internal enum class PageId(override val title: String, override val summary: String? = null, override val icon: MenuIcon? = null) : PageRef {
    Specimen("UI specimen"),
    SpecimenChild("Specimen child page", "Where a navigation row leads", io.github.anastaci1a.localica.ui.AppIcon.ActionSettings);
    override val key: String get() = name
}

/** A named sample state, used by the fixture tests, the specimen and the device invariant suite. */
internal class Fixture<S>(val name: String, val state: S)

/**
 * A page is a pure function `(state) -> PageDraft`, registered with the small composable holder that produces its
 * state. Declarations import no Compose UI, so they can be admitted on the JVM without a device.
 */
internal class PageSource<S>(val id: PageId, val state: @Composable () -> S, val declare: (S) -> PageDraft, val fixtures: List<Fixture<S>>) {
    /** Every fixture declared, by name. */
    fun drafts(): List<Pair<String, PageDraft>> = fixtures.map { it.name to declare(it.state) }
}

internal object MenuPages {
    val sources: List<PageSource<*>> = listOf(SpecimenSource)
}
