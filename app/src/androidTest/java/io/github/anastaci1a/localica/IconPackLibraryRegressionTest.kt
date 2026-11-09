package io.github.anastaci1a.localica

import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.icons.IconPacks
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Uses the pre-library API too: importing another pack must retain the first pack's internal images. */
class IconPackLibraryRegressionTest {
    @Test fun importingAnotherPackRetainsBothInternalCopies() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(app.cacheDir, "icon-library-regression").also { it.deleteRecursively(); it.mkdirs() }
        val context = object : ContextWrapper(app) { override fun getFilesDir() = root }
        try {
            fun pack(name: String, id: String): ByteArray {
                val image = ByteArrayOutputStream().also { output ->
                    val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(android.graphics.Color.BLACK)
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                    bitmap.recycle()
                }.toByteArray()
                return ByteArrayOutputStream().also { output -> ZipOutputStream(output).use { zip ->
                    zip.putNextEntry(ZipEntry("pack.json")); zip.write("""{"name":"$name","version":"1"}""".toByteArray()); zip.closeEntry()
                    zip.putNextEntry(ZipEntry("icons/$id.png")); zip.write(image); zip.closeEntry()
                } }.toByteArray()
            }
            IconPacks.load(context)
            IconPacks.import(context, pack("First", "action.pin").inputStream())
            IconPacks.import(context, pack("Second", "transport.play").inputStream())
            assertEquals("Both imports must remain available without their source documents", setOf("action.pin.png", "transport.play.png"),
                root.walkTopDown().filter { it.extension == "png" }.map { it.name }.toSet())
        } finally {
            IconPacks.reset(context)
            root.deleteRecursively()
            IconPacks.load(app)
        }
    }
}
