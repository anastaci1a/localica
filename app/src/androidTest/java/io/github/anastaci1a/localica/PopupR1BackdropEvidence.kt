package io.github.anastaci1a.localica

import androidx.compose.ui.layout.LayoutCoordinates
import io.github.anastaci1a.localica.ui.FrostedBackdrop
import io.github.anastaci1a.localica.ui.GlassAnchor
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.io.File

/** Check the real placed source/consumer relationship, not a declared source label. */
internal fun assertR1BackdropIndependent(backdrop: FrostedBackdrop, consumer: GlassAnchor, output: File) {
    val source = requireNotNull(backdrop.location.coordinates)
    val target = requireNotNull(consumer.samplingLocation.coordinates)
    assertTrue("R1 backdrop source is attached", source.isAttached)
    assertTrue("R1 backdrop consumer is attached", target.isAttached)
    var cursor: LayoutCoordinates? = target
    var containsConsumer = false
    var parents = 0
    while (cursor != null) {
        if (cursor === source) containsConsumer = true
        parents++
        cursor = cursor.parentCoordinates
    }
    File(output, "backdrop-independence.json").writeText(JSONObject()
        .put("backdropContainsConsumer", containsConsumer).put("parentCoordinatesVisited", parents)
        .put("sourceAttached", source.isAttached).put("consumerAttached", target.isAttached).toString(2))
    assertFalse("R1 backdrop recording must not contain its glass consumer", containsConsumer)
}
