package io.github.anastaci1a.localica.icons

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.anastaci1a.localica.ui.AppIcon
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import org.json.JSONArray
import java.util.UUID
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class IconPackInfo(val name: String, val version: String, val author: String?)

/** What an import found. Unknown ids and bad images never fail the whole pack. */
data class IconPackReport(val info: IconPackInfo, val applied: List<String>, val unknown: List<String>,
    val rejected: List<Pair<String, String>>, val suggestions: Map<String, String> = emptyMap())

class IconPackException(message: String) : Exception(message)

/** An imported pack owns decoded images; selecting it never opens its source again. */
data class StoredIconPack(val id: String, val report: IconPackReport, val importedAt: Long,
    val masks: Map<String, Bitmap>, val vectors: Map<String, ImageVector> = emptyMap(),
    val imageMappings: Map<String, String> = emptyMap()) {
    val iconIds: Set<String> get() = masks.keys + vectors.keys
    val uniqueImages: List<IconPackImage> get() = imageMappings.entries.groupBy({ it.value }, { it.key })
        .map { (file, ids) -> IconPackImage(file, ids) }
}

data class IconPackImages(val masks: Map<String, Bitmap> = emptyMap(),
    val vectors: Map<String, ImageVector> = emptyMap())

class PreparedIconPack internal constructor(val report: IconPackReport,
    internal val masks: Map<String, Bitmap>, internal val vectors: Map<String, ImageVector>,
    internal val files: Map<String, ByteArray>, internal val manifest: IconPackManifest)

/** Process-wide library and live resolver. Disk operations run off Main. */
object IconPacks {
    private val imageState = MutableStateFlow(IconPackImages())
    val images: StateFlow<IconPackImages> get() = imageState
    private val maskState = MutableStateFlow<Map<String, Bitmap>>(emptyMap())
    private val infoState = MutableStateFlow<IconPackInfo?>(null)
    private val libraryState = MutableStateFlow<List<StoredIconPack>>(emptyList())
    private val activeState = MutableStateFlow<String?>(null)
    val masks: StateFlow<Map<String, Bitmap>> get() = maskState
    val info: StateFlow<IconPackInfo?> get() = infoState
    val library: StateFlow<List<StoredIconPack>> get() = libraryState
    val activeId: StateFlow<String?> get() = activeState
    private var loadedRoot: String? = null
    private fun directory(context: Context) = File(context.filesDir, "icon-packs")
    private fun preferences(context: Context) = context.getSharedPreferences("icon-packs", Context.MODE_PRIVATE)

    /** Loads all images once per process, and migrates the previous single-pack directory. */
    @Synchronized fun load(context: Context) {
        val root = directory(context).also { check(it.isDirectory || it.mkdirs()) }
        root.listFiles().orEmpty().filter { it.name.endsWith(".old") }.forEach { backup ->
            val target = File(root, backup.name.removeSuffix(".old"))
            if (target.exists()) backup.deleteRecursively() else check(backup.renameTo(target))
        }
        val legacy = File(context.filesDir, "icon-pack")
        if (legacy.isDirectory) {
            val target = File(root, "legacy")
            if (!target.exists()) {
                val old = runCatching { readDirectory(legacy) }.getOrNull()
                if (old != null) {
                    writeMetadata(legacy, old.report, legacy.lastModified())
                    check(preferences(context).edit().putString("active", "legacy").commit())
                    check(legacy.renameTo(target)) { "Could not migrate the icon pack" }
                }
            }
        }
        libraryState.value = root.listFiles().orEmpty().filter { it.isDirectory && !it.name.contains('.') }
            .mapNotNull { folder -> runCatching {
                val stored = readDirectory(folder) ?: return@runCatching null
                val metadata = File(folder, "report.json").takeIf { it.isFile }?.readText()?.let(::JSONObject)
                fun strings(key: String): List<String> = metadata?.optJSONArray(key)?.let { array ->
                    (0 until array.length()).map { array.getString(it) }
                }.orEmpty()
                val rejected = metadata?.optJSONArray("rejected")?.let { array ->
                    (0 until array.length()).map { index -> array.getJSONArray(index).let { it.getString(0) to it.getString(1) } }
                }.orEmpty()
                StoredIconPack(folder.name, stored.report.copy(unknown = (stored.report.unknown + strings("unknown")).distinct(), rejected = (stored.report.rejected + rejected).distinct(),
                    suggestions = (stored.report.unknown + strings("unknown")).distinct().associateWith(::closestIconId)),
                    metadata?.optLong("importedAt") ?: folder.lastModified(), stored.masks, stored.vectors, stored.manifest.icons)
            }.getOrNull() }.sortedBy { it.importedAt }
        loadedRoot = root.absolutePath
        publish(preferences(context).getString("active", null))
    }

