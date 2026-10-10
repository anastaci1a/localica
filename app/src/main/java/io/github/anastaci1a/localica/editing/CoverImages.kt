package io.github.anastaci1a.localica.editing

import android.content.ContentUris
import android.content.Context
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
    fun remember(uri: String) {
        val entries = (listOf(uri) + remembered()).distinct().take(12)
        preferences.edit().putString("recent", JSONArray(entries).toString()).apply()
    }
    private fun remembered(): List<String> = runCatching {
        val array = JSONArray(preferences.getString("recent", "[]")); (0 until array.length()).map(array::getString)
    }.getOrDefault(emptyList())
    suspend fun recent(): List<String> = withContext(Dispatchers.IO) {
        val device = runCatching {
            context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID), null, null, "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { cursor ->
                buildList { while (size < 12 && cursor.moveToNext()) add(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0)).toString()) }
            }.orEmpty()
        }.getOrDefault(emptyList())
        (device + remembered()).distinct().take(20)
    }
    suspend fun read(uri: String, edge: Int = 2048): CoverImage = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(Uri.parse(uri))?.use { input ->
            val output = ByteArrayOutputStream(); val buffer = ByteArray(16384)
            while (true) { val n = input.read(buffer); if (n < 0) break
                require(output.size() + n <= 24 * 1024 * 1024) { "Choose an image smaller than 24 MB." }; output.write(buffer, 0, n) }
            output.toByteArray()
        } ?: error("Image is unavailable. Choose it again.")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "This image format is not supported." }
        var sample = 1
        while (bounds.outWidth / sample > edge || bounds.outHeight / sample > edge) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: error("Couldn’t decode the image.")
        val orientation = runCatching { ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)
        val matrix = Matrix().apply { when (orientation) {
            2 -> setScale(-1f, 1f); 3 -> setRotate(180f); 4 -> setScale(1f, -1f)
            5 -> { setRotate(90f); postScale(-1f, 1f) }; 6 -> setRotate(90f)
            7 -> { setRotate(-90f); postScale(-1f, 1f) }; 8 -> setRotate(-90f)
        } }
        val image = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        val alpha = image.hasAlpha()
        val output = ByteArrayOutputStream()
        image.compress(if (alpha) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 92, output)
        CoverImage(output.toByteArray(), if (alpha) "image/png" else "image/jpeg", image.width, image.height).also {
            if (image !== decoded) image.recycle(); decoded.recycle()
        }
    }
}
