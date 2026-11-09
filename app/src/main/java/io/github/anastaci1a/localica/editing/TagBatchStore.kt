package io.github.anastaci1a.localica.editing

import android.util.AtomicFile
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.library.TrackSnapshot
import io.github.anastaci1a.localica.library.documentIdentity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

internal enum class TagBatchPhase { Saving, AwaitingDecision, Interrupted, CancelRequested, Restoring, Committed, Restored }
internal enum class TagBatchEntryPhase { Prepared, Writing, Saved, Failed, Restoring, Restored }
internal data class TagBatchEntry(val index: Int, val uri: String, val original: String?, val originalHash: String?,
    val size: Long, val phase: TagBatchEntryPhase = TagBatchEntryPhase.Prepared, val attempted: Boolean = false,
    val writtenHash: String? = null, val message: String? = null)
internal data class TagBatch(val id: String, val tracks: List<Track>, val patch: TagPatch?, val draft: String?,
    val undo: Boolean, val background: Boolean, val phase: TagBatchPhase, val entries: Map<Int, TagBatchEntry>,
    val payloadHash: String) {
    val saved get() = entries.values.count { it.phase == TagBatchEntryPhase.Saved }
    val restored get() = entries.values.count { it.attempted && it.phase == TagBatchEntryPhase.Restored }
    val touched get() = entries.values.count { it.attempted }
    val terminal get() = phase == TagBatchPhase.Committed || phase == TagBatchPhase.Restored
}

