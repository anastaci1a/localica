package io.github.anastaci1a.localica

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.*

/** Read the applied tree from existing composition observers, before async effects can settle. */
internal class PlayerPresentationProbe {
    private var nodes: (() -> List<SemanticsNode>)? = null
    private var text = ""
    private var description = ""
    private var preview: Pair<Int, Rect>? = null
    val changes = linkedSetOf<String>()
    var countSamples = 0
        private set
    var previewSamples = 0
        private set

    fun start(root: SemanticsNode, count: String, label: String) {
        val owner = requireNotNull(root.root).semanticsOwner
        nodes = { owner.getAllSemanticsNodes(mergingEnabled = false) }
        text = count
        description = label
    }
    fun preview(node: SemanticsNode?) { preview = node?.let { it.id to it.boundsInRoot } }
    fun stop() { nodes = null; preview = null }
    fun sample() {
        val current = nodes?.invoke() ?: return
        countSamples++
        val counts = current.filter { node -> node.config.getOrNull(SemanticsProperties.ContentDescription)
            ?.any { it == description || it == "Track count loading" } == true }
        if (counts.none { it.config.getOrNull(SemanticsProperties.Text)?.any { value -> value.text == text } == true })
            changes += "Count withdrawn or replaced: ${counts.map { it.config }}"
        if (counts.none { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(description) == true })
            changes += "Count accessibility description withdrawn"
        preview?.let { (id, bounds) ->
            previewSamples++
            val shown = current.singleOrNull { it.id == id }
            if (shown == null || shown.boundsInRoot != bounds) changes += "Held neighbour removed, replaced or moved"
        }
    }
}
