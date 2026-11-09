package io.github.anastaci1a.localica.artistphoto

import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

internal const val PHOTO_REFRESH_MS = 30L * 24 * 60 * 60 * 1000
internal const val PHOTO_RETRY_MS = 24L * 60 * 60 * 1000
internal const val PHOTO_MAX_BYTES = 4 * 1024 * 1024
internal data class SavedPhotoChoice(val mode: String = "auto", val choice: ArtistPhotoChoice? = null,
    val attemptedAt: Long? = null, val pendingChoice: ArtistPhotoChoice? = null)

/** Repository-serialized disk operations; display leases are thread safe and never perform IO. */
internal class ArtistPhotoDisk(private val cache: File, private val choices: File, private val maxBytes: Long,
    private val evictable: Boolean = true) {
    init { require(maxBytes >= PHOTO_MAX_BYTES + 32768) }
    private val visible = ConcurrentHashMap<String, AtomicInteger>()
    private val lastDisplay = ConcurrentHashMap<String, Long>()
    private val previews by lazy { ArtistPhotoDisk(File(cache, "previews"), choices,
        maxOf(PHOTO_MAX_BYTES + 32768L, minOf(64L * 1024 * 1024, maxBytes / 4))) }
    private val chosen by lazy { ArtistPhotoDisk(File(choices, "photos"), choices, Long.MAX_VALUE, evictable = false) }
    private fun key(artist: String) = MessageDigest.getInstance("SHA-256").digest(artist.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    private fun entry(artist: String) = File(cache, key(artist) + ".json")
    private fun selection(artist: String) = File(choices, key(artist) + ".json")
    private fun safeImage(name: String?) = name?.takeIf { it.matches(Regex("[0-9a-f]{64}\\.(image|thumb)")) }?.let { File(cache, it) }
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
    }.getOrDefault(SavedPhotoChoice("cleared"))

    fun saveChoice(artist: String, value: SavedPhotoChoice) {
        choices.mkdirs()
        check(selection(artist).exists() || choices.listFiles().orEmpty().size < 10000)
        write(selection(artist), JSONObject().put("mode", value.mode).putChoice(value.choice)
            .putChoice(value.pendingChoice, "pendingChoice").put("attemptedAt", value.attemptedAt).toString().toByteArray())
    }
    fun reset(artist: String) { selection(artist).delete(); removePhoto(artist) }
    fun removePhoto(artist: String) { removeLocal(artist); if (File(choices, "photos").exists()) chosen.removeLocal(artist) }
    private fun removeLocal(artist: String) {
        recoverTransactions()
        val metadata = read(entry(artist))
        safeImage(metadata?.optString("image"))?.delete()
        safeImage(metadata?.optString("thumbnail"))?.delete()
        entry(artist).delete()
    }
    fun cached(artist: String, now: Long): CachedArtistPhoto? {
        recoverTransactions()
        if (File(choices, "photos").exists()) chosen.recoverTransactions()
        return (if (File(choices, "photos").exists()) chosen.cachedLocal(artist) else null) ?: cachedLocal(artist)
    }

    private fun cachedLocal(artist: String): CachedArtistPhoto? = runCatching {
        val metadata = read(entry(artist)) ?: return@runCatching null
        val file = safeImage(metadata.getString("image")) ?: return@runCatching null
        if (!file.isFile || file.length() !in 1..PHOTO_MAX_BYTES.toLong()) return@runCatching null
        val credit = metadata.optJSONObject("attribution")?.run { PhotoAttribution(getString("artist"), getString("credit"),
            getString("licence"), getString("licenceUrl"), getString("sourcePage")) }
        val provider = ArtistPhotoProvider.valueOf(metadata.getString("provider"))
        if (provider == ArtistPhotoProvider.WIKIMEDIA && credit == null) return@runCatching null
        val thumbnail = safeImage(metadata.optString("thumbnail"))?.takeIf { it.isFile && it.length() in 1..PHOTO_MAX_BYTES.toLong() }
        CachedArtistPhoto(file, provider, credit, metadata.getLong("fetchedAt"), thumbnail)
    }.getOrNull()

    fun savePhoto(artist: String, remote: RemoteArtist, bytes: ByteArray, now: Long, thumbnail: ByteArray? = null,
        commitChoice: () -> Unit = {}): CachedArtistPhoto {
        if (choice(artist).mode == "manual") {
            val photo = chosen.saveLocal(artist, remote, bytes, now, thumbnail, commitChoice)
            removeLocal(artist)
            return photo
        }
        return saveLocal(artist, remote, bytes, now, thumbnail, commitChoice)
    }

    private fun saveLocal(artist: String, remote: RemoteArtist, bytes: ByteArray, now: Long, thumbnail: ByteArray?,
        commitChoice: () -> Unit = {}): CachedArtistPhoto {
        check(bytes.isNotEmpty() && bytes.size <= PHOTO_MAX_BYTES && photoHeader(bytes))
        check(thumbnail == null || thumbnail.isNotEmpty() && thumbnail.size <= PHOTO_MAX_BYTES && photoHeader(thumbnail))
        cache.mkdirs()
        fun identity(value: ByteArray) = key(artist + ":" + MessageDigest.getInstance("SHA-256").digest(value).joinToString { it.toString() })
        val image = identity(bytes) + ".image"
        val small = thumbnail?.let { identity(it) + ".thumb" }
        val previous = read(entry(artist))
        write(File(cache, image), bytes)
        if (small != null) write(File(cache, small), requireNotNull(thumbnail))
        val metadata = JSONObject().put("image", image).put("thumbnail", small)
            .put("provider", remote.choice.provider.name).put("fetchedAt", now)
        remote.attribution?.let { credit -> metadata.put("attribution", JSONObject().put("artist", credit.artist).put("credit", credit.credit)
            .put("licence", credit.licence).put("licenceUrl", credit.licenceUrl).put("sourcePage", credit.sourcePage)) }
        val journal = File(cache, entry(artist).name + ".transaction")
        write(journal, (previous?.toString() ?: "null").toByteArray())
        try {
            write(entry(artist), metadata.toString().toByteArray())
            commitChoice()
        } catch (failure: Exception) {
            if (previous == null) entry(artist).delete()
            else write(entry(artist), previous.toString().toByteArray())
            if (image != previous?.optString("image")) File(cache, image).delete()
            if (small != null && small != previous?.optString("thumbnail")) File(cache, small).delete()
            journal.delete()
            throw failure
        }
        if (previous?.optString("image") != image) safeImage(previous?.optString("image"))?.delete()
        if (previous?.optString("thumbnail") != small) safeImage(previous?.optString("thumbnail"))?.delete()
        journal.delete()
        File(cache, image).setLastModified(now); entry(artist).setLastModified(now)
        trim()
        return requireNotNull(cachedLocal(artist))
    }

    fun preview(choice: ArtistPhotoChoice, now: Long): CachedArtistPhoto? = previews.cachedLocal("$choice")
    fun savePreview(remote: RemoteArtist, encoded: PreparedArtistPhoto, now: Long): CachedArtistPhoto {
        val photo = previews.saveLocal("${remote.choice}", remote, encoded.full, now, encoded.thumbnail)
        trim()
        return photo
    }
    fun cacheBytes(): Long = cache.listFiles().orEmpty().filter { it.isFile }.sumOf { it.length() } +
        if (File(cache, "previews").exists()) previews.cacheBytes() else 0
    fun chosenBytes(): Long = if (File(choices, "photos").exists()) chosen.cacheBytes() else 0
    fun clearCache() {
        val protected = cache.listFiles().orEmpty().filter { it.extension == "json" &&
            (isVisible(it.nameWithoutExtension) || read(File(choices, it.name))?.optString("mode") == "manual") }
        val keep = protected.flatMap { listOfNotNull(it.name, read(it)?.optString("image"), read(it)?.optString("thumbnail")) }.toSet()
        cache.listFiles().orEmpty().filter { it.isFile && it.name !in keep }.forEach { it.delete() }
        if (File(cache, "previews").exists()) previews.clearCache()
    }
    fun budget(): Long = File(choices, "cache-budget").takeIf { it.length() in 1..16 }?.readText()?.trim()?.toLongOrNull()
        ?.takeIf { it in 128L * 1024 * 1024..2L * 1024 * 1024 * 1024 } ?: maxBytes
    fun setBudget(bytes: Long) {
        require(bytes in 128L * 1024 * 1024..2L * 1024 * 1024 * 1024)
        choices.mkdirs(); write(File(choices, "cache-budget"), bytes.toString().toByteArray()); trim()
    }
    fun retainDisplay(artist: String): AutoCloseable {
        val hash = key(artist)
        visible.computeIfAbsent(hash) { AtomicInteger() }.incrementAndGet()
        lastDisplay[hash] = System.currentTimeMillis()
        return AutoCloseable {
            lastDisplay[hash] = System.currentTimeMillis()
            visible[hash]?.decrementAndGet()
        }
    }
    fun retainPreview(choice: ArtistPhotoChoice): AutoCloseable = previews.retainDisplay("$choice")
    fun displayed(artist: String, now: Long) {
        entry(artist).setLastModified(now)
        if (File(choices, "photos").exists()) chosen.entry(artist).setLastModified(now)
    }
    private fun isVisible(hash: String) = (visible[hash]?.get() ?: 0) > 0

    private fun recoverTransactions() {
        cache.listFiles().orEmpty().filter { it.name.endsWith(".json.transaction") }.forEach { journal ->
            val metadata = File(cache, journal.name.removeSuffix(".transaction"))
            val current = read(metadata)
            val previous = read(journal)
            val rollback = read(File(choices, metadata.name))?.isNull("pendingChoice") == false
            val retire = if (rollback) current else previous
            val keep = if (rollback) previous else current
            if (rollback) {
                if (previous == null) metadata.delete() else write(metadata, previous.toString().toByteArray())
            }
            for (field in listOf("image", "thumbnail")) {
                if (retire?.optString(field) != keep?.optString(field)) safeImage(retire?.optString(field))?.delete()
            }
            journal.delete()
        }
    }

    fun trim() {
        if (!cache.exists()) return
        recoverTransactions()
        if (evictable && cache.name != "previews" && File(choices, "photos").exists()) chosen.trim()
        // Upgrade old manual entries out of the OS-evictable cache without changing their choices.
        if (evictable && cache.name != "previews") cache.listFiles().orEmpty().filter { metadata ->
            val saved = read(File(choices, metadata.name))
            metadata.extension == "json" && saved?.optString("mode") == "manual" && saved.isNull("pendingChoice")
        }.forEach { metadata ->
            val info = read(metadata) ?: return@forEach
            val files = listOfNotNull(safeImage(info.optString("image")), safeImage(info.optString("thumbnail")))
            if (files.isNotEmpty() && files.all { it.isFile }) {
                chosen.cache.mkdirs()
                files.forEach { write(File(chosen.cache, it.name), it.readBytes()) }
                write(File(chosen.cache, metadata.name), metadata.readBytes())
                files.forEach { it.delete() }; metadata.delete()
            }
        }
        val entries = cache.listFiles().orEmpty().filter { it.extension == "json" }
        lastDisplay.forEach { (hash, time) -> File(cache, "$hash.json").setLastModified(time) }
        val referenced = entries.flatMap { listOfNotNull(read(it)?.optString("image"), read(it)?.optString("thumbnail")) }.toSet()
        cache.listFiles().orEmpty().filter { it.name.endsWith(".pending") ||
            (it.extension in setOf("image", "thumb") && it.name !in referenced) }.forEach { it.delete() }
        if (!evictable) return
        var size = cacheBytes()
        for (metadata in entries.sortedBy { it.lastModified() }) {
            if (size <= budget()) break
            if (isVisible(metadata.nameWithoutExtension)) continue
            if (cache.name != "previews" && read(File(choices, metadata.name))?.optString("mode") == "manual") continue
            val info = read(metadata)
            val files = listOfNotNull(safeImage(info?.optString("image")), safeImage(info?.optString("thumbnail")))
            size -= metadata.length() + files.sumOf { it.length() }
            files.forEach { it.delete() }; metadata.delete()
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
