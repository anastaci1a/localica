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
    val rejected: List<Pair<String, String>>)

class IconPackException(message: String) : Exception(message)

/** An imported pack owns decoded images; selecting it never opens its source again. */
data class StoredIconPack(val id: String, val report: IconPackReport, val importedAt: Long,
    val masks: Map<String, Bitmap>, val vectors: Map<String, ImageVector> = emptyMap()) {
    val iconIds: Set<String> get() = masks.keys + vectors.keys
}

data class IconPackImages(val masks: Map<String, Bitmap> = emptyMap(),
    val vectors: Map<String, ImageVector> = emptyMap())

class PreparedIconPack internal constructor(val report: IconPackReport,
    internal val masks: Map<String, Bitmap>, internal val vectors: Map<String, ImageVector>,
    internal val files: Map<String, ByteArray>)

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
                    writeMetadata(legacy, IconPackReport(old.second, (old.first.keys + old.third.keys).toList(), emptyList(), emptyList()), legacy.lastModified())
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
                StoredIconPack(folder.name, IconPackReport(stored.second, (stored.first.keys + stored.third.keys).toList(), strings("unknown"), rejected),
                    metadata?.optLong("importedAt") ?: folder.lastModified(), stored.first, stored.third)
            }.getOrNull() }.sortedBy { it.importedAt }
        loadedRoot = root.absolutePath
        publish(preferences(context).getString("active", null))
    }

    private fun ensureLoaded(context: Context) {
        if (loadedRoot != directory(context).absolutePath) load(context)
    }

    fun prepare(input: InputStream): PreparedIconPack = parse(input).let { PreparedIconPack(it.report, it.masks, it.vectors, it.files) }

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
        val info = prepared.report.info
        File(staging, IconPackNames.MANIFEST).writeText(JSONObject().put("name", info.name)
            .put("version", info.version).apply { info.author?.let { put("author", it) } }.toString())
        prepared.files.forEach { (key, bytes) -> File(staging, "icons/$key").writeBytes(bytes) }
        writeMetadata(staging, prepared.report, now)
        val backup = File(root, "$id.old")
        check(!target.exists() || target.renameTo(backup)) { "Could not update the icon pack" }
        if (!staging.renameTo(target)) {
            backup.renameTo(target)
            error("Could not store the icon pack")
        }
        backup.deleteRecursively()
        val stored = StoredIconPack(id, prepared.report, now, prepared.masks, prepared.vectors)
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

    /** Writes every built-in icon as a ready-to-edit template pack. */
    fun exportTemplate(context: Context, output: OutputStream) {
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry(IconPackNames.MANIFEST))
            zip.write(JSONObject().put("name", "Built-in icons").put("version", "1").toString().toByteArray())
            zip.closeEntry()
            AppIcon.entries.forEach { icon ->
                zip.putNextEntry(ZipEntry("icons/${icon.id}.svg"))
                zip.write(builtinSvg(context, icon))
                zip.closeEntry()
            }
        }
    }

    private class Parsed(val report: IconPackReport, val masks: Map<String, Bitmap>,
        val vectors: Map<String, ImageVector>, val files: Map<String, ByteArray>)

    private fun parse(input: InputStream): Parsed {
        val decoder = IconVectorDecoder()
        var manifest: IconPackInfo? = null
        val masks = linkedMapOf<String, Bitmap>()
        val vectors = linkedMapOf<String, ImageVector>()
        val files = linkedMapOf<String, ByteArray>()
        val unknown = mutableListOf<String>()
        val rejected = mutableListOf<Pair<String, String>>()
        var entries = 0
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                if (++entries > IconPackNames.MAX_ENTRIES) throw IconPackException("The pack has too many files")
                val bytes = readCapped(zip) ?: run { rejected += entry.name to "larger than 2 MB"; null } ?: continue
                if (entry.name == IconPackNames.MANIFEST) { manifest = manifest(bytes); continue }
                val id = IconPackNames.iconId(entry.name) ?: continue
                if (AppIcon.byId(id) == null) { unknown += id; continue }
                if (entry.name.endsWith(".svg", ignoreCase = true)) {
                    val vector = runCatching { decoder.decode(bytes) }.getOrElse {
                        rejected += entry.name to ("Invalid SVG: " + (it.message ?: "malformed vector").take(300))
                        null
                    }
                    if (vector != null) {
                        vectors[id] = vector
                        masks.remove(id)?.recycle()
                        files.remove("$id.png")
                        files["$id.svg"] = bytes
                    }
                    continue
                }
                if (id in vectors) continue
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) { rejected += entry.name to "not a decodable image"; continue }
                val problem = IconPackNames.sizeProblem(bounds.outWidth, bounds.outHeight)
                if (problem != null) { rejected += entry.name to problem; continue }
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bitmap == null) { rejected += entry.name to "not a decodable image"; continue }
                files["$id.png"] = if (entry.name.endsWith(".png", ignoreCase = true)) bytes else
                    ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
                masks[id] = displayMask(bitmap)
            }
        }
        val info = manifest ?: throw IconPackException("pack.json is missing")
        canonicalize(masks, vectors, files)
        if (masks.isEmpty() && vectors.isEmpty()) throw IconPackException("The pack has no usable icons" +
            rejected.firstOrNull()?.let { ": ${it.first}: ${it.second}" }.orEmpty())
        return Parsed(IconPackReport(info, (masks.keys + vectors.keys).toList(), unknown, rejected), masks, vectors, files)
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

    private fun manifest(bytes: ByteArray): IconPackInfo {
        val json = try { JSONObject(String(bytes, Charsets.UTF_8)) } catch (_: Exception) { throw IconPackException("pack.json is not valid JSON") }
        val name = json.optString("name").trim()
        val version = json.opt("version")?.toString()?.trim().orEmpty()
        if (name.isEmpty()) throw IconPackException("pack.json needs a name")
        if (version.isEmpty()) throw IconPackException("pack.json needs a version")
        return IconPackInfo(name, version, json.optString("author").trim().takeIf { it.isNotEmpty() })
    }

    private fun readDirectory(directory: File): Triple<Map<String, Bitmap>, IconPackInfo, Map<String, ImageVector>>? {
        val manifest = File(directory, IconPackNames.MANIFEST).takeIf { it.isFile } ?: return null
        val info = manifest(manifest.readBytes())
        val decoder = IconVectorDecoder()
        val masks = linkedMapOf<String, Bitmap>()
        val vectors = linkedMapOf<String, ImageVector>()
        (AppIcon.ids + AppIcon.legacyIds.keys).forEach { id ->
            val vector = File(directory, "icons/${id}.svg").takeIf { it.isFile && it.length() <= SvgIconParser.MAX_BYTES }
                ?.let { runCatching { decoder.decode(it.readBytes()) }.getOrNull() }
            if (vector != null) vectors[id] = vector
            else File(directory, "icons/${id}.png").takeIf { it.isFile }?.let { BitmapFactory.decodeFile(it.path) }
                ?.let { masks[id] = displayMask(it) }
        }
        canonicalize(masks, vectors, linkedMapOf())
        return if (masks.isEmpty() && vectors.isEmpty()) null else Triple(masks, info, vectors)
    }

    /** Normalize only accepted private images; aliases never override a usable canonical mapping. */
    private fun canonicalize(masks: MutableMap<String, Bitmap>, vectors: MutableMap<String, ImageVector>,
        files: MutableMap<String, ByteArray>) {
        val oldMasks = masks.toMap()
        val oldVectors = vectors.toMap()
        val oldFiles = files.toMap()
        masks.clear(); vectors.clear(); files.clear()
        (oldMasks.keys + oldVectors.keys).mapNotNull { AppIcon.byId(it) }.distinct().forEach { icon ->
            val source = AppIcon.packIds(icon).first { it in oldMasks || it in oldVectors }
            oldMasks[source]?.let { masks[icon.id] = it }
            oldVectors[source]?.let { vectors[icon.id] = it }
            val extension = if (source in oldVectors) "svg" else "png"
            oldFiles["$source.$extension"]?.let { files["${icon.id}.$extension"] = it }
        }
        oldMasks.values.filterNot { it in masks.values }.forEach { it.recycle() }
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
