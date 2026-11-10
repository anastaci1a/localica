package io.github.anastaci1a.localica

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class FixtureRestorationTest {
    @Test fun cleanupFailuresDoNotSkipDelegatesOrAdmissionOrReplaceTheBodyFailure() {
        val body = AssertionError("original launch assertion")
        val cancellation = IllegalStateException("cancel failed")
        val join = IllegalStateException("join timeout")
        val read = IOException("journal read failed")
        val attempted = mutableListOf<String>()
        val cleanup = FixtureRestoration(body)
        cleanup.attempt("cancel") { attempted += "cancel"; throw cancellation }
        cleanup.attempt("controller") { attempted += "controller" }
        cleanup.attempt("writer") { attempted += "writer" }
        cleanup.attempt("join") { attempted += "join"; throw join }
        cleanup.attempt("journal") { attempted += "journal"; throw read }
        cleanup.attempt("original") { attempted += "original" }
        cleanup.attempt("admission") { attempted += "admission" }
        val result = runCatching { cleanup.finish() }.exceptionOrNull()
        assertSame(body, result)
        assertEquals(listOf("cancel", "controller", "writer", "join", "journal", "original", "admission"), attempted)
        assertEquals(listOf(cancellation, join, read), body.suppressed.map { it.cause })
    }

    @Test fun restorationFailureFailsAnOtherwisePassingBodyAndStillRestoresAdmission() {
        var admissionRestored = false
        val reflection = IllegalAccessException("writer delegate restore failed")
        val cleanup = FixtureRestoration(null)
        cleanup.attempt("writer") { throw reflection }
        cleanup.attempt("admission") { admissionRestored = true }
        val result = runCatching { cleanup.finish() }.exceptionOrNull()
        assertTrue(admissionRestored)
        assertTrue(result is AssertionError)
        assertSame(reflection, result!!.cause)
        assertTrue(result.message!!.contains("writer"))
    }
}
