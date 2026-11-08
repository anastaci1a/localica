package io.github.anastaci1a.localica.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** One app-level opt-in for online metadata. Callers must check again before each request. */
interface NetworkConsent {
    val allowed: StateFlow<Boolean>
    suspend fun setAllowed(value: Boolean)
}

/** Starts closed until the asynchronous read completes. Invalid/unreadable state stays closed. */
class OnlineMetadataConsent(private val file: File) : NetworkConsent {
    private val mutable = MutableStateFlow(false)
    override val allowed = mutable.asStateFlow()
    private val lock = Mutex()
    private var loaded = false

    suspend fun load() = withContext(Dispatchers.IO) { lock.withLock {
        if (!loaded) {
            mutable.value = runCatching { file.length() <= 16 && file.readText() == "allowed\n" }.getOrDefault(false)
            loaded = true
        }
    } }

    override suspend fun setAllowed(value: Boolean) = withContext(Dispatchers.IO) { lock.withLock {
        // Revocation takes effect even if persistence fails. Enabling takes effect only after saving.
        if (!value) mutable.value = false
        file.parentFile?.mkdirs()
        val pending = File(file.parentFile, file.name + ".pending")
        try {
            pending.outputStream().use { output ->
                output.write(if (value) "allowed\n".toByteArray() else "denied\n".toByteArray())
                output.fd.sync()
            }
            Files.move(pending.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            loaded = true
            mutable.value = value
        } finally { pending.delete() }
    } }
}
