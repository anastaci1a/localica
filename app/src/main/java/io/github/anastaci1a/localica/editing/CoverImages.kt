package io.github.anastaci1a.localica.editing

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.provider.DocumentsContract
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class CoverImages(private val context: Context) {
    private val preferences = context.getSharedPreferences("cover-images", 0)
    private val grants by lazy { sharedGrants(context) }
    fun selected(uri: String) {
        remember(uri)
        grants.selected(uri)
    }
    fun retain(uri: String): AutoCloseable = grants.retain(uri)
    fun remember(uri: String) {
        val entries = (listOf(uri) + remembered()).distinct().take(12)
        check(preferences.edit().putString("recent", JSONArray(entries).toString()).commit()) { "Couldn’t remember this image." }
    }
    private fun remembered(): List<String> = runCatching {
        val array = JSONArray(preferences.getString("recent", "[]")); (0 until array.length()).map(array::getString)
    }.getOrDefault(emptyList())
    suspend fun recent(includeDevicePhotos: Boolean): List<String> = withContext(Dispatchers.IO) {
        val device = if (includeDevicePhotos) runCatching {
            context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID), null, null, "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { cursor ->
                buildList { while (size < 12 && cursor.moveToNext()) add(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0)).toString()) }
            }.orEmpty()
        }.getOrDefault(emptyList()) else emptyList()
        (device + remembered()).distinct().take(20)
    }
    suspend fun read(uri: String, edge: Int = 2048): CoverImage = withContext(Dispatchers.IO) {
        val image = decodeOwned(uri, edge) {}
        try {
            val alpha = image.hasAlpha()
            val output = ByteArrayOutputStream()
            image.compress(if (alpha) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 92, output)
            CoverImage(output.toByteArray(), if (alpha) "image/png" else "image/jpeg", image.width, image.height)
        } finally { image.recycle() }
    }

    /** Fresh oriented pixels owned by the caller; previews do not need an encoded round trip.
     * Cancellation during dispatcher handoff retires an image that could not be delivered. */
    internal suspend fun decode(uri: String, edge: Int, onDecode: () -> Unit = {}): Bitmap {
        var owned: Bitmap? = null
        try {
            return withContext(Dispatchers.IO) {
                decodeOwned(uri, edge, onDecode).also { owned = it }
            }
        } catch (failure: Throwable) {
            owned?.recycle()
            throw failure
        }
    }

    private fun decodeOwned(uri: String, edge: Int, onDecode: () -> Unit): Bitmap {
        val bytes = retain(uri).use { context.contentResolver.openInputStream(Uri.parse(uri))?.use { input ->
            val output = ByteArrayOutputStream(); val buffer = ByteArray(16384)
            while (true) { val n = input.read(buffer); if (n < 0) break
                require(output.size() + n <= 24 * 1024 * 1024) { "Choose an image smaller than 24 MB." }; output.write(buffer, 0, n) }
            output.toByteArray()
        } ?: error("Image is unavailable. Choose it again.") }
        onDecode()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "This image format is not supported." }
        var sample = 1
        while (bounds.outWidth / sample > edge || bounds.outHeight / sample > edge) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: error("Couldn’t decode the image.")
        val orientation = runCatching { ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)
        try {
            val matrix = Matrix().apply { when (orientation) {
                2 -> setScale(-1f, 1f); 3 -> setRotate(180f); 4 -> setScale(1f, -1f)
                5 -> { setRotate(90f); postScale(-1f, 1f) }; 6 -> setRotate(90f)
                7 -> { setRotate(-90f); postScale(-1f, 1f) }; 8 -> setRotate(-90f)
            } }
            val image = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            if (image !== decoded) decoded.recycle()
            return image
        } catch (failure: Throwable) {
            decoded.recycle()
            throw failure
        }
    }
    companion object {
        private val owners = mutableMapOf<android.content.SharedPreferences, ArtworkGrants>()
        @Synchronized private fun sharedGrants(context: Context): ArtworkGrants {
            val app = context.applicationContext
            val prefs = app.getSharedPreferences("cover-images", 0)
            owners[prefs]?.let { return it }
            fun entries(key: String): Set<String> {
                val array = JSONArray(prefs.getString(key, "[]"))
                return (0 until array.length()).map(array::getString).toSet()
            }
            return ArtworkGrants(
                loadOwned = { entries("owned-read-grants") },
                saveOwned = { prefs.edit().putString("owned-read-grants", JSONArray(it.toList()).toString()).commit() },
                retained = { entries("recent") },
                alreadyGranted = { value -> app.contentResolver.persistedUriPermissions.any { it.uri.toString() == value } },
                eligible = { value -> Uri.parse(value).let { it.scheme == "content" && !DocumentsContract.isTreeUri(it) } },
                // Conservative app budget, not a guarantee about OEM platform limits.
                // Legacy grants are not ours to revoke; use the picker’s transient access instead.
                canAcquire = { app.contentResolver.persistedUriPermissions.size < 64 },
                acquire = { app.contentResolver.takePersistableUriPermission(Uri.parse(it), Intent.FLAG_GRANT_READ_URI_PERMISSION) },
                release = { value ->
                    if (app.contentResolver.persistedUriPermissions.any { it.uri.toString() == value && it.isReadPermission })
                        app.contentResolver.releasePersistableUriPermission(Uri.parse(value), Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
            ).also { owners[prefs] = it }
        }
    }

}
