package io.github.anastaci1a.localica

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.runComposeUiTest
import androidx.test.ext.junit.rules.ActivityScenarioRule
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.test.StandardTestDispatcher

// Queue composition resumptions until the test clock runs them on the UI thread (#499).
// The default unconfined dispatcher can resume a test frame on a background snapshot writer.
@OptIn(ExperimentalTestApi::class)
fun localicaComposeRule(): ComposeContentTestRule =
    createComposeRule(effectContext = StandardTestDispatcher())

@OptIn(ExperimentalTestApi::class)
inline fun <reified A : ComponentActivity> localicaAndroidComposeRule():
    AndroidComposeTestRule<ActivityScenarioRule<A>, A> =
    createAndroidComposeRule<A>(effectContext = StandardTestDispatcher())

@OptIn(ExperimentalTestApi::class)
fun localicaRunComposeUiTest(
    effectContext: CoroutineContext = EmptyCoroutineContext,
    block: ComposeUiTest.() -> Unit
) = runComposeUiTest(effectContext = effectContext + StandardTestDispatcher(), block = block)
