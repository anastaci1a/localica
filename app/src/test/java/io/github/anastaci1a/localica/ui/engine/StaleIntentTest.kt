package io.github.anastaci1a.localica.ui.engine

import io.github.anastaci1a.localica.ui.spec.MotionProfile
import org.junit.Assert.*
import org.junit.Test

class StaleIntentTest {
    @Test fun contactCannotOverwriteANewerProgrammaticIntentBetweenPresentedFrames() {
        EngineHarness().use { h ->
            val m=MotionFloat(h.clock,0f,1f); val profile=motionProfileId(MotionProfile.Control)
            val old=h.clock.stampNow; m.retarget(1f,profile)
            h.now+=10_000_000; m.retarget(.5f,profile); val sample=m.sampleAt(h.clock.stampNow); val epoch=m.epoch.intValue
            assertThrows(IllegalArgumentException::class.java) { m.drive(.2f,old) }
            assertEquals(sample,m.sampleAt(h.clock.stampNow)); assertEquals(epoch,m.epoch.intValue); m.dispose()
        }
    }
}
