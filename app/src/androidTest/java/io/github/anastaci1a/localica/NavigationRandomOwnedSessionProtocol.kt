package io.github.anastaci1a.localica

import java.util.UUID

/** Fixed one-shot operation, not a general test selector or command channel. */
internal object NavigationRandomOwnedSessionProtocol {
    const val SELECTOR = NavigationRandomCorpusTargetProbe.OWNED_TEST_CLASS + "#" + NavigationRandomCorpusTargetProbe.OWNED_TEST_METHOD
    const val DIRECTORY = "navigation-random-session-v1"
    const val MAX_RECEIPT_BYTES = 64 * 1024
    const val HOST_READY_FILE = "host-ready.authorization"
    const val HOST_READY_TIMEOUT_MS = 120_000L
    val keys = setOf("class", "localicaNavigationRandomOwned", "localicaOwnedSession",
        "ownedSessionId", "waitForActivitiesToComplete")

    /** Host sequencing declaration only; the real hook still checks actual permissions/readback. */
    fun validateHostReady(bytes: ByteArray, sessionId: String) {
        require(UUID.fromString(sessionId).toString() == sessionId) { "Noncanonical ready session" }
        require(bytes.size == 36 && bytes.contentEquals(sessionId.toByteArray(Charsets.US_ASCII))) {
            "Host-ready declaration differs from this resident session"
        }
    }

    fun validate(arguments: Map<String, String>): String {
        require(arguments.keys == keys) { "Require exactly the resident-session argument keys" }
        require(arguments.values.all { it.length <= 256 }) { "Session argument too long" }
        require(arguments["class"] == SELECTOR) { "Require the one owned-corpus operation" }
        require(arguments["localicaNavigationRandomOwned"] == "v1" &&
            arguments["localicaOwnedSession"] == "v1") { "Require explicit owned resident-session opt-in" }
        require(arguments["waitForActivitiesToComplete"] == "false") { "Activity cleanup is forbidden" }
        val id = requireNotNull(arguments["ownedSessionId"])
        require(UUID.fromString(id).toString() == id) { "Require a canonical session UUID" }
        return id
    }
}