    private fun ensureLoaded(context: Context) {
        if (loadedRoot != directory(context).absolutePath) load(context)
    }

    fun prepare(input: InputStream): PreparedIconPack = parse(input).let { PreparedIconPack(it.report, it.masks, it.vectors, it.files, it.manifest) }

    @Synchronized fun matching(context: Context, prepared: PreparedIconPack): StoredIconPack? {
        ensureLoaded(context)
        return libraryState.value.firstOrNull { it.report.info.name == prepared.report.info.name &&
            it.report.info.version == prepared.report.info.version }
    }

    /** The caller confirms an existing name/version before committing a prepared import. */
    @Synchronized fun install(context: Context, prepared: PreparedIconPack): IconPackReport {
        ensureLoaded(context)
        val existing = matching(context, prepared)
        val id = existing?.id ?: UUID.randomUUID().toString()
        val root = directory(context)
        val target = File(root, id)
        val staging = File(root, "$id.new").also { it.deleteRecursively(); check(File(it, "icons").mkdirs()) }
        val now = System.currentTimeMillis()
        File(staging, IconPackNames.MANIFEST).writeText(prepared.manifest.json().toString())
        prepared.files.forEach { (key, bytes) -> File(staging, "icons/$key").writeBytes(bytes) }
        writeMetadata(staging, prepared.report, now)
        val backup = File(root, "$id.old")
        check(!target.exists() || target.renameTo(backup)) { "Could not update the icon pack" }
        if (!staging.renameTo(target)) {
            backup.renameTo(target)
            error("Could not store the icon pack")
        }
        backup.deleteRecursively()
        val stored = StoredIconPack(id, prepared.report, now, prepared.masks, prepared.vectors, prepared.manifest.icons)
        libraryState.value = if (existing == null) libraryState.value + stored else libraryState.value.map { if (it.id == id) stored else it }
        select(context, id)
        return prepared.report
    }

    /** Import API for callers that already authorize updating a matching pack. */
    fun import(context: Context, input: InputStream): IconPackReport = install(context, prepare(input))

    private fun writeMetadata(folder: File, report: IconPackReport, date: Long) {
        File(folder, "report.json").writeText(JSONObject().put("importedAt", date)
            .put("applied", JSONArray(report.applied)).put("unknown", JSONArray(report.unknown))
            .put("rejected", JSONArray().apply { report.rejected.forEach { put(JSONArray(listOf(it.first, it.second))) } }).toString())
    }

    @Synchronized fun select(context: Context, id: String?) {
        ensureLoaded(context)
        require(id == null || libraryState.value.any { it.id == id })
        preferences(context).edit().putString("active", id).apply()
        publish(id)
    }

    private fun publish(id: String?) {
        val pack = libraryState.value.firstOrNull { it.id == id }
        imageState.value = IconPackImages(pack?.masks ?: emptyMap(), pack?.vectors ?: emptyMap())
        maskState.value = pack?.masks ?: emptyMap()
        infoState.value = pack?.report?.info
        activeState.value = pack?.id
    }

    @Synchronized fun delete(context: Context, id: String) {
        ensureLoaded(context)
        require(libraryState.value.any { it.id == id })
        val target = File(directory(context), id)
        val trash = File(directory(context), "$id.deleted")
        check(target.renameTo(trash)) { "Could not delete the icon pack" }
        libraryState.value = libraryState.value.filterNot { it.id == id }
        if (activeState.value == id) select(context, null)
        trash.deleteRecursively()
    }

