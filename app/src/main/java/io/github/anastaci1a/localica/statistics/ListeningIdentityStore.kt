package io.github.anastaci1a.localica.statistics

import android.content.SharedPreferences

/** Small identity checkpoint; qualified_plays remains the durable, idempotent count ledger. */
class ListeningIdentityStore(private val preferences: SharedPreferences) {
    fun read(): ListeningIdentity? {
        val visit = preferences.getString("visit", null) ?: return null
        val event = preferences.getString("event", null) ?: return null
        return ListeningIdentity(visit, event)
    }

    /** Called by the repository's IO writer before any event using this identity. */
    fun write(identity: ListeningIdentity?) {
        val edit = preferences.edit()
        if (identity == null) edit.remove("visit").remove("event")
        else edit.putString("visit", identity.visit).putString("event", identity.event)
        check(edit.commit()) { "Could not save listening traversal identity" }
    }
}
