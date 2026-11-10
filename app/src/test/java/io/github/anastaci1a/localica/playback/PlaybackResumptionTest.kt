package io.github.anastaci1a.localica.playback

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ExecutionException

class PlaybackResumptionTest {
    @Test fun resumptionDeclinesImmediatelySoMediaPlaysThroughTheQueueOwnedTransport() {
        val future = PlaybackResumption.ownedByQueueController()
        assertTrue("Media3 must not wait: the key's play() runs when the future settles", future.isDone)
        val failure = try { future.get(); null } catch (caught: ExecutionException) { caught.cause }
        // Media3 logs UnsupportedOperationException as a warning and plays anyway; anything else is an error.
        assertTrue(failure is UnsupportedOperationException)
    }

    @Test fun resumptionNeverSuppliesItemsThatWouldReplaceTheRestoredWindowedTimeline() {
        val future = PlaybackResumption.ownedByQueueController()
        assertTrue(runCatching { future.get() }.isFailure)
    }
}
