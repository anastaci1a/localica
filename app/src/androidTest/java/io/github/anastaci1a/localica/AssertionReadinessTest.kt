package io.github.anastaci1a.localica

import androidx.compose.ui.test.ComposeTimeoutException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AssertionReadinessTest {
    @get:Rule val compose = localicaComposeRule()

    @Test fun successDoesNotRequestFailureDiagnostics() {
        compose.waitForAssertion(30, "Ready frame", { error("Diagnostics must stay off the success path") }) { }
    }

    @Test fun timeoutRetainsItsLastAssertionAndObservedFrameState() {
        val failures = mutableListOf<AssertionError>()
        try {
            compose.waitForAssertion(30, "Initial cover", { "laid=40x40 painted=0x0" }) {
                throw AssertionError("Missing cover, attempt ${failures.size}").also { failures += it }
            }
            fail("The missing frame cannot become ready")
        } catch (timeout: ComposeTimeoutException) {
            assertTrue(failures.isNotEmpty())
            assertTrue(timeout.suppressed.any { it === failures.last() })
            assertTrue(timeout.suppressed.any { it.message?.contains("laid=40x40 painted=0x0") == true })
        }
    }
}
