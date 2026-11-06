package io.github.anastaci1a.localica

import android.os.Build
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.platform.LocalLayoutDirection
import io.github.anastaci1a.localica.localicaComposeRule as createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import io.github.anastaci1a.localica.ui.legacyLayerSnapshot
import java.io.File
import java.io.FileDescriptor
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** #501: the Android 8 emulator's gralloc reserves an 8-byte ashmem header but reads RGBA
 * pixels back at a 16-byte offset, so a CPU lock of a buffer whose RGBA byte count is 4084 or
 * 4088 modulo 4096 writes its last one or two pixels past the mapping into whatever follows.
 * The API < 28 layer readback (exercised on every API) must never ask for such a buffer. Each buffer this readback maps is
 * watched at the byte after its end; an unmapped neighbour gets a private probe page, and a
 * foreign neighbour is compared (and restored) only against this test's distinctive pixel. */
class LegacyReadbackGrallocBoundsTest {
    @get:Rule val compose = createComposeRule()

    private data class Mapping(val start: Long, val end: Long, val name: String)
    private class Watch(val end: Long, val neighbour: String, val before: Long?, val probePage: Boolean) {
        var after: Long? = null
    }

    private val ink = Color(0xFF123456)
    // Premultiplied opaque RGBA bytes 12 34 56 FF, read as one little-endian Int.
    private val inkBytes = 0xFF563412.toInt()

    private fun mappings(): List<Mapping> = File("/proc/self/maps").readLines().map { line ->
        val parts = line.trim().split(Regex("\\s+"), limit = 6)
        val range = parts[0].split('-')
        Mapping(java.lang.Long.parseUnsignedLong(range[0], 16), java.lang.Long.parseUnsignedLong(range[1], 16),
            parts.getOrElse(5) { "" })
    }

    private fun grallocStarts() = mappings().filter { "gralloc-buffer" in it.name }.map { it.start }.toSet()

    private fun peek(address: Long): Long? = try {
        RandomAccessFile("/proc/self/mem", "r").use { memory ->
            memory.seek(address)
            ByteArray(8).also { memory.readFully(it) }.let { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).long }
        }
    } catch (_: Exception) { null }

    private fun poke(address: Long, value: Long) {
        try {
            RandomAccessFile("/proc/self/mem", "rw").use { memory ->
                memory.seek(address)
                memory.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array())
            }
        } catch (_: Exception) { }
    }

    /** Overran iff the first neighbouring word now holds this test's pixel and did not before. */
    private fun Watch.overran(): Boolean {
        val now = after ?: return false
        return now.toInt() == inkBytes && before?.toInt() != inkBytes
    }

    private fun readback(context: GraphicsContext, density: Density, direction: LayoutDirection, size: IntSize): Pair<ImageBitmap, List<Watch>> {
        val layer = compose.runOnIdle {
            context.createGraphicsLayer().also { it.record(density, direction, size) { drawRect(ink) } }
        }
        val existing = grallocStarts()
        val watches = mutableListOf<Watch>()
        val probePages = mutableListOf<Long>()
        try {
            val image = runBlocking {
                withContext(Dispatchers.Main) {
                    layer.legacyLayerSnapshot(density, direction, straightAlpha = false) { phase ->
                        when (phase) {
                            // Buffers are mapped once the producer has queued; the CPU lock follows.
                            "legacy-image-callback" -> {
                                val all = mappings()
                                all.filter { "gralloc-buffer" in it.name && it.start !in existing }.forEach { buffer ->
                                    val next = all.firstOrNull { it.start <= buffer.end && buffer.end < it.end }
                                    var probe = false
                                    if (next == null) {
                                        val placed = Os.mmap(buffer.end, 4096, OsConstants.PROT_READ or OsConstants.PROT_WRITE,
                                            OsConstants.MAP_PRIVATE or OsConstants.MAP_ANONYMOUS, FileDescriptor(), 0)
                                        if (placed == buffer.end) { probePages += placed; probe = true } else Os.munmap(placed, 4096)
                                    }
                                    watches += Watch(buffer.end, if (probe) "probe page" else next?.name?.ifEmpty { "[anonymous]" } ?: "unmapped",
                                        peek(buffer.end), probe)
                                }
                            }
                            // Pixels are locked and copied; inspect, and undo any overrun into foreign memory.
                            "legacy-pixels-copied" -> watches.forEach { watch ->
                                watch.after = peek(watch.end)
                                val original = watch.before
                                if (watch.overran() && !watch.probePage && original != null) poke(watch.end, original)
                            }
                        }
                    }
                }
            }
            return image to watches
        } finally {
            probePages.forEach { Os.munmap(it, 4096) }
            compose.runOnIdle { context.releaseGraphicsLayer(layer) }
        }
    }

    @Test fun legacyReadbackNeverWritesPastItsGrallocMapping() {
        // The readback runs on every API; only Android 8.x emulator buffers are named gralloc-buffer.
        lateinit var context: GraphicsContext
        lateinit var density: Density
        lateinit var direction: LayoutDirection
        compose.setContent {
            val graphics = LocalGraphicsContext.current
            val currentDensity = LocalDensity.current
            val layout = LocalLayoutDirection.current
            SideEffect { context = graphics; density = currentDensity; direction = layout }
        }
        compose.waitForIdle()
        // RGBA byte counts 4088, 4084 and 4088 (mod 4096); the last is an ordinary odd layer size.
        val failures = listOf(IntSize(511, 2), IntSize(1021, 1), IntSize(73, 14)).mapNotNull { size ->
            val (image, watches) = readback(context, density, direction, size)
            Log.i("GrallocBounds", "$size " + watches.joinToString { w ->
                "end=${java.lang.Long.toHexString(w.end)} next=${w.neighbour} before=${w.before?.let(java.lang.Long::toHexString)} after=${w.after?.let(java.lang.Long::toHexString)}" })
            assertEquals("Readback keeps the layer's logical width", size.width, image.width)
            assertEquals("Readback keeps the layer's logical height", size.height, image.height)
            val pixels = image.toPixelMap()
            assertEquals("Last pixel is the recorded colour", ink, pixels[size.width - 1, size.height - 1])
            if (Build.VERSION.SDK_INT < 28) assertTrue("A readback buffer was observed for $size", watches.isNotEmpty())
            watches.filter { it.overran() }.takeIf { it.isNotEmpty() }?.let { overruns ->
                "$size wrote past " + overruns.joinToString { "${java.lang.Long.toHexString(it.end)} into ${it.neighbour}" }
            }
        }
        assertTrue("Gralloc readback overran its mapping: $failures", failures.isEmpty())
    }
}
