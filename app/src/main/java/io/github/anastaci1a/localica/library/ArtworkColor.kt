package io.github.anastaci1a.localica.library

import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.conflate
import kotlin.math.pow
import kotlin.math.roundToInt

/** Average artwork colours: computed once per artwork identity, persisted in `track_artwork`,
 * and published as an in-memory index so loading placeholders are tinted before any decode (#525). */
object ArtworkColors {
    /** Index value for checked tracks without embedded artwork. Stored colours are always opaque. */
    const val MISSING = 0

    private val state = mutableStateOf<Map<String, Int>>(emptyMap())
    @Volatile var ready = false
        private set
    @Volatile internal var requests: ArtworkColorRequests? = null
    /** True while an activity is started; the bulk backfill stops with the app. */
    val foreground = MutableStateFlow(false)

    /** Observed read: a composition that calls this recomposes when the index is republished. */
    fun lookup(uris: List<String>): Int? = resolve(state.value, uris)
    /** Unobserved read for decisions that must not subscribe a cell to index updates. */
    fun peek(uris: List<String>): Int? = Snapshot.withoutReadObservation { resolve(state.value, uris) }

    /** Null while unknown; [MISSING] only when every cover candidate is known to have none. */
    internal fun resolve(index: Map<String, Int>, uris: List<String>): Int? {
        if (uris.isEmpty()) return null
        for (uri in uris) {
            val color = index[uri] ?: return null
            if (color != MISSING) return color
        }
        return MISSING
    }

    internal fun publish(index: Map<String, Int>) {
        state.value = index
        ready = true
    }

    /** A visible placeholder without a colour asks for it ahead of the bulk backfill. */
    fun prioritize(uris: List<String>) { requests?.prioritize(uris) }
    fun withdraw(uris: List<String>) { requests?.withdraw(uris) }
    /** A cover decoded for display also supplies its average; [bitmap] null means confirmed missing. */
    fun offer(uri: String, bitmap: Bitmap?) {
        val requests = requests ?: return
        if (peek(listOf(uri)) != null) return
        requests.offer(uri, bitmap?.let(::averageArtworkColor) ?: MISSING)
    }
}

internal interface ArtworkColorRequests {
    fun prioritize(uris: List<String>)
    fun withdraw(uris: List<String>)
    fun offer(uri: String, color: Int)
}

private val linearTable = FloatArray(256) { srgbToLinear(it / 255f) }

private fun srgbToLinear(value: Float): Float =
    if (value <= .04045f) value / 12.92f else ((value + .055f) / 1.055f).toDouble().pow(2.4).toFloat()

private fun linearToSrgb(value: Float): Float {
    val v = value.coerceIn(0f, 1f)
    return if (v <= .0031308f) v * 12.92f else (1.055 * v.toDouble().pow(1 / 2.4) - .055).toFloat()
}

/** Alpha-weighted mean in linear light, returned as an opaque sRGB ARGB colour.
 * Fully transparent input averages to opaque black rather than [ArtworkColors.MISSING]. */
internal fun averageArtworkColor(pixels: IntArray): Int {
    var r = 0.0; var g = 0.0; var b = 0.0; var weight = 0.0
    for (pixel in pixels) {
        val a = (pixel ushr 24 and 0xFF) / 255.0
        if (a <= 0.0) continue
        r += linearTable[pixel shr 16 and 0xFF] * a
        g += linearTable[pixel shr 8 and 0xFF] * a
        b += linearTable[pixel and 0xFF] * a
        weight += a
    }
    if (weight <= 0.0) return 0xFF000000.toInt()
    fun channel(sum: Double) = (linearToSrgb((sum / weight).toFloat()) * 255f).roundToInt().coerceIn(0, 255)
    return (0xFF shl 24) or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
}

internal const val ARTWORK_COLOR_EDGE = 16

/** Downscales to 16x16 with filtering, then averages; never on the main thread. */
internal fun averageArtworkColor(bitmap: Bitmap): Int {
    val source = if (bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
    val small = Bitmap.createScaledBitmap(source, ARTWORK_COLOR_EDGE, ARTWORK_COLOR_EDGE, true)
    val pixels = IntArray(ARTWORK_COLOR_EDGE * ARTWORK_COLOR_EDGE)
    small.getPixels(pixels, 0, ARTWORK_COLOR_EDGE, 0, 0, ARTWORK_COLOR_EDGE, ARTWORK_COLOR_EDGE)
    if (small !== source && small !== bitmap) small.recycle()
    if (source !== bitmap) source.recycle()
    return averageArtworkColor(pixels)
}

class RoomArtworkColorStore(private val dao: LibraryDao) : ArtworkColorStore {
    override suspend fun pending(limit: Int) = dao.pendingArtwork(limit)
    override suspend fun sources(uris: List<String>) = uris.distinct().chunked(500).flatMap { dao.artworkSources(it) }
    override suspend fun save(colors: List<Pair<ArtworkSource, Int>>) { if (colors.isNotEmpty()) dao.saveArtworkColors(colors) }
}

/** Builds and publishes the URI index off Main. Backfill bursts are coalesced to one
 * publication per [spacingMillis], so visible cells recompose at most that often. */
internal suspend fun publishArtworkColors(rows: kotlinx.coroutines.flow.Flow<List<ArtworkColorRow>>, spacingMillis: Long = 400) {
    var first = true
    rows.conflate().collect { list ->
        val started = android.os.SystemClock.elapsedRealtime()
        val index = HashMap<String, Int>(list.size * 2)
        list.forEach { index[it.uri] = it.color ?: ArtworkColors.MISSING }
        ArtworkColors.publish(index)
        if (first) {
            first = false
            val sinceStart = started - android.os.Process.getStartElapsedRealtime()
            android.util.Log.i("ArtworkColors", "index ready: ${index.size} uris, built in " +
                "${android.os.SystemClock.elapsedRealtime() - started} ms, ${sinceStart} ms after process start")
        }
        kotlinx.coroutines.delay(spacingMillis)
    }
}
