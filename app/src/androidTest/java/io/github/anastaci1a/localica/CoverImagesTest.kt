package io.github.anastaci1a.localica

import android.graphics.Bitmap
import android.media.ExifInterface
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.editing.CoverImages
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class CoverImagesTest {
    @Test fun deviceEnumerationIsOptionalAndCannotHideRememberedImages() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = base.getSharedPreferences("cover-images", 0)
        val original = preferences.getString("recent", null)
        var resolverRequests = 0
        val context = object : android.content.ContextWrapper(base) {
            override fun getContentResolver(): android.content.ContentResolver {
                resolverRequests++
                throw SecurityException("Photo access revoked")
            }
        }
        try {
            preferences.edit().remove("recent").commit()
            val images = CoverImages(context)
            val remembered = "content://previously-selected/image"
            images.remember(remembered)
            assertEquals(listOf(remembered), images.recent(includeDevicePhotos = false))
            assertEquals(0, resolverRequests)
            assertEquals(listOf(remembered), images.recent(includeDevicePhotos = true))
            assertEquals(1, resolverRequests)
        } finally { preferences.edit().putString("recent", original).commit() }
    }

    @Test fun importedPicturesHonorOrientationAndDecodeBounds() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "cover-orientation.jpg")
        try {
            val bitmap = Bitmap.createBitmap(2048, 1024, Bitmap.Config.ARGB_8888)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }; bitmap.recycle()
            ExifInterface(file.path).apply { setAttribute(ExifInterface.TAG_ORIENTATION, "6"); saveAttributes() }
            val image = CoverImages(context).read(Uri.fromFile(file).toString(), 128)
            assertEquals(64, image.width); assertEquals(128, image.height)
            assertTrue(image.bytes.isNotEmpty())
            assertTrue(runCatching { CoverImages(context).read("content://missing/image") }.isFailure)
        } finally { file.delete() }
    }
}
