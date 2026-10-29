package io.github.anastaci1a.localica

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/** Argument contracts only. Never starts a resident session or reads the real corpus. */
class NavigationRandomOwnedSessionProtocolTest {
    private fun valid() = mapOf(
        "class" to NavigationRandomOwnedSessionProtocol.SELECTOR,
        "localicaNavigationRandomOwned" to "v1",
        "localicaOwnedSession" to "v1",
        "ownedSessionId" to "97bb0e9e-bcb9-40b8-8bcc-063d35282a8f",
        "waitForActivitiesToComplete" to "false",
    )

    @Test fun exactFixedOperationAcceptsCanonicalSessionIdentity() {
        assertEquals(valid().getValue("ownedSessionId"), NavigationRandomOwnedSessionProtocol.validate(valid()))
    }

    @Test fun alternateClassAndMethodSelectorsCannotRun() {
        rejects(valid() + ("class" to "io.github.anastaci1a.localica.NavigationRandomCorpusOwnedTargetTest"))
        rejects(valid() + ("class" to NavigationRandomOwnedSessionProtocol.SELECTOR + ",another.Test"))
    }

    @Test fun injectedListenerAndPathArgumentsAreRejected() {
        rejects(valid() + ("listener" to "another.Listener"))
        rejects(valid() + ("path" to "/data/local/tmp/other"))
    }

    @Test fun missingOptInAndActivityCleanupAreRejected() {
        rejects(valid() - "localicaOwnedSession")
        rejects(valid() + ("localicaNavigationRandomOwned" to "true"))
        rejects(valid() + ("waitForActivitiesToComplete" to "true"))
    }

    @Test fun nonCanonicalAndOversizedSessionIdentitiesAreRejected() {
        rejects(valid() + ("ownedSessionId" to "1-1-1-1-1"))
        rejects(valid() + ("ownedSessionId" to "A".repeat(257)))
        rejects(valid() + ("ownedSessionId" to "97BB0E9E-BCB9-40B8-8BCC-063D35282A8F"))
    }

    @Test fun hostReadyBindsExactSessionBytes() {
        val id = valid().getValue("ownedSessionId")
        NavigationRandomOwnedSessionProtocol.validateHostReady(id.toByteArray(Charsets.US_ASCII), id)
    }

    @Test fun hostReadyRejectsForeignTruncatedAndExtendedDeclarations() {
        val id = valid().getValue("ownedSessionId")
        for (value in listOf("00000000-0000-0000-0000-000000000000", id.dropLast(1), id + "\n", id + "x")) {
            try {
                NavigationRandomOwnedSessionProtocol.validateHostReady(value.toByteArray(Charsets.US_ASCII), id)
                fail("Expected ready declaration rejection")
            } catch (_: IllegalArgumentException) { }
        }
    }

    private fun rejects(arguments: Map<String, String>) {
        try {
            NavigationRandomOwnedSessionProtocol.validate(arguments)
            fail("Expected exact argument-contract rejection")
        } catch (_: IllegalArgumentException) { }
    }
}
