package io.github.anastaci1a.localica

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.AndroidDocuments
import io.github.anastaci1a.localica.library.ArtworkReader
import io.github.anastaci1a.localica.library.decodeArtwork
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class EmbeddedTagsTest {
    @Test fun readsEditableTagsAndBoundedEmbeddedArtwork() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        for (extension in listOf("mp3", "m4a", "flac", "ogg", "opus")) {
            val file = File(context.cacheDir, "tagged.$extension")
            instrumentation.context.assets.open(file.name).use { input -> file.outputStream().use(input::copyTo) }
            try {
                val uri = Uri.fromFile(file).toString()
                val tags = AndroidDocuments(context).metadata(uri)
                assertEquals(extension, "Tagged tone", tags.title)
                assertEquals(extension, "Test album", tags.album)
                assertEquals(extension, "Track artist", tags.artist)
                assertEquals(extension, "Album artist", tags.albumArtist)
                assertEquals(extension, 2024, tags.year)
                assertEquals(extension, 2, tags.discNumber)
                assertEquals(extension, 3, tags.trackNumber)
                assertEquals(extension, "Personal notes", tags.comment)
                val art = ArtworkReader(context).read(uri)
                if (extension in listOf("mp3", "m4a", "flac")) {
                    assertNotNull(extension, art)
                    assertTrue(art!!.width in 1..768 && art.height in 1..768)
                } else assertNull(extension, art)
            } finally { file.delete() }
        }
    }

    @Test fun technicalCommentsNeverBecomeLibraryComments() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        for (name in listOf("tagged.mp3", "tone.wav")) for (technicalFirst in listOf(true, false))
            for (userComment in listOf(true, false)) {
                val file = File(context.cacheDir, "comments-${java.util.UUID.randomUUID()}.${name.substringAfterLast('.')}")
                instrumentation.context.assets.open(name).use { input -> file.outputStream().use(input::copyTo) }
                try {
                    seedComments(file, technicalFirst, userComment)
                    val tags = AndroidDocuments(context).metadata(Uri.fromFile(file).toString(), file.name)
                    assertEquals("$name technicalFirst=$technicalFirst", if (userComment) "Personal notes" else null, tags.comment)
                } finally { file.delete() }
            }
    }

    @Test fun missingOrCorruptArtworkFallsBackWithoutThrowing() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertNull(ArtworkReader(context).read("file:///missing.mp3"))
        assertNull(decodeArtwork(byteArrayOf(1, 2, 3, 4)))
    }
}
