package io.github.anastaci1a.localica

import androidx.test.platform.app.InstrumentationRegistry
import io.github.anastaci1a.localica.library.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ArtworkCacheTest {
    @Test fun artworkIsSizedReusedAndInvalidatedIncludingMissingCovers() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val covered = File(context.cacheDir, "cache-cover.mp3")
        val uncovered = File(context.cacheDir, "cache-empty.opus")
        try {
            instrumentation.context.assets.open("tagged.mp3").use { input -> covered.outputStream().use(input::copyTo) }
            instrumentation.context.assets.open("tagged.opus").use { input -> uncovered.outputStream().use(input::copyTo) }
            ArtworkCache.invalidate()
            val uri = covered.toURI().toString()
            assertNull(ArtworkCache.peek(listOf(uri)))
            val first = requireNotNull(ArtworkCache.read(context, uri, 128))
            assertTrue(first.width <= 128 && first.height <= 128)
            assertSame(first, ArtworkCache.read(context, uri, 100))
            assertSame(first, ArtworkCache.peek(listOf(uri), 768)?.bitmap) // retain a thumbnail while a larger decode loads
            val large = requireNotNull(ArtworkCache.read(context, uri, 768))
            assertTrue(large.width > first.width && large.width <= 768)
            assertNull(ArtworkCache.read(context, uncovered.toURI().toString(), 128))
            assertSame(first, EmbeddedCoverProvider.load(context, listOf(uncovered.toURI().toString(), uri), 128))
            assertSame(first, ArtworkCache.peek(listOf(uncovered.toURI().toString(), uri), 128)?.bitmap)
            assertNotNull(ArtworkCache.peek(listOf(uncovered.toURI().toString())))
            ArtworkCache.invalidate()
            assertNull(ArtworkCache.peek(listOf(uri)))
            assertNotSame(first, ArtworkCache.read(context, uri, 128))
        } finally { covered.delete(); uncovered.delete(); ArtworkCache.invalidate() }
    }
}
