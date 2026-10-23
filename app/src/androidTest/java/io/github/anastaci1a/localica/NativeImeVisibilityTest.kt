package io.github.anastaci1a.localica

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** InputMethodService can retain its input-view flag after hiding the actual window. */
internal fun nativeImeWindowVisible(dump: String): Boolean? {
    val windows = Regex("""\bm(?:WindowVisible|DecorViewVisible)=(true|false)\b""")
        .findAll(dump).map { it.groupValues[1] == "true" }.toList()
    return windows.takeIf { it.isNotEmpty() }?.any { it }
}

@RunWith(AndroidJUnit4::class)
class NativeImeVisibilityTest {
    @Test fun api26HiddenWindowIgnoresRetainedInputViewFlag() {
        assertEquals(false, nativeImeWindowVisible("mWindowVisible=false mWindowWasVisible=true\nmIsInputViewShown=true"))
    }
    @Test fun hiddenDecorIgnoresRetainedInputViewFlag() {
        assertEquals(false, nativeImeWindowVisible("mDecorViewVisible=false\nmIsInputViewShown=true"))
    }
    @Test fun anyVisibleWindowPreventsHiddenCompletion() {
        assertEquals(true, nativeImeWindowVisible("mWindowVisible=false mDecorViewVisible=true mIsInputViewShown=false"))
    }
    @Test fun inputViewFlagAloneCannotEstablishWindowVisibility() {
        assertNull(nativeImeWindowVisible("mIsInputViewShown=false"))
    }
    @Test fun missingOrMalformedWindowStateStaysUnknown() {
        assertNull(nativeImeWindowVisible(""))
        assertNull(nativeImeWindowVisible("mWindowVisible=unknown"))
    }
}
