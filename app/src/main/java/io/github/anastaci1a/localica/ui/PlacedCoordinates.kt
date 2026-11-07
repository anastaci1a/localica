package io.github.anastaci1a.localica.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.LayoutAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.UnplacedAwareModifierNode
import androidx.compose.ui.platform.InspectorInfo

/**
 * Like onPlaced, but reports null once the layout is no longer placed. A lazy grid inside a
 * LookaheadScope keeps the previous approach rows composed after a long jump without placing them
 * (#635); a placed-coordinates holder must not keep drawing them at their last position.
 */
internal fun Modifier.placedCoordinates(onChange: (LayoutCoordinates?) -> Unit): Modifier =
    this then PlacedCoordinatesElement(onChange)

private data class PlacedCoordinatesElement(val onChange: (LayoutCoordinates?) -> Unit) :
    ModifierNodeElement<PlacedCoordinatesNode>() {
    override fun create() = PlacedCoordinatesNode(onChange)
    override fun update(node: PlacedCoordinatesNode) { node.onChange = onChange }
    override fun InspectorInfo.inspectableProperties() { name = "placedCoordinates" }
}

private class PlacedCoordinatesNode(var onChange: (LayoutCoordinates?) -> Unit) :
    Modifier.Node(), LayoutAwareModifierNode, UnplacedAwareModifierNode {
    override fun onPlaced(coordinates: LayoutCoordinates) = onChange(coordinates)
    override fun onUnplaced() = onChange(null)
}