/** Checked records only; never calls the writer, queue or library while holding this monitor. */
class TagBatchStore(private val directory: File,
    private val commit: (AtomicFile, ByteArray) -> Unit = ::commitRefreshRecord) {
    private val header get() = AtomicFile(File(directory, "active.json"))
    private val idPattern = Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")
    private val hashPattern = Regex("[0-9a-f]{64}")

    @Synchronized internal fun read(): TagBatch? {
        val active = record(header) ?: run {
            // A payload staged before initial header publication has not admitted audio IO.
            // Orphan originals/entries are different: never guess their former ownership.
            directory.listFiles()?.filter { it.isDirectory }?.forEach { candidate ->
                check(!File(candidate, "originals").exists() && !File(candidate, "entries").exists()) {
                    "Undo records have lost their batch owner. Keep the retained files and retry recovery."
                }
            }
            return null
        }
        check(active.getInt("version") == 1)
        val id = active.getString("id"); check(idPattern.matches(id)) { "Invalid undo batch identity." }
        val phase = TagBatchPhase.valueOf(active.getString("phase"))
        val undo = active.getBoolean("undo"); val background = active.getBoolean("background")
        val payloadHash = active.getString("payloadHash"); check(hashPattern.matches(payloadHash))
        if (phase == TagBatchPhase.Committed || phase == TagBatchPhase.Restored)
            return TagBatch(id, emptyList(), null, null, undo, background, phase, emptyMap(), payloadHash)
        val root = root(id)
        val payloadFile = child(root, "batch.json")
        check(payloadFile.isFile && tagHash(payloadFile.inputStream()) == payloadHash) { "Undo batch details failed verification." }
        val payload = JSONObject(payloadFile.readText())
        check(payload.getInt("version") == 1 && payload.getString("id") == id)
        val array = payload.getJSONArray("tracks")
        val tracks = (0 until array.length()).map { TrackSnapshot.decode(array.getString(it)) }
        check(tracks.isNotEmpty() && tracks.map { documentIdentity(it.id) }.distinct().size == tracks.size) {
            "Undo batch contains duplicate document identities."
        }
        val patch = decodePatch(root, payload.getJSONObject("patch"))
        patch.validate()
        val entriesDir = child(root, "entries")
        val names = if (entriesDir.exists()) checkNotNull(entriesDir.list()).toList() else emptyList()
        val entries = linkedMapOf<Int, TagBatchEntry>()
        for (name in names.map { it.removeSuffix(".bak").removeSuffix(".new") }.distinct()) {
            check(name.matches(Regex("(0|[1-9][0-9]*)\\.json"))) { "Unknown undo entry record." }
            val index = name.removeSuffix(".json").toInt(); check(index in tracks.indices)
            val value = record(AtomicFile(child(entriesDir, name))) ?: error("Incomplete first undo entry record.")
            check(value.getInt("index") == index && value.getString("identity") == documentIdentity(tracks[index].id))
            val uri = value.getString("uri")
            check(documentIdentity(uri) == documentIdentity(tracks[index].id)) { "Undo source identity changed." }
            val original = value.optional("original")
            val originalHash = value.optional("originalHash")
            val size = value.getLong("size"); check(size >= 0)
            if (undo) {
                check(original == "$index.original" && originalHash != null && hashPattern.matches(originalHash))
                // Check ownership here, bytes immediately before use. Missing/corrupt undo bytes
                // never authorize a replacement, but do not turn verified audio into unsafe bytes.
                child(child(root, "originals"), requireNotNull(original))
            } else check(original == null && (originalHash == null || hashPattern.matches(originalHash)))
            val writtenHash = value.optional("writtenHash")
            check(writtenHash == null || hashPattern.matches(writtenHash))
            val entryPhase = TagBatchEntryPhase.valueOf(value.getString("phase"))
            val attempted = value.getBoolean("attempted")
            check(entryPhase != TagBatchEntryPhase.Saved || (attempted && writtenHash != null))
            check(entryPhase !in listOf(TagBatchEntryPhase.Writing, TagBatchEntryPhase.Restoring, TagBatchEntryPhase.Restored) || attempted)
            entries[index] = TagBatchEntry(index, uri, original, originalHash, size, entryPhase, attempted, writtenHash, value.optional("message"))
        }
        val draft = payload.optional("draft")?.also { JSONObject(it) }
        return TagBatch(id, tracks, patch, draft, undo, background, phase, entries, payloadHash)
    }

    @Synchronized internal fun begin(tracks: List<Track>, patch: TagPatch, draft: String?, undo: Boolean): TagBatch {
        check(read() == null) { "Finish or restore the interrupted batch before saving again." }
        patch.validate(); draft?.let { JSONObject(it) }
        val distinct = tracks.distinctBy { documentIdentity(it.id) }
        check(distinct.isNotEmpty())
        val id = UUID.randomUUID().toString()
        check(directory.isDirectory || directory.mkdirs())
        val root = root(id); check(root.mkdir())
        val payload = JSONObject().put("version", 1).put("id", id)
            .put("tracks", JSONArray(distinct.map(TrackSnapshot::encode))).put("draft", draft)
            .put("patch", encodePatch(root, patch)).toString().toByteArray(Charsets.UTF_8)
        writeSynced(child(root, "batch.json"), payload)
        val value = TagBatch(id, distinct, patch, draft, undo, false, TagBatchPhase.Saving, emptyMap(), tagHash(payload.inputStream()))
        writeHeader(value)
        return requireNotNull(read())
    }

    @Synchronized internal fun changePhase(id: String, phase: TagBatchPhase): TagBatch {
        val current = current(id)
        check(!current.terminal) { "The batch decision has already committed." }
        if (current.phase in listOf(TagBatchPhase.CancelRequested, TagBatchPhase.Restoring)) {
            check(phase in listOf(TagBatchPhase.CancelRequested, TagBatchPhase.Restoring, TagBatchPhase.Restored)) {
                "A cancelled batch cannot resume or commit."
            }
        }
        if (phase == TagBatchPhase.Committed) check(current.phase == TagBatchPhase.AwaitingDecision) { "Finish the active work before committing." }
        if (phase == TagBatchPhase.Restored) {
            check(current.undo && current.entries.values.filter { it.attempted }.all { it.phase == TagBatchEntryPhase.Restored })
        }
        if (phase == TagBatchPhase.CancelRequested || phase == TagBatchPhase.Restoring) check(current.undo)
        val next = current.copy(phase = phase)
        writeHeader(next)
        return if (next.terminal) next else requireNotNull(read())
    }

    @Synchronized internal fun background(id: String): TagBatch {
        val current = current(id); check(!current.terminal)
        val next = current.copy(background = true); writeHeader(next)
        return requireNotNull(read())
    }

    @Synchronized internal fun put(id: String, entry: TagBatchEntry): TagBatch {
        val current = current(id); check(!current.terminal && entry.index in current.tracks.indices)
        check(documentIdentity(entry.uri) == documentIdentity(current.tracks[entry.index].id))
        val old = current.entries[entry.index]
        check(old == null || (old.original == entry.original && (old.originalHash == entry.originalHash || (!current.undo && old.originalHash == null)) && old.uri == entry.uri)) {
            "First-touch undo bytes cannot be replaced."
        }
        if (current.phase in listOf(TagBatchPhase.CancelRequested, TagBatchPhase.Restoring))
            check(entry.phase != TagBatchEntryPhase.Writing) { "Cancellation closed write admission." }
        val root = child(root(id), "entries"); check(root.isDirectory || root.mkdir())
        val value = JSONObject().put("index", entry.index).put("identity", documentIdentity(current.tracks[entry.index].id))
            .put("uri", entry.uri).put("original", entry.original).put("originalHash", entry.originalHash).put("size", entry.size)
            .put("phase", entry.phase.name).put("attempted", entry.attempted).put("writtenHash", entry.writtenHash).put("message", entry.message)
        commit(AtomicFile(child(root, "${entry.index}.json")), value.toString().toByteArray(Charsets.UTF_8))
        return requireNotNull(read())
    }

    @Synchronized internal fun original(id: String, index: Int): File {
        val current = current(id); check(current.undo && index in current.tracks.indices)
        val originals = child(root(id), "originals"); check(originals.isDirectory || originals.mkdir())
        return child(originals, "$index.original")
    }

    @Synchronized internal fun checkedOriginal(batch: TagBatch, entry: TagBatchEntry): RecoverableTagWriter.Recovery {
        val current = current(batch.id); check(current.undo && current.entries[entry.index] == entry)
        val original = child(child(root(batch.id), "originals"), requireNotNull(entry.original))
        check(original.isFile && original.length() == entry.size && tagHash(original.inputStream()) == entry.originalHash) {
            "An undo copy is missing or failed verification. Keep the retained files and restore from another backup."
        }
        return RecoverableTagWriter.Recovery(current.tracks[entry.index], entry.uri, original, requireNotNull(entry.originalHash))
    }

    @Synchronized internal fun cleanup(id: String) {
        val current = current(id); check(current.terminal) { "Undo copies still belong to an unresolved batch." }
        val root = root(id)
        deleteOwned(root, root.canonicalFile)
        check(!root.exists()) { "Undo cleanup is incomplete; retry it." }
        header.delete()
        check(!header.baseFile.exists() && !File(directory, "active.json.bak").exists() && !File(directory, "active.json.new").exists())
    }

    private fun current(id: String): TagBatch = requireNotNull(read()).also { check(it.id == id) { "Undo batch ownership changed." } }
    private fun writeHeader(value: TagBatch) {
        check(directory.isDirectory || directory.mkdirs())
        val bytes = JSONObject().put("version", 1).put("id", value.id).put("phase", value.phase.name)
            .put("undo", value.undo).put("background", value.background).put("payloadHash", value.payloadHash)
            .toString().toByteArray(Charsets.UTF_8)
        commit(header, bytes)
    }
    private fun record(file: AtomicFile): JSONObject? {
        val base = file.baseFile
        val exists = base.exists() || File(base.path + ".bak").exists()
        check(exists || !File(base.path + ".new").exists()) { "An incomplete first undo record needs recovery." }
        if (!exists) return null
        return JSONObject(file.readFully().toString(Charsets.UTF_8))
    }
    private fun root(id: String): File {
        check(idPattern.matches(id))
        return child(directory, id)
    }
    private fun child(parent: File, name: String): File {
        check(name.isNotBlank() && name != "." && name != ".." && '/' !in name && '\\' !in name)
        val file = File(parent, name)
        check(file.canonicalFile.parentFile == parent.canonicalFile) { "Undo path escaped its owner." }
        return file
    }
    private fun deleteOwned(file: File, owner: File) {
        check(file.canonicalFile.toPath().startsWith(owner.toPath()))
        check(file.canonicalFile == File(requireNotNull(file.parentFile).canonicalFile, file.name)) { "Undo cleanup encountered a symbolic link." }
        if (file.isDirectory) checkNotNull(file.listFiles()).forEach { deleteOwned(it, owner) }
        if (file.exists()) check(file.delete()) { "Cannot remove retained undo file." }
    }
    private fun encodePatch(root: File, patch: TagPatch): JSONObject {
        val result = JSONObject().put("fields", JSONObject().apply { patch.fields.forEach { (field, text) -> put(field.name, text) } })
        when (val artwork = patch.artwork) {
            ArtworkChange.Keep -> result.put("artwork", "keep")
            ArtworkChange.Remove -> result.put("artwork", "remove")
            is ArtworkChange.Replace -> {
                val image = artwork.image
                require(image.bytes.size <= 24 * 1024 * 1024 && image.width > 0 && image.height > 0)
                writeSynced(child(root, "artwork.bin"), image.bytes)
                result.put("artwork", "replace").put("mime", image.mime).put("width", image.width).put("height", image.height)
                    .put("hash", tagHash(image.bytes.inputStream()))
            }
        }
        return result
    }
    private fun decodePatch(root: File, value: JSONObject): TagPatch {
        val fields = value.getJSONObject("fields")
        val patchFields = fields.keys().asSequence().associate { EditField.valueOf(it) to fields.getString(it) }
        val artwork = when (value.getString("artwork")) {
            "keep" -> ArtworkChange.Keep
            "remove" -> ArtworkChange.Remove
            "replace" -> {
                val file = child(root, "artwork.bin")
                check(file.isFile && file.length() <= 24 * 1024 * 1024 && tagHash(file.inputStream()) == value.getString("hash"))
                ArtworkChange.Replace(CoverImage(file.readBytes(), value.getString("mime"), value.getInt("width"), value.getInt("height")))
            }
            else -> error("Unknown retained artwork change.")
        }
        return TagPatch(patchFields, artwork)
    }
}

private fun JSONObject.optional(name: String): String? = if (isNull(name)) null else getString(name)
internal fun writeSynced(file: File, bytes: ByteArray) {
    check(!file.exists()) { "Retained bytes already exist." }
    FileOutputStream(file).use { output -> output.write(bytes); output.fd.sync() }
    check(file.readBytes().contentEquals(bytes)) { "Retained bytes did not verify." }
}
internal fun tagHash(input: InputStream): String = input.use {
    val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(64 * 1024)
    while (true) { val count = it.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
    val hex = "0123456789abcdef"
    buildString(64) { digest.digest().forEach { byte -> val value = byte.toInt() and 255; append(hex[value ushr 4]); append(hex[value and 15]) } }
}
