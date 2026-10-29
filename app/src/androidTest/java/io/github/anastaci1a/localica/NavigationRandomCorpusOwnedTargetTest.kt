package io.github.anastaci1a.localica

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in entry point only: ordinary discovery neither opens an Activity nor touches a corpus. */
@RunWith(AndroidJUnit4::class)
class NavigationRandomCorpusOwnedTargetTest {
    @Test
    fun verifyOwnedCorpusAndPersistQueue() {
        val args = InstrumentationRegistry.getArguments()
        when (NavigationRandomCorpusTargetProbe.selection(
            flag = args.getString(NavigationRandomCorpusTargetProbe.OPT_IN_ARGUMENT),
            classSelection = args.getString("class")
        )) {
            NavigationRandomCorpusTargetProbe.Selection.SkipOrdinaryDiscovery ->
                Assume.assumeTrue("Real owned corpus not selected; NOT a corpus pass", false)
            NavigationRandomCorpusTargetProbe.Selection.RunOwned ->
                NavigationRandomCorpusTargetProbe.runOwned(InstrumentationRegistry.getInstrumentation())
        }
    }
}
