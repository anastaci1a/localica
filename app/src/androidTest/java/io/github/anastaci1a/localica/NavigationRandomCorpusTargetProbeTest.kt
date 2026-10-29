package io.github.anastaci1a.localica

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** Ordinary gate/declaration contracts only. These tests never activate or validate a real corpus. */
@RunWith(AndroidJUnit4::class)
class NavigationRandomCorpusTargetProbeTest {
    @Test
    fun ordinaryDiscoveryWithoutOptInSkipsRatherThanClaimingCorpusSuccess() {
        for (selector in listOf(null, "", "io.github.anastaci1a.localica.OtherTest",
            NavigationRandomCorpusTargetProbe.OWNED_TEST_CLASS + "Extra",
            "prefix." + NavigationRandomCorpusTargetProbe.OWNED_TEST_CLASS,
            "io.github.anastaci1a.localica.OtherTest#someMethod")) {
            assertEquals(NavigationRandomCorpusTargetProbe.Selection.SkipOrdinaryDiscovery,
                NavigationRandomCorpusTargetProbe.selection(null, selector))
        }
    }

    @Test
    fun explicitOwnedClassOrAnyOwnedMethodRequiresOptIn() {
        val owned = NavigationRandomCorpusTargetProbe.OWNED_TEST_CLASS
        for (selector in listOf(owned, "$owned#${NavigationRandomCorpusTargetProbe.OWNED_TEST_METHOD}",
            "$owned#anotherMethod", "$owned#", "other.Test,$owned", "$owned,other.Test", "other.Test,$owned#anotherMethod,third.Test")) {
            illegalArgument("Explicit owned-corpus selection requires opt-in") {
                NavigationRandomCorpusTargetProbe.selection(null, selector)
            }
        }
    }

    @Test
    fun onlyExactOptInValueAllowsTheOwnedPath() {
        assertEquals("localicaNavigationRandomOwned", NavigationRandomCorpusTargetProbe.OPT_IN_ARGUMENT)
        assertEquals("v1", NavigationRandomCorpusTargetProbe.OPT_IN_VALUE)
        assertEquals("io.github.anastaci1a.localica.NavigationRandomCorpusOwnedTargetTest", NavigationRandomCorpusTargetProbe.OWNED_TEST_CLASS)
        assertEquals("verifyOwnedCorpusAndPersistQueue", NavigationRandomCorpusTargetProbe.OWNED_TEST_METHOD)
        for (selector in listOf(null, "", NavigationRandomCorpusTargetProbe.OWNED_TEST_CLASS, "other.Test")) {
            assertEquals(NavigationRandomCorpusTargetProbe.Selection.RunOwned,
                NavigationRandomCorpusTargetProbe.selection("v1", selector))
            for (invalid in listOf("", "true", "V1", "v2", " v1", "v1 ", "v1\n")) {
                illegalArgument("Invalid owned-corpus opt-in") {
                    NavigationRandomCorpusTargetProbe.selection(invalid, selector)
                }
            }
        }
    }

    @Test
    fun ownedInvocationRequiresExclusiveSelectionAndDisabledActivityFinisher() {
        assertEquals("waitForActivitiesToComplete", NavigationRandomCorpusTargetProbe.RUNNER_ACTIVITY_WAIT_ARGUMENT)
        val owned = NavigationRandomCorpusTargetProbe.OWNED_TEST_CLASS
        val method = "$owned#${NavigationRandomCorpusTargetProbe.OWNED_TEST_METHOD}"
        // These are declaration-shape checks, not admission or authorization to execute the hook.
        for (selector in listOf(owned, method)) {
            NavigationRandomCorpusTargetProbe.validateInvocation("v1", selector, "false")
        }
        for (selector in listOf(null, "", "other.Test", "$owned,other.Test", "other.Test,$owned", "$owned#anotherMethod")) {
            illegalArgument("Owned invocation requires the exclusive class or method selector") {
                NavigationRandomCorpusTargetProbe.validateInvocation("v1", selector, "false")
            }
        }
        for (runnerWait in listOf(null, "", "true", "FALSE", "false ")) {
            illegalArgument("Owned invocation must disable the runner ActivityFinisher") {
                NavigationRandomCorpusTargetProbe.validateInvocation("v1", owned, runnerWait)
            }
        }
        illegalArgument("Owned invocation requires opt-in") {
            NavigationRandomCorpusTargetProbe.validateInvocation(null, null, "false")
        }
    }

    @Test
    fun reportAndOperationBoundsAreExplicitAndPinned() {
        assertEquals(8 * 1024 * 1024, NavigationRandomCorpusTargetProbe.MAX_REPORT_BYTES)
        assertEquals(4096, NavigationRandomCorpusTargetProbe.MAX_RECORD_BYTES)
        assertEquals(13_600, NavigationRandomCorpusTargetProbe.MAX_REPORT_RECORDS)
        assertEquals(512, NavigationRandomCorpusTargetProbe.MAX_FAILURE_TEXT_CHARS)
        assertEquals(30_000L, NavigationRandomCorpusTargetProbe.PREREQUISITE_TIMEOUT_MS)
        assertEquals(30_000L, NavigationRandomCorpusTargetProbe.COMMAND_TIMEOUT_MS)
        assertEquals(30_000L, NavigationRandomCorpusTargetProbe.READ_OPERATION_TIMEOUT_MS)
        assertEquals(30 * 60 * 1000L, NavigationRandomCorpusTargetProbe.READ_PHASE_TIMEOUT_MS)
        assertEquals(60 * 60 * 1000L, NavigationRandomCorpusTargetProbe.SCAN_TIMEOUT_MS)
        assertEquals(2 * 60 * 1000L, NavigationRandomCorpusTargetProbe.QUEUE_TIMEOUT_MS)
    }

