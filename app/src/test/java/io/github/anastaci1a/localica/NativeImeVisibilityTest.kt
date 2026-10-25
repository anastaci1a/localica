package io.github.anastaci1a.localica

import org.junit.Assert.*
import org.junit.Test


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
