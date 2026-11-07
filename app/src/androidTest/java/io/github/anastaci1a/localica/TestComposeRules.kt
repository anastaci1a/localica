package io.github.anastaci1a.localica

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
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

@OptIn(ExperimentalTestApi::class)
internal fun localicaComposeRule(
    effectContext: CoroutineContext = EmptyCoroutineContext
): ComposeContentTestRule = EngineRecordRule(createComposeRule(effectContext = queuedEffects(effectContext)))

/** U04.02a: every fixture composes under the U11.00 root record, as LocalicaScreen does, so shared Components (MorphFaces, presence)
 * can own Engine motion outside the screen. A fixture's own record (or LocalicaScreen's) still overrides it. */
private class EngineRecordRule(private val rule: ComposeContentTestRule) : ComposeContentTestRule by rule {
    override fun setContent(composable: @Composable () -> Unit) = rule.setContent { EngineRecord(content = composable) }
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

@OptIn(ExperimentalTestApi::class)
internal fun localicaRunComposeUiTest(
    effectContext: CoroutineContext = EmptyCoroutineContext,
    block: ComposeUiTest.() -> Unit
) = runComposeUiTest(effectContext = queuedEffects(effectContext), block = block)
