package io.github.anastaci1a.localica

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.IdlingResource
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.runComposeUiTest
import androidx.test.ext.junit.rules.ActivityScenarioRule
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler

// Queue composition resumptions until the test clock runs them on the UI thread (#499).
// The default unconfined dispatcher can resume a test frame on a background snapshot writer.
@OptIn(ExperimentalTestApi::class)
fun localicaComposeRule(): ComposeContentTestRule {
    val dispatcher = StandardTestDispatcher()
    return createComposeRule(effectContext = dispatcher).withCurrentTasks(dispatcher.scheduler)
}

inline fun <reified A : ComponentActivity> localicaAndroidComposeRule():
    AndroidComposeTestRule<ActivityScenarioRule<A>, A> = localicaAndroidComposeRule(A::class.java)

@OptIn(ExperimentalTestApi::class)
fun <A : ComponentActivity> localicaAndroidComposeRule(activityClass: Class<A>):
    AndroidComposeTestRule<ActivityScenarioRule<A>, A> {
    val dispatcher = StandardTestDispatcher()
    return createAndroidComposeRule(activityClass, effectContext = dispatcher).withCurrentTasks(dispatcher.scheduler)
}

@OptIn(ExperimentalTestApi::class)
fun localicaRunComposeUiTest(
    effectContext: CoroutineContext = EmptyCoroutineContext,
    block: ComposeUiTest.() -> Unit
) {
    val dispatcher = StandardTestDispatcher()
    runComposeUiTest(effectContext = effectContext + dispatcher) {
        val currentTasks = CurrentTasksIdleResource(dispatcher.scheduler) { runOnUiThread(it) }
        registerIdlingResource(currentTasks)
        try { block() } finally { unregisterIdlingResource(currentTasks) }
    }
}

private fun <T : ComposeTestRule> T.withCurrentTasks(scheduler: TestCoroutineScheduler): T = apply {
    // Register before the rule installs Compose's own idle check, so it sees resumed work.
    registerIdlingResource(CurrentTasksIdleResource(scheduler) { runOnUiThread(it) })
}

// Compose 1.8.2 accepts StandardTestDispatcher but does not drain due tasks when idling.
// Backport the runCurrent step added to ComposeIdlingResource in 1.10. Remove after upgrading to Compose 1.10+.
// This never advances virtual time, including when tests pause mainClock.autoAdvance.
@OptIn(ExperimentalCoroutinesApi::class)
private class CurrentTasksIdleResource(
    private val scheduler: TestCoroutineScheduler,
    private val onUiThread: (() -> Unit) -> Unit
) : IdlingResource {
    override val isIdleNow: Boolean
        get() {
            onUiThread { scheduler.runCurrent() }
            return true
        }
}
