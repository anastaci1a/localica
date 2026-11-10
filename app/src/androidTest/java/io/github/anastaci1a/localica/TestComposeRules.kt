package io.github.anastaci1a.localica

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.junit4.ComposeTestRule
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

/** Observe a production ActivityScenario without launching a synthetic activity before fixture setup. */
@OptIn(ExperimentalTestApi::class)
internal fun localicaEmptyComposeRule(
    effectContext: CoroutineContext = EmptyCoroutineContext
): ComposeTestRule = createEmptyComposeRule(queuedEffects(effectContext))

@OptIn(ExperimentalTestApi::class)
internal fun localicaComposeRule(
    effectContext: CoroutineContext = EmptyCoroutineContext
): ComposeContentTestRule = createComposeRule(effectContext = queuedEffects(effectContext))

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

@OptIn(ExperimentalTestApi::class)
internal fun localicaRunComposeUiTest(
    effectContext: CoroutineContext = EmptyCoroutineContext,
    block: ComposeUiTest.() -> Unit
) = runComposeUiTest(effectContext = queuedEffects(effectContext), block = block)
