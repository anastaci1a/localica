package io.github.anastaci1a.localica.icons

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import androidx.core.content.ContextCompat
import io.github.anastaci1a.localica.ui.AppIcon
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
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

/** Process-wide icon pack. The UI resolver reads [masks]; settings read [info]. */
object IconPacks {
    private val maskState = MutableStateFlow<Map<String, Bitmap>>(emptyMap())
    private val infoState = MutableStateFlow<IconPackInfo?>(null)
    val masks: StateFlow<Map<String, Bitmap>> get() = maskState
    val info: StateFlow<IconPackInfo?> get() = infoState
    private fun directory(context: Context) = File(context.filesDir, "icon-pack")

    /** Loads the stored pack, if any. Call off Main. */
    fun load(context: Context) {
        val stored = runCatching { readDirectory(directory(context)) }.getOrNull()
        maskState.value = stored?.first ?: emptyMap()
        infoState.value = stored?.second
    }

    /** Validates [input] and replaces the stored pack. Throws [IconPackException] when the pack is unusable. */
    fun import(context: Context, input: InputStream): IconPackReport {
        val parsed = parse(input)
        val target = directory(context)
        val staging = File(context.filesDir, "icon-pack.new").also { it.deleteRecursively(); File(it, "icons").mkdirs() }
        File(staging, IconPackNames.MANIFEST).writeText(JSONObject().put("name", parsed.report.info.name)
            .put("version", parsed.report.info.version).apply { parsed.report.info.author?.let { put("author", it) } }.toString())
        parsed.files.forEach { (id, bytes) -> File(staging, "icons/$id.png").writeBytes(bytes) }
        target.deleteRecursively()
        check(staging.renameTo(target)) { "Could not store the icon pack" }
        maskState.value = parsed.masks
        infoState.value = parsed.report.info
        return parsed.report
    }

    fun reset(context: Context) {
        directory(context).deleteRecursively()
        maskState.value = emptyMap()
        infoState.value = null
    }

    /** Writes every built-in icon as a ready-to-edit template pack. */
    fun exportTemplate(context: Context, output: OutputStream) {
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry(IconPackNames.MANIFEST))
            zip.write(JSONObject().put("name", "Built-in icons").put("version", "1").toString().toByteArray())
            zip.closeEntry()
            AppIcon.entries.forEach { icon ->
                zip.putNextEntry(ZipEntry("icons/${icon.id}.png"))
                zip.write(renderBuiltin(context, icon))
                zip.closeEntry()
            }
        }
    }

    private fun renderBuiltin(context: Context, icon: AppIcon): ByteArray {
        val drawable = requireNotNull(ContextCompat.getDrawable(context, icon.builtin)).mutate()
        val bitmap = Bitmap.createBitmap(IconPackNames.TEMPLATE_SIZE, IconPackNames.TEMPLATE_SIZE, Bitmap.Config.ARGB_8888)
        drawable.setTint(android.graphics.Color.BLACK)
        drawable.setBounds(0, 0, bitmap.width, bitmap.height)
        drawable.draw(Canvas(bitmap))
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray().also { bitmap.recycle() }
    }

    private class Parsed(val report: IconPackReport, val masks: Map<String, Bitmap>, val files: Map<String, ByteArray>)

    private fun parse(input: InputStream): Parsed {
        var manifest: IconPackInfo? = null
        val masks = linkedMapOf<String, Bitmap>()
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
                if (id !in AppIcon.ids) { unknown += id; continue }
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bitmap == null) { rejected += entry.name to "not a decodable image"; continue }
                val problem = IconPackNames.sizeProblem(bitmap.width, bitmap.height)
                if (problem != null) { rejected += entry.name to problem; bitmap.recycle(); continue }
                masks[id] = bitmap
                files[id] = if (entry.name.endsWith(".png", ignoreCase = true)) bytes else
                    ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            }
        }
        val info = manifest ?: throw IconPackException("pack.json is missing")
        if (masks.isEmpty()) throw IconPackException("The pack has no usable icons")
        return Parsed(IconPackReport(info, masks.keys.toList(), unknown, rejected), masks, files)
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

    private fun readDirectory(directory: File): Pair<Map<String, Bitmap>, IconPackInfo>? {
        val manifest = File(directory, IconPackNames.MANIFEST).takeIf { it.isFile } ?: return null
        val info = manifest(manifest.readBytes())
        val masks = AppIcon.entries.mapNotNull { icon ->
            File(directory, "icons/${icon.id}.png").takeIf { it.isFile }?.let { BitmapFactory.decodeFile(it.path) }?.let { icon.id to it }
        }.toMap()
        return if (masks.isEmpty()) null else masks to info
    }

    /** The stored icon for [id] as decoded alpha, for tests. */
    internal fun maskFor(id: String): Bitmap? = maskState.value[id]
}
