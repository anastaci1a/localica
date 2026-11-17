package io.github.anastaci1a.localica

import android.os.SystemClock
import org.junit.Assert.*
import org.junit.Test

class NativeImePolicyTest {
    @Test fun acknowledgementUsesSelectedServiceAndRejectsUnprovenState() {
        val selected = "com.android.inputmethod.latin/.LatinIME"
        fun dump(state: String) = """
            Input Method Manager Service state:
              mCurId=$selected
              mCurMethod=bound
            Input method service state for com.android.inputmethod.latin.LatinIME@123:
              mSettingsObserver=SettingsObserver{mShowImeWithHardKeyboard=$state}
        """.trimIndent()
        val absent = "Input Method Manager Service state:\n  mCurId=null\n  mCurMethod=null\nNo input method service.\n"
        val invalid = listOf("", dump("1"), dump("0"), dump("bad"),
            dump("2") + "\n  mSettingsObserver=bad",
            dump("2").replace("mCurId=$selected", "mCurId=other/.Keyboard"),
            dump("2").replace("LatinIME@123", "OtherIME@123"),
            dump("2") + "\n*** DUMP TIMEOUT ***", absent + "Input method service state for malformed:",
            absent.replace("No input method service.", ""), dump("2").replace("mCurMethod=bound", "missing=bound"),
            dump("2").replace("mSettingsObserver=", "missing="))
        var index = 0
        fun shell(command: String): String = when (command) {
            "settings get secure default_input_method" -> selected
            "dumpsys -t 2 input_method" -> (invalid + dump("2"))[index++]
            else -> error(command)
        }
        awaitSystemImeHardKeyboardPolicy(::shell, true, true)
        assertEquals("Every invalid predecessor must be rejected", invalid.size + 1, index)
        for ((state, show, changed) in listOf(Triple(dump("0"), true, false),
            Triple(dump("0"), false, false), Triple(dump("1"), false, true),
            Triple(absent, true, true), Triple(absent, false, true),
            Triple(dump("2").replace("Input Method Manager Service state:", "Current Input Method Manager state:")
                .replace("mCurMethod=bound", "mCurMethod=bound\n  mSettingsObserver=SettingsObserver{mUserId=0 mRegistered=true}"), true, true))) {
            awaitSystemImeHardKeyboardPolicy({ if (it.startsWith("settings")) selected else state }, show, changed)
        }
        val started = SystemClock.uptimeMillis()
        try {
            awaitSystemImeHardKeyboardPolicy({ if (it.startsWith("settings")) selected else dump("1") }, true, true)
            fail("An unchanged stale observation must exhaust the existing budget")
        } catch (failure: AssertionError) {
            assertTrue(failure.message.orEmpty().contains("never observed"))
            assertTrue(failure.message.orEmpty().contains("mShowImeWithHardKeyboard=1"))
            assertTrue("The existing 20 second budget is retained", SystemClock.uptimeMillis() - started >= 20_000)
        }
    }
}
