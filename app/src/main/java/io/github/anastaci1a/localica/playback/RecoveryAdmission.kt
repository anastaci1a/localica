package io.github.anastaci1a.localica.playback

/** Byte integrity, independent of the durable metadata-publication retry journal. */
sealed interface RecoveryAdmission {
    data object Clear : RecoveryAdmission
    data object Checking : RecoveryAdmission
    data class Pending(val identity: String, val reason: String? = null) : RecoveryAdmission
    data class Unknown(val reason: String) : RecoveryAdmission

    fun blocks(identity: String): Boolean = when (this) {
        Clear -> false
        is Pending -> this.identity == identity
        is Unknown, Checking -> true
    }

    fun explanation(): String = when (this) {
        Clear -> ""
        is Pending -> reason ?: "This song needs tag recovery before playback. Use Tag editing jobs and recovery in Settings."
        is Unknown -> reason
        Checking -> "Checking interrupted tag edits before playback."
    }
}
