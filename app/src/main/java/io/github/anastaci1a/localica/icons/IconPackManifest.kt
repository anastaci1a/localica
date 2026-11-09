package io.github.anastaci1a.localica.icons

import io.github.anastaci1a.localica.ui.AppIcon
import org.json.JSONObject
import java.security.MessageDigest

/** The archive version is independent of the author's pack version. Image paths are relative to icons/. */
internal data class IconPackManifest(
    val info: IconPackInfo,
    val schemaVersion: Int,
    val icons: Map<String, String>,
    val images: Map<String, JSONObject> = emptyMap(),
) {
    fun json(): JSONObject = JSONObject().put("schemaVersion", 2).put("name", info.name).put("version", info.version)
        .apply { info.author?.let { put("author", it) } }
        .put("icons", JSONObject(icons)).put("images", JSONObject(images))

    companion object {
        fun parse(bytes: ByteArray): IconPackManifest {
            val json = try { JSONObject(String(bytes, Charsets.UTF_8)) }
            catch (_: Exception) { throw IconPackException("pack.json is not valid JSON") }
            val name = json.optString("name").trim()
            val version = json.opt("version")?.toString()?.trim().orEmpty()
            if (name.isEmpty()) throw IconPackException("pack.json needs a name")
            if (version.isEmpty()) throw IconPackException("pack.json needs a version")
            val schema = json.opt("schemaVersion") ?: 1
            if (schema !is Number || schema.toDouble() !in listOf(1.0, 2.0))
                throw IconPackException("Unsupported icon pack schemaVersion")
            val icons = linkedMapOf<String, String>()
            if (schema.toInt() == 2) {
                val mappings = json.optJSONObject("icons") ?: throw IconPackException("Schema v2 needs an icons map")
                if (mappings.length() > 1024) throw IconPackException("The pack has too many icon mappings")
                mappings.keys().forEach { id ->
                    val file = mappings.opt(id)
                    if (!id.matches(Regex("[a-z][a-z0-9_]*\\.[a-z][a-z0-9_]*")) || file !is String || !imageFilename(file))
                        throw IconPackException("Invalid icon mapping: $id")
                    icons[id] = file
                }
            }
            val images = linkedMapOf<String, JSONObject>()
            val metadata = json.optJSONObject("images")
            if (json.has("images") && metadata == null) throw IconPackException("images must be an object")
            metadata?.keys()?.forEach { file ->
                if (!imageFilename(file)) throw IconPackException("Invalid image filename: $file")
                val value = metadata.optJSONObject(file) ?: throw IconPackException("Invalid image metadata: $file")
                fun finiteNumber(value: Any?): Boolean = value is Number && value.toDouble().isFinite()
                for ((field, keys) in listOf("opticalOffset" to listOf("x", "y"), "opticalBounds" to listOf("left", "top", "right", "bottom"))) {
                    if (!value.has(field)) continue
                    val obj = value.optJSONObject(field) ?: throw IconPackException("Invalid $field: $file")
                    if (keys.any { !finiteNumber(obj.opt(it)) }) throw IconPackException("Invalid $field: $file")
                    if (field == "opticalBounds" && (obj.getDouble("left") >= obj.getDouble("right") || obj.getDouble("top") >= obj.getDouble("bottom")))
                        throw IconPackException("Invalid opticalBounds: $file")
                }
                if (value.has("designedWeight") && (!finiteNumber(value.opt("designedWeight")) || value.getDouble("designedWeight") <= 0))
                    throw IconPackException("Invalid designedWeight: $file")
                images[file] = value
            }
            return IconPackManifest(IconPackInfo(name, version, json.optString("author").trim().takeIf { it.isNotEmpty() }), schema.toInt(), icons, images)
        }

        fun imageFilename(file: String): Boolean = file.matches(Regex("[A-Za-z0-9_][A-Za-z0-9_.-]*\\.(?i:svg|png|webp)")) && ".." !in file
    }
}

/** Hash original accepted bytes, never a rasterized approximation of a vector. */
internal fun imageHash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { "%02x".format(it.toInt() and 255) }

internal fun closestIconId(id: String): String = AppIcon.ids.minWith(compareBy<String> { candidate ->
    var row = IntArray(candidate.length + 1) { it }
    id.forEachIndexed { index, a ->
        val next = IntArray(candidate.length + 1)
        next[0] = index + 1
        candidate.forEachIndexed { j, b -> next[j + 1] = minOf(next[j] + 1, row[j + 1] + 1, row[j] + if (a == b) 0 else 1) }
        row = next
    }
    row.last()
}.thenBy { it })

/** One image row can serve several IDs. Missing replacements are represented by built-in image rows. */
data class IconPackImage(val filename: String, val ids: List<String>)
