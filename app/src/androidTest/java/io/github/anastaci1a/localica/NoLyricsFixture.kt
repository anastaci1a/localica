package io.github.anastaci1a.localica

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.Track
import io.github.anastaci1a.localica.lyrics.LyricsRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.io.Closeable
import java.io.File

/** A verified readable source with no lyrics, distinct from a missing/unreadable file. */
internal class NoLyricsFixture(title: String) : Closeable {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val file = File.createTempFile("no-lyrics-", ".mp3", instrumentation.targetContext.cacheDir)
    val track = Track(Uri.fromFile(file).toString(), title, fileName = file.name, relativePath = file.name)
    init {
        try {
            instrumentation.context.assets.open("queue-tone.mp3").use { input -> file.outputStream().use(input::copyTo) }
            val result = runBlocking { LyricsRepository(instrumentation.targetContext).load(track, 0) }
            assertNull("Empty fixture must be readable", result.problem)
            assertTrue("Empty fixture must contain no lyrics", result.sources.isEmpty())
        } catch (failure: Throwable) { file.delete(); throw failure }
    }
    override fun close() { file.delete() }
}
