package io.github.anastaci1a.localica.artistphoto

import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

internal const val PHOTO_REFRESH_MS = 30L * 24 * 60 * 60 * 1000
internal const val PHOTO_RETRY_MS = 24L * 60 * 60 * 1000
internal const val PHOTO_MAX_BYTES = 4 * 1024 * 1024
internal data class SavedPhotoChoice(val mode: String = "auto", val choice: ArtistPhotoChoice? = null,
    val attemptedAt: Long? = null, val pendingChoice: ArtistPhotoChoice? = null)

/** Called under the repository mutex on IO. Choices live in filesDir; evictable pictures in cacheDir. */
internal class ArtistPhotoDisk(private val cache: File, private val choices: File, private val maxBytes: Long) {
    init { require(maxBytes >= PHOTO_MAX_BYTES + 32768) }
    private fun key(artist: String) = MessageDigest.getInstance("SHA-256").digest(artist.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    private fun entry(artist: String) = File(cache, key(artist) + ".json")
    private fun selection(artist: String) = File(choices, key(artist) + ".json")
    private fun read(file: File): JSONObject? = runCatching {
        if (file.length() !in 1..32768) return@runCatching null
        JSONObject(file.readText())
    }.getOrNull()
    private fun JSONObject.choice(field: String = "choice"): ArtistPhotoChoice? = optJSONObject(field)?.run {
        ArtistPhotoChoice(ArtistPhotoProvider.valueOf(getString("provider")), getString("id"))
    }
    private fun JSONObject.putChoice(choice: ArtistPhotoChoice?, field: String = "choice") = put(field, choice?.let {
        JSONObject().put("provider", it.provider.name).put("id", it.id)
    })
    fun choice(artist: String): SavedPhotoChoice = runCatching {
        read(selection(artist))?.run { SavedPhotoChoice(getString("mode"), choice(),
            if (isNull("attemptedAt")) null else getLong("attemptedAt"), choice("pendingChoice")) }
            ?: if (selection(artist).exists()) SavedPhotoChoice("cleared") else SavedPhotoChoice()
    }.getOrDefault(SavedPhotoChoice("cleared")) // Damaged persisted corrections must not trigger an automatic rematch.

    fun saveChoice(artist: String, value: SavedPhotoChoice) {
        choices.mkdirs()
        check(selection(artist).exists() || choices.listFiles().orEmpty().size < 10000)
        write(selection(artist), JSONObject().put("mode", value.mode).putChoice(value.choice)
            .putChoice(value.pendingChoice, "pendingChoice").put("attemptedAt", value.attemptedAt).toString().toByteArray())
    }
    fun reset(artist: String) { selection(artist).delete(); removePhoto(artist) }
    fun removePhoto(artist: String) {
        val image = read(entry(artist))?.optString("image")
        if (image != null && image.matches(Regex("[0-9a-f]{64}\\.image"))) File(cache, image).delete()
        entry(artist).delete()
    }
    fun cached(artist: String, now: Long): CachedArtistPhoto? = runCatching {
        val metadata = read(entry(artist)) ?: return@runCatching null
        val image = metadata.getString("image").takeIf { it.matches(Regex("[0-9a-f]{64}\\.image")) } ?: return@runCatching null
        val file = File(cache, image)
        if (!file.isFile || file.length() !in 1..PHOTO_MAX_BYTES.toLong()) return@runCatching null
        val credit = metadata.optJSONObject("attribution")?.run { PhotoAttribution(getString("artist"), getString("credit"),
            getString("licence"), getString("licenceUrl"), getString("sourcePage")) }
        val provider = ArtistPhotoProvider.valueOf(metadata.getString("provider"))
        if (provider == ArtistPhotoProvider.WIKIMEDIA && credit == null) return@runCatching null
        entry(artist).setLastModified(now); file.setLastModified(now)
        CachedArtistPhoto(file, provider, credit, metadata.getLong("fetchedAt"))
    }.getOrNull()

    fun savePhoto(artist: String, remote: RemoteArtist, bytes: ByteArray, now: Long, commitChoice: () -> Unit = {}): CachedArtistPhoto {
        check(bytes.isNotEmpty() && bytes.size <= PHOTO_MAX_BYTES && photoHeader(bytes))
        cache.mkdirs()
        val image = key(artist + ":" + MessageDigest.getInstance("SHA-256").digest(bytes).joinToString { it.toString() }) + ".image"
        write(File(cache, image), bytes)
        val previous = read(entry(artist))
        val old = previous?.optString("image")
        val metadata = JSONObject().put("image", image).put("provider", remote.choice.provider.name).put("fetchedAt", now)
        remote.attribution?.let { credit -> metadata.put("attribution", JSONObject().put("artist", credit.artist).put("credit", credit.credit)
            .put("licence", credit.licence).put("licenceUrl", credit.licenceUrl).put("sourcePage", credit.sourcePage)) }
        try {
            write(entry(artist), metadata.toString().toByteArray())
            commitChoice()
        } catch (failure: Exception) {
            // Keep the previous ready image and pending intent when selection persistence fails.
            if (previous == null) entry(artist).delete()
            else write(entry(artist), previous.toString().toByteArray())
            if (image != old) File(cache, image).delete()
            throw failure
        }
        if (old != null && old != image && old.matches(Regex("[0-9a-f]{64}\\.image"))) File(cache, old).delete()
        File(cache, image).setLastModified(now); entry(artist).setLastModified(now)
        trim()
        return requireNotNull(cached(artist, now))
    }
    fun cacheBytes(): Long = cache.listFiles().orEmpty().sumOf { it.length() }
    fun clearCache() { cache.listFiles().orEmpty().filter { it.isFile }.forEach { it.delete() } }

    fun trim() {
        if (!cache.exists()) return
        // A crash between picture and metadata writes leaves an orphan; it is safe to discard.
        val entries = cache.listFiles().orEmpty().filter { it.extension == "json" }
        val referenced = entries.mapNotNull { read(it)?.optString("image") }.toSet()
        cache.listFiles().orEmpty().filter { it.name.endsWith(".pending") || (it.extension == "image" && it.name !in referenced) }.forEach { it.delete() }
        var size = cache.listFiles().orEmpty().sumOf { it.length() }
        for (metadata in entries.sortedBy { it.lastModified() }) {
            if (size <= maxBytes) break
            val image = read(metadata)?.optString("image")?.takeIf { it.matches(Regex("[0-9a-f]{64}\\.image")) }?.let { File(cache, it) }
            size -= metadata.length() + (image?.length() ?: 0)
            image?.delete(); metadata.delete()
        }
    }
    private fun write(file: File, bytes: ByteArray) {
        val pending = File(file.parentFile, file.name + ".pending")
        try {
            pending.outputStream().use { output -> output.write(bytes); output.fd.sync() }
            Files.move(pending.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { pending.delete() }
    }
}

internal fun photoHeader(bytes: ByteArray): Boolean =
    (bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte()) ||
        (bytes.size >= 8 && bytes.take(8) == listOf(137, 80, 78, 71, 13, 10, 26, 10).map(Int::toByte)) ||
        (bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP")
