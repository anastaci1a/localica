package io.github.anastaci1a.localica

import androidx.compose.ui.layout.LayoutCoordinates
import io.github.anastaci1a.localica.ui.FrostedBackdrop
import io.github.anastaci1a.localica.ui.GlassAnchor
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.io.File

internal data class R1BackdropIndependence(val containsConsumer: Boolean, val parents: Int,
    val sourceAttached: Boolean, val consumerAttached: Boolean)

/** Read the actual placed ancestry on Main; return immutable evidence without file I/O. */
internal fun captureR1BackdropIndependence(backdrop: FrostedBackdrop, consumer: GlassAnchor): R1BackdropIndependence {
    val source = requireNotNull(backdrop.location.coordinates)
    val target = requireNotNull(consumer.samplingLocation.coordinates)
    var cursor: LayoutCoordinates? = target
    var containsConsumer = false
    var parents = 0
    while (cursor != null) {
        if (cursor === source) containsConsumer = true
        parents++
        cursor = cursor.parentCoordinates
    }
    return R1BackdropIndependence(containsConsumer, parents, source.isAttached, target.isAttached)
}

/** Serialize and assert on the instrumentation thread, after the Main snapshot returns. */
internal fun assertR1BackdropIndependent(evidence: R1BackdropIndependence, output: File) {
    assertTrue("R1 backdrop source is attached", evidence.sourceAttached)
    assertTrue("R1 backdrop consumer is attached", evidence.consumerAttached)
    File(output, "backdrop-independence.json").writeText(JSONObject()
        .put("backdropContainsConsumer", evidence.containsConsumer).put("parentCoordinatesVisited", evidence.parents)
        .put("sourceAttached", evidence.sourceAttached).put("consumerAttached", evidence.consumerAttached).toString(2))
    assertFalse("R1 backdrop recording must not contain its glass consumer", evidence.containsConsumer)
}
