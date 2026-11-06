package io.github.anastaci1a.localica

import android.view.Choreographer
import androidx.compose.runtime.*
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.foundation.Canvas
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import io.github.anastaci1a.localica.ui.spec.*
import io.github.anastaci1a.localica.ui.engine.diagnostics.*
import androidx.compose.ui.window.Popup
import io.github.anastaci1a.localica.ui.LocalicaTheme
import io.github.anastaci1a.localica.ui.engine.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@OptIn(ExperimentalTestApi::class)
class UiClockFoundationTest {
    @Test fun rootNestedThemeAndPopupReuseOneIdleService() = localicaRunComposeUiTest {
        var root: UiClock?=null; var nested: UiClock?=null; var popup: UiClock?=null
        setContent {
            LocalicaTheme {
                val a=LocalUiClock.current; SideEffect { root=a }
                LocalicaTheme { val b=LocalUiClock.current; SideEffect { nested=b } }
                Popup { LocalicaTheme { val c=LocalUiClock.current; SideEffect { popup=c } } }
            }
        }
        runOnIdle { assertNotNull(root); assertSame(root,nested); assertSame(root,popup); assertEquals(0,root!!.activeCount); assertNull(root!!.context) }
        mainClock.advanceTimeBy(1000)
        runOnIdle { assertEquals(0,root!!.activeCount); assertEquals(0L,root!!.stamp.sequence) }
    }
    @Test fun frameTimeAndIntentTimeShareMonotonicDomainAndMutationRequiresMain() = localicaRunComposeUiTest {
        val done=CountDownLatch(1); val error=AtomicReference<Throwable?>(); var clock: UiClock?=null
        runOnIdle {
            clock=UiClock(object: FrameSource { override suspend fun awaitFrame(): Long=throw IllegalStateException("unused") })
            Choreographer.getInstance().postFrameCallback { nanos ->
                try {
                    val now=System.nanoTime()
                    // Android Choreographer.frameTimeNanos is CLOCK_MONOTONIC, the same
                    // System.nanoTime domain. Allow scheduling latency, never epoch time.
                    assertTrue(nanos<=now); assertTrue(now-nanos<TimeUnit.SECONDS.toNanos(5))
                    assertTrue(kotlin.math.abs(clock!!.stampNow.nanos-nanos)<TimeUnit.SECONDS.toNanos(5))
                } catch(t: Throwable) { error.set(t) } finally { done.countDown() }
            }
        }
        assertTrue(done.await(10,TimeUnit.SECONDS)); error.get()?.let { throw it }
        val thread=Thread { try { clock!!.stampNow; error.set(AssertionError("Off-Main intent accepted")) } catch(expected: IllegalStateException) { } }
        thread.start(); thread.join(); error.get()?.let { throw it }
    }
    @Test fun actualOwnerFactoryAndObserverReachFiniteRestOnPresentedFrame() = localicaRunComposeUiTest {
        var motion: MotionFloat?=null; var clock: UiClock?=null; var evidence: BoundedEvidence?=null
        setContent {
            val owner=remember { UiClock(object: FrameSource { override suspend fun awaitFrame()=withFrameNanos { it } },{ mainClock.currentTime*1_000_000 }) }
            val sink=remember { BoundedEvidence(100) }
            val observation=remember(owner) { EngineObservation(owner,sink) }
            DisposableEffect(observation) { onDispose { observation.close() } }
            LaunchedEffect(owner) { owner.run() }
            CompositionLocalProvider(LocalUiClock provides owner,LocalEngineContext provides EngineContext(1f/120,1000f,MotionAccess.Standard)) {
                val value=rememberMotionFloat("native-control",0f,1f)
                SideEffect { motion=value; clock=owner; evidence=sink }
                Canvas(Modifier.size(20.dp)) { value.value() }
            }
        }
        runOnIdle { motion!!.retarget(1f,motionProfileId(MotionProfile.Control)) }
        mainClock.advanceTimeBy(500)
        runOnIdle { assertTrue(motion!!.settled); assertEquals(1f,motion!!.value(),0f); assertEquals(0,clock!!.activeCount); assertTrue(evidence!!.violations.toString(),evidence!!.violations.isEmpty()) }
    }

}
