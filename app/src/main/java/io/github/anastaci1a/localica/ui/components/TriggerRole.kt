package io.github.anastaci1a.localica.ui.components

/** Content identity only. Shared components alone map a role to motion and rendering policy.
 * Type parameters carry existing shared contact/preparation data without a dependency on a screen. */
sealed interface TriggerRole<out Contact, out Preparation> {
    data object Surface : TriggerRole<Nothing, Nothing>
    data object PlainIcon : TriggerRole<Nothing, Nothing>
    data object Contextual : TriggerRole<Nothing, Nothing>
    data object Navigation : TriggerRole<Nothing, Nothing>
    data object BrowseOptions : TriggerRole<Nothing, Nothing>
    data object Selection : TriggerRole<Nothing, Nothing>
    data object Lyrics : TriggerRole<Nothing, Nothing>
    data class SelectionItem<Entry>(val entry: Entry) : TriggerRole<Entry, Nothing>
    data class MaterialSurface<Plane>(val plane: Plane) : TriggerRole<Plane, Nothing>
    data class RetainedSurface<Plane>(val plane: Plane) : TriggerRole<Plane, Nothing>
    data class LyricsHeader<Contact, Preparation>(val contacts: Contact, val preparation: Preparation,
        val side: Float = -1f) : TriggerRole<Contact, Preparation>
}
