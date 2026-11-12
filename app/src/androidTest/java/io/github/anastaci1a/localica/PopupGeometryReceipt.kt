package io.github.anastaci1a.localica

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeTestRule
import io.github.anastaci1a.localica.ui.GlassAnchor
import io.github.anastaci1a.localica.ui.GlassPopupHome
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Keep original merged geometry selection; fall back only after a proven handoff. */
internal fun ComposeTestRule.popupGeometryNodes(anchor: GlassAnchor, requestedVisible: Boolean,
    output: File, name: String, expectedWindows: Int): List<SemanticsNode> {
    val merged = onAllNodesWithTag("glass-morph-surface").fetchSemanticsNodes()
    val unmerged = onAllNodesWithTag("glass-morph-surface", useUnmergedTree = true).fetchSemanticsNodes()
    val receipt = runOnUiThread {
        val flights = JSONArray()
        fun collect(home: GlassPopupHome, path: String) {
            home.flights.forEachIndexed { index, flight ->
                val returningSemantics = home.departing || flight.handedOff && !requestedVisible
                flights.put(JSONObject().put("homePath", path).put("flightIndex", index)
                    .put("handedOff", flight.handedOff).put("wasProjected", flight.wasProjected)
                    .put("prepared", flight.prepared).put("homeDeparting", home.departing)
                    .put("chromeTrigger", flight.chromeTrigger).put("requestedVisible", requestedVisible)
                    .put("returningInline", returningSemantics || !requestedVisible && flight.chromeTrigger))
            }
            home.children.forEachIndexed { index, child -> collect(child, "$path/$index") }
        }
        anchor.popupHome?.let { collect(it, "anchor") }
        JSONObject().put("name", name).put("mergedCount", merged.size).put("unmergedCount", unmerged.size)
            .put("expectedWindows", expectedWindows).put("requestedVisibleBasis", "fixture open")
            .put("flights", flights)
    }
    File(output, "geometry-$name.json").writeText(receipt.toString(2))
    File(output, "geometry-receipts.jsonl").appendText(
        JSONObject(receipt.toString()).put("uptimeMs", android.os.SystemClock.uptimeMillis()).toString() + "\n")
    if (merged.isNotEmpty()) return merged
    val flights = receipt.getJSONArray("flights")
    org.junit.Assert.assertTrue("A merged geometry miss requires real handoffs: $receipt",
        flights.length() > 0 && (0 until flights.length()).all { flights.getJSONObject(it).getBoolean("handedOff") })
    org.junit.Assert.assertTrue("Expected window count was captured before close", expectedWindows > 0)
    org.junit.Assert.assertEquals("Unmerged geometry keeps the expected native window count", expectedWindows, unmerged.size)
    return unmerged
}
