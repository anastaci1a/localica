package io.github.anastaci1a.localica

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import io.github.anastaci1a.localica.ui.engine.*
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.runComposeUiTest
import androidx.test.ext.junit.rules.ActivityScenarioRule
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler

// Compose 1.10 drains due StandardTestDispatcher work on Main when idling, including
// with a paused frame clock. Its default unconfined dispatcher can resume on a worker.
// Preserve explicit dispatchers, schedulers and other fixture context elements.
private fun queuedEffects(context: CoroutineContext): CoroutineContext =
    if (context[ContinuationInterceptor] != null) context
    else context + StandardTestDispatcher(context[TestCoroutineScheduler])

/** The running test's virtual time, for Engine owners in fixture hosts (PlayerSurfaceHarness); real-app owner tests pass it
 * to their own UiClock. The theme clock would stamp an idle intent at its last presented frame instead. */
internal object FixtureTime {
    @Volatile var nanos: (() -> Long)? = null
    /** Stage2's harness clock, shared with the fixture root so every owner stamps idle intents in the same virtual time. */
    @Composable fun WithClock(content: @Composable () -> Unit) {
        val clock = remember { nanos?.let { now -> UiClock(object : FrameSource {
            override suspend fun awaitFrame() = withFrameNanos { it }
            override suspend fun <R> awaitFrame(onFrame: (Long) -> R) = withFrameNanos(onFrame)
        }, now) } }
        if (clock == null) { content(); return }
        LaunchedEffect(clock) { clock.run() }
        CompositionLocalProvider(LocalClockOwner provides clock, LocalUiClock provides clock, content = content)
    }
}

@OptIn(ExperimentalTestApi::class)
internal fun localicaComposeRule(
    effectContext: CoroutineContext = EmptyCoroutineContext
): ComposeContentTestRule = EngineRecordRule(createComposeRule(effectContext = queuedEffects(effectContext))
    .also { rule -> FixtureTime.nanos = { rule.mainClock.currentTime * 1_000_000 } })

/** Fixtures outside LocalicaScreen receive its root record and Stage2's shared virtual-time clock. */
private class EngineRecordRule(private val rule: ComposeContentTestRule) : ComposeContentTestRule by rule {
    override fun setContent(composable: @Composable () -> Unit) = rule.setContent { FixtureTime.WithClock { EngineRecord(content = composable) } }
}

internal inline fun <reified A : ComponentActivity> localicaAndroidComposeRule(
    effectContext: CoroutineContext = EmptyCoroutineContext
): AndroidComposeTestRule<ActivityScenarioRule<A>, A> =
    localicaAndroidComposeRule(A::class.java, effectContext)

@OptIn(ExperimentalTestApi::class)
internal fun <A : ComponentActivity> localicaAndroidComposeRule(
    activityClass: Class<A>,
    effectContext: CoroutineContext = EmptyCoroutineContext
): AndroidComposeTestRule<ActivityScenarioRule<A>, A> =
    createAndroidComposeRule(activityClass, effectContext = queuedEffects(effectContext))
        .also { rule -> FixtureTime.nanos = { rule.mainClock.currentTime * 1_000_000 } }

@OptIn(ExperimentalTestApi::class)
internal fun localicaRunComposeUiTest(
    effectContext: CoroutineContext = EmptyCoroutineContext,
    block: ComposeUiTest.() -> Unit
) = runComposeUiTest(effectContext = queuedEffects(effectContext)) { FixtureTime.nanos = { mainClock.currentTime * 1_000_000 }; block() }
