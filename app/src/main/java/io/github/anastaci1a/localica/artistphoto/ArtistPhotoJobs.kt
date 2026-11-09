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

/** Durable queue, independent of activities and WorkManager's bounded input Data. Called on IO. */
internal class ArtistPhotoJobs(private val directory: File) {
    private fun file(key: String) = File(directory, MessageDigest.getInstance("SHA-256")
        .digest(key.toByteArray()).joinToString("") { "%02x".format(it) } + ".json")

    fun all(): List<ArtistPhotoJob> = directory.listFiles().orEmpty().filter { it.extension == "json" }.mapNotNull { file ->
        runCatching {
            val json = JSONObject(file.readText())
            val releases = json.getJSONArray("releases")
            ArtistPhotoJob(ArtistPhotoRequest(json.getString("key"), json.getString("name"),
                (0 until releases.length()).map { releases.getString(it) }.toSet()), json.getString("generation"),
                json.getBoolean("refresh"), json.getString("state"))
        }.getOrNull()
    }

    fun enqueue(request: ArtistPhotoRequest, refresh: Boolean): ArtistPhotoJob {
        val existing = all().firstOrNull { it.request.artistKey == request.artistKey }
        if (!refresh && existing?.state == "pending") return existing
        return ArtistPhotoJob(request.copy(latestAlbumArt = null), UUID.randomUUID().toString(), refresh).also(::write)
    }

    fun complete(job: ArtistPhotoJob, succeeded: Boolean) {
        // A newer pick must survive an older download finishing or being cancelled.
        if (all().firstOrNull { it.request.artistKey == job.request.artistKey }?.generation != job.generation) return
        if (succeeded) file(job.request.artistKey).delete() else write(job.copy(state = "failed"))
    }

    fun retryFailures() { all().filter { it.state == "failed" }.forEach { write(it.copy(state = "pending", refresh = true)) } }

    private fun write(job: ArtistPhotoJob) {
        check(directory.isDirectory || directory.mkdirs())
        val json = JSONObject().put("key", job.request.artistKey).put("name", job.request.name)
            .put("releases", JSONArray(job.request.localReleases.toList())).put("generation", job.generation)
            .put("refresh", job.refresh).put("state", job.state)
        val target = file(job.request.artistKey)
        val pending = File(directory, target.name + ".pending")
        try {
            pending.outputStream().use { output -> output.write(json.toString().toByteArray()); output.fd.sync() }
            Files.move(pending.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { pending.delete() }
    }
}
