package io.github.anastaci1a.localica.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.BeyondBoundsLayout
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.findNearestBeyondBoundsLayoutAncestor
import androidx.compose.ui.platform.InspectorInfo

/** Live items keep the grid provider reachable when its header is virtualized. */
internal class BrowseBeyondBounds {
    private val nodes = mutableSetOf<BrowseBeyondBoundsNode>()

    val provider: BeyondBoundsLayout?
        get() = nodes.firstNotNullOfOrNull { node ->
            if (node.isAttached) node.findNearestBeyondBoundsLayoutAncestor() else null
        }

    internal fun attach(node: BrowseBeyondBoundsNode) { nodes.add(node) }
    internal fun detach(node: BrowseBeyondBoundsNode) { nodes.remove(node) }
}

internal fun Modifier.browseBeyondBounds(owner: BrowseBeyondBounds): Modifier =
    this then BrowseBeyondBoundsElement(owner)

private data class BrowseBeyondBoundsElement(val owner: BrowseBeyondBounds) :
    ModifierNodeElement<BrowseBeyondBoundsNode>() {
    override fun create() = BrowseBeyondBoundsNode(owner)
    override fun update(node: BrowseBeyondBoundsNode) { node.update(owner) }
    override fun InspectorInfo.inspectableProperties() { name = "browseBeyondBounds" }
}

internal class BrowseBeyondBoundsNode(private var owner: BrowseBeyondBounds) : Modifier.Node() {
    override fun onAttach() { owner.attach(this) }
    override fun onDetach() { owner.detach(this) }

    fun update(next: BrowseBeyondBounds) {
        if (owner === next) return
        if (isAttached) owner.detach(this)
        owner = next
        if (isAttached) owner.attach(this)
    }
}