    /** Restores built-ins without removing imported packs. */
    fun reset(context: Context) = select(context, null)

    /** Writes one source SVG per distinct built-in image and a v2 ID manifest. */
    fun exportTemplate(context: Context, output: OutputStream) {
        val files = linkedMapOf<String, ByteArray>()
        val hashes = linkedMapOf<String, String>()
        val mappings = AppIcon.entries.associate { icon ->
            val bytes = builtinSvg(context, icon)
            val filename = hashes.getOrPut(imageHash(bytes)) { icon.id + ".svg" }
            files[filename] = bytes
            icon.id to filename
        }
        val manifest = IconPackManifest(IconPackInfo("Built-in icons", "1", null), 2, mappings)
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry(IconPackNames.MANIFEST))
            zip.write(manifest.json().toString().toByteArray()); zip.closeEntry()
            files.forEach { (file, bytes) ->
                zip.putNextEntry(ZipEntry("icons/$file")); zip.write(bytes); zip.closeEntry()
            }
        }
    }

    internal fun builtinImageRows(): List<IconPackImage> = AppIcon.entries.groupBy { it.builtin }.values
        .map { icons -> IconPackImage(icons.first().id + ".svg", icons.map { it.id }) }

    private class Parsed(val report: IconPackReport, val masks: Map<String, Bitmap>,
        val vectors: Map<String, ImageVector>, val files: Map<String, ByteArray>, val manifest: IconPackManifest)

    private fun parse(input: InputStream): Parsed {
        val files = linkedMapOf<String, ByteArray>()
        val rejected = mutableListOf<Pair<String, String>>()
        var entries = 0
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                if (++entries > IconPackNames.MAX_ENTRIES) throw IconPackException("The pack has too many files")
                if (entry.name != IconPackNames.MANIFEST && IconPackNames.iconId(entry.name) == null) continue
                if (entry.name in files) throw IconPackException("Duplicate archive entry: ${entry.name}")
                val bytes = readCapped(zip) ?: run { rejected += entry.name to "larger than 2 MB"; null } ?: continue
                files[entry.name] = bytes
            }
        }
        return decode(files, rejected)
    }

    /** Decode each physical image once; canonical IDs win only after their image has been accepted. */
    private fun decode(entries: Map<String, ByteArray>, rejected: MutableList<Pair<String, String>> = mutableListOf()): Parsed {
        val manifest = IconPackManifest.parse(entries[IconPackNames.MANIFEST] ?: throw IconPackException("pack.json is missing"))
        val mappings = if (manifest.schemaVersion == 2) manifest.icons.mapValues { listOf(it.value) } else
            entries.keys.mapNotNull { path -> IconPackNames.iconId(path)?.let { it to path.removePrefix("icons/") } }
                .groupBy({ it.first }, { it.second }).mapValues { (_, files) -> files.sortedWith(compareBy<String> { if (it.endsWith(".svg", true)) 0 else 1 }.thenBy { it }) }
        val unknown = mappings.keys.filter { AppIcon.byId(it) == null }
        val decoder = IconVectorDecoder()
        val decodedMasks = linkedMapOf<String, Bitmap>()
        val decodedVectors = linkedMapOf<String, ImageVector>()
        val accepted = linkedMapOf<String, ByteArray>()
        val checked = mutableSetOf<String>()
        fun accept(file: String): Boolean {
            if (!checked.add(file)) return file in accepted
            val path = "icons/$file"
            val bytes = entries[path]
            if (bytes == null) { rejected += path to "missing image"; return false }
            if (file.endsWith(".svg", true)) {
                val vector = runCatching { decoder.decode(bytes) }.getOrElse {
                    rejected += path to ("Invalid SVG: " + (it.message ?: "malformed vector").take(300)); null
                } ?: return false
                decodedVectors[file] = vector
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                val problem = if (bounds.outWidth < 0 || bounds.outHeight < 0) "not a decodable image"
                    else IconPackNames.sizeProblem(bounds.outWidth, bounds.outHeight)
                if (problem != null) { rejected += path to problem; return false }
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bitmap == null) { rejected += path to "not a decodable image"; return false }
                decodedMasks[file] = displayMask(bitmap)
            }
            accepted[file] = bytes
            return true
        }
        val selected = linkedMapOf<String, String>()
        for (icon in AppIcon.entries) {
            val file = AppIcon.packIds(icon).flatMap { mappings[it].orEmpty() }.firstOrNull(::accept)
            if (file != null) selected[icon.id] = file
        }
        if (selected.isEmpty()) throw IconPackException("The pack has no usable icons" +
            rejected.firstOrNull()?.let { ": ${it.first}: ${it.second}" }.orEmpty())
        val files = linkedMapOf<String, ByteArray>()
        val hashes = linkedMapOf<String, String>()
        val images = linkedMapOf<String, JSONObject>()
        val deduplicated = selected.mapValues { (_, file) ->
            val bytes = accepted.getValue(file)
            val hash = imageHash(bytes)
            val unique = hashes.getOrPut(hash) { file }
            files[unique] = bytes
            manifest.images[file]?.let { metadata ->
                val previous = images[unique]
                if (previous != null && previous.toString() != metadata.toString()) throw IconPackException("Conflicting metadata for identical images")
                images[unique] = metadata
            }
            unique
        }
        val masks = linkedMapOf<String, Bitmap>()
        val vectors = linkedMapOf<String, ImageVector>()
        // Share the decoded instance as well as the stored file after hash deduplication.
        deduplicated.forEach { (id, file) ->
            decodedMasks[file]?.let { masks[id] = it }
            decodedVectors[file]?.let { vectors[id] = it }
        }
        decodedMasks.values.filterNot { it in masks.values }.forEach { it.recycle() }
        return Parsed(IconPackReport(manifest.info, selected.keys.toList(), unknown, rejected.distinct(), unknown.associateWith(::closestIconId)),
            masks, vectors, files, IconPackManifest(manifest.info, 2, deduplicated, images))
    }

    private fun readCapped(zip: ZipInputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val read = zip.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            if (out.size() > IconPackNames.MAX_ENTRY_BYTES) return null
        }
        return out.toByteArray()
    }

    private fun readDirectory(directory: File): Parsed? {
        val manifest = File(directory, IconPackNames.MANIFEST).takeIf { it.isFile } ?: return null
        if (manifest.length() > IconPackNames.MAX_ENTRY_BYTES) return null
        val entries = linkedMapOf(IconPackNames.MANIFEST to manifest.readBytes())
        File(directory, "icons").listFiles().orEmpty().filter { it.isFile && IconPackManifest.imageFilename(it.name) && it.length() <= IconPackNames.MAX_ENTRY_BYTES }
            .forEach { entries["icons/${it.name}"] = it.readBytes() }
        return decode(entries)
    }

    // Keep library memory proportional to icon display size, not the author's source resolution.
    // The original accepted pixels remain on disk; only this private decoded instance is recycled.
    private fun displayMask(bitmap: Bitmap): Bitmap {
        val mask = if (bitmap.width <= IconPackNames.TEMPLATE_SIZE) bitmap else
            Bitmap.createScaledBitmap(bitmap, IconPackNames.TEMPLATE_SIZE, IconPackNames.TEMPLATE_SIZE, true)
                .also { bitmap.recycle() }
        val alpha = IntArray(mask.width * mask.height)
        mask.getPixels(alpha, 0, mask.width, 0, 0, mask.width, mask.height)
        for (i in alpha.indices) alpha[i] = alpha[i] ushr 24
        val metrics = alphaInkMetrics(alpha, mask.width, mask.height) ?: return mask
        // Truncate toward zero: preserve pixels exactly and stay within the measured edge clearance.
        val move = opticalShift(metrics, mask.height.toFloat()).toInt()
        if (move == 0) return mask
        return Bitmap.createBitmap(mask.width, mask.height, Bitmap.Config.ARGB_8888).apply {
            density = mask.density
            Canvas(this).drawBitmap(mask, 0f, move.toFloat(), null)
            mask.recycle()
        }
    }

    /** The stored icon for [id] as decoded alpha, for tests. */
    internal fun maskFor(id: String): Bitmap? = maskState.value[id]
}