    @Test
    fun recordByteRejectionDoesNotConsumeBudget() {
        val budget = NavigationRandomCorpusTargetProbe.EvidenceBudget()
        assertEquals(0, budget.records)
        assertEquals(0L, budget.bytes)
        budget.reserve(1)
        for (invalid in listOf(Int.MIN_VALUE, -1, 0, NavigationRandomCorpusTargetProbe.MAX_RECORD_BYTES + 1, Int.MAX_VALUE)) {
            illegalArgument("Report record byte bound") { budget.reserve(invalid) }
            assertEquals(1, budget.records)
            assertEquals(1L, budget.bytes)
        }
        budget.reserve(NavigationRandomCorpusTargetProbe.MAX_RECORD_BYTES)
        assertEquals(2, budget.records)
        assertEquals(4097L, budget.bytes)
    }

    @Test
    fun exactRecordCountBoundaryIsAcceptedThenRejectedWithoutIncrement() {
        val budget = NavigationRandomCorpusTargetProbe.EvidenceBudget()
        repeat(NavigationRandomCorpusTargetProbe.MAX_REPORT_RECORDS) { budget.reserve(1) }
        assertEquals(13_600, budget.records)
        assertEquals(13_600L, budget.bytes)
        repeat(2) {
            illegalState("Report record count bound") { budget.reserve(1) }
            assertEquals(13_600, budget.records)
            assertEquals(13_600L, budget.bytes)
        }
    }

    @Test
    fun exactTotalByteBoundaryIsAcceptedThenRejectedWithoutIncrement() {
        val budget = NavigationRandomCorpusTargetProbe.EvidenceBudget()
        val recordCount = NavigationRandomCorpusTargetProbe.MAX_REPORT_BYTES / NavigationRandomCorpusTargetProbe.MAX_RECORD_BYTES
        repeat(recordCount) { budget.reserve(NavigationRandomCorpusTargetProbe.MAX_RECORD_BYTES) }
        assertEquals(recordCount, budget.records)
        assertEquals(NavigationRandomCorpusTargetProbe.MAX_REPORT_BYTES.toLong(), budget.bytes)
        repeat(2) {
            illegalState("Report total byte bound") { budget.reserve(1) }
            assertEquals(recordCount, budget.records)
            assertEquals(NavigationRandomCorpusTargetProbe.MAX_REPORT_BYTES.toLong(), budget.bytes)
        }
    }

    @Test
    fun failureTextIsBoundedWithoutChangingShortMessages() {
        for (text in listOf("", "failure", "x".repeat(512))) {
            assertEquals(text, NavigationRandomCorpusTargetProbe.failureText(text))
        }
        val long = "x".repeat(512) + "must-not-be-retained"
        assertEquals("x".repeat(512), NavigationRandomCorpusTargetProbe.failureText(long))
    }

    @Test
    fun expectedMetadataRejectsEmptyOversizedOrUnpinnedDeclarations() {
        val oneByte = byteArrayOf('{'.code.toByte())
        illegalArgument("Manifest metadata byte limit") {
            NavigationRandomCorpusTargetProbe.parseExpectedMetadata(byteArrayOf(), oneByte)
        }
        illegalArgument("Manifest metadata byte limit") {
            NavigationRandomCorpusTargetProbe.parseExpectedMetadata(ByteArray(NavigationRandomCorpusJson.MAX_MANIFEST_BYTES + 1), oneByte)
        }
        illegalArgument("Tracks metadata byte limit") {
            NavigationRandomCorpusTargetProbe.parseExpectedMetadata(oneByte, byteArrayOf())
        }
        illegalArgument("Tracks metadata byte limit") {
            NavigationRandomCorpusTargetProbe.parseExpectedMetadata(oneByte, ByteArray(NavigationRandomCorpusJson.MAX_TRACKS_BYTES + 1))
        }
        for (untrusted in listOf("{", "{}", "{\"schema_version\":1}")) {
            illegalArgument("Manifest metadata bytes do not match expected SHA256") {
                NavigationRandomCorpusTargetProbe.parseExpectedMetadata(untrusted.toByteArray(Charsets.UTF_8), oneByte)
            }
        }
    }

    private fun illegalArgument(message: String, block: () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException: $message")
        } catch (failure: IllegalArgumentException) {
            assertEquals(message, failure.message)
        }
    }

    private fun illegalState(message: String, block: () -> Unit) {
        try {
            block()
            fail("Expected IllegalStateException: $message")
        } catch (failure: IllegalStateException) {
            assertEquals(message, failure.message)
        }
    }
}
