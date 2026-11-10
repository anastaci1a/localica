package io.github.anastaci1a.localica.artistphoto

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

internal data class ArtistPhotoJob(val request: ArtistPhotoRequest, val generation: String,
    val refresh: Boolean, val state: String = "pending")

/**
 * Durable queue, independent of activities and WorkManager's bounded input Data. Called on IO under the
 * owner's lock. The directory is parsed once per generation; [find] is an O(1) memory lookup and
 * [firstPending]/[pendingCount] scan the in-memory jobs without I/O (#779: each visible artist tile asks
 * whether it is queued, which used to read and parse every job file). In the application exactly one
 * [ArtistPhotoWork] owns this directory; the generation marker only serves other instances (tests).
 */
internal class ArtistPhotoJobs(private val directory: File) {
    private fun name(key: String) = MessageDigest.getInstance("SHA-256")
        .digest(key.toByteArray()).joinToString("") { "%02x".format(it) } + ".json"
    private fun file(key: String) = File(directory, name(key))
    private var loaded: LinkedHashMap<String, ArtistPhotoJob>? = null
    // Every writer replaces the generation marker after each change, so another instance notices any
    // write (also back-to-back writes within one file-system timestamp) with one small read, never a listing.
    private val marker = File(directory, "generation")
    private var generation: String? = null
    private fun currentGeneration(): String = runCatching { marker.readText() }.getOrDefault("")
    private fun advanceGeneration() {
        val next = UUID.randomUUID().toString()
        val pending = File(directory, "generation.pending")
        try {
            pending.outputStream().use { output -> output.write(next.toByteArray()); output.fd.sync() }
            Files.move(pending.toPath(), marker.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { pending.delete() }
        generation = next
    }

    private fun index(): LinkedHashMap<String, ArtistPhotoJob> {
        val current = currentGeneration()
        loaded?.takeIf { current == generation }?.let { return it }
        generation = current
        return load()
    }

    private fun load(): LinkedHashMap<String, ArtistPhotoJob> = LinkedHashMap<String, ArtistPhotoJob>().also { jobs ->
        directory.listFiles().orEmpty().filter { it.extension == "json" }.sortedBy { it.name }.forEach { file ->
            runCatching {
                val json = JSONObject(file.readText())
                val releases = json.getJSONArray("releases")
                ArtistPhotoJob(ArtistPhotoRequest(json.getString("key"), json.getString("name"),
                    (0 until releases.length()).map { releases.getString(it) }.toSet()), json.getString("generation"),
                    json.getBoolean("refresh"), json.getString("state"))
            }.getOrNull()?.let { job -> jobs[job.request.artistKey] = job }
        }
        loaded = jobs
    }

    fun all(): List<ArtistPhotoJob> = index().values.toList()
    fun find(artistKey: String): ArtistPhotoJob? = index()[artistKey]
    fun firstPending(): ArtistPhotoJob? = index().values.firstOrNull { it.state == "pending" }
    fun pendingCount(): Int = index().values.count { it.state == "pending" }

    fun enqueue(request: ArtistPhotoRequest, refresh: Boolean): ArtistPhotoJob {
        val existing = find(request.artistKey)
        if (!refresh && existing?.state == "pending") return existing
        return ArtistPhotoJob(request.copy(latestAlbumArt = null), UUID.randomUUID().toString(), refresh).also(::write)
    }

    fun complete(job: ArtistPhotoJob, succeeded: Boolean) {
        // A newer pick must survive an older download finishing or being cancelled.
        if (find(job.request.artistKey)?.generation != job.generation) return
        if (succeeded) {
            val jobs = index()
            file(job.request.artistKey).delete()
            jobs.remove(job.request.artistKey); advanceGeneration()
        } else write(job.copy(state = "failed"))
    }

    fun retryFailures() { all().filter { it.state == "failed" }.forEach { write(it.copy(state = "pending", refresh = true)) } }

    private fun write(job: ArtistPhotoJob) {
        check(directory.isDirectory || directory.mkdirs())
        val jobs = index()
        val json = JSONObject().put("key", job.request.artistKey).put("name", job.request.name)
            .put("releases", JSONArray(job.request.localReleases.toList())).put("generation", job.generation)
            .put("refresh", job.refresh).put("state", job.state)
        val target = file(job.request.artistKey)
        val pending = File(directory, target.name + ".pending")
        try {
            pending.outputStream().use { output -> output.write(json.toString().toByteArray()); output.fd.sync() }
            Files.move(pending.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { pending.delete() }
        jobs[job.request.artistKey] = job; advanceGeneration()
    }
}
