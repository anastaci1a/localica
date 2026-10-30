package io.github.anastaci1a.localica.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap

/** Scalar correlation only. Hashes are not globally unique; byte reports are not heap totals. */
internal data class GlassPreparationImage(val image: Int, val bitmap: Int, val width: Int,
    val height: Int, val config: String, val mutable: Boolean, val reportedBytes: Int)
internal data class GlassPreparationWork(val kind: String, val owner: Int, val epoch: Long,
    val startedNs: Long, val endedNs: Long, val images: List<GlassPreparationImage?>,
    val sameImage: List<Boolean>, val sameBitmap: List<Boolean>, val parameter: Float,
    val cacheBytes: Int, val cacheHits: Int, val cacheMisses: Int, val cacheEvictions: Int,
    val reportedCopyBytes: Int, val failure: String?)
internal interface GlassPreparationWorkObserver {
    fun record(event: GlassPreparationWork)
    fun observationFailed(kind: String, failure: String)
}
internal val LocalGlassPreparationWorkObserver = staticCompositionLocalOf<GlassPreparationWorkObserver?> { null }

/** Does not retain arguments. An observation failure cannot replace the original UI outcome. */
internal fun GlassPreparationWorkObserver?.glassPreparationEvent(kind: String,
    images: List<ImageBitmap?>, owner: Int = 0, epoch: Long = -1,
    startedNs: Long = System.nanoTime(), endedNs: Long = startedNs,
    parameter: Float = Float.NaN, cacheBytes: Int = -1, cacheHits: Int = -1,
    cacheMisses: Int = -1, cacheEvictions: Int = -1, reportedCopyBytes: Int = -1,
    failure: String? = null) {
    val observer = this ?: return
    try {
        val bitmaps = images.map { it?.asAndroidBitmap() }
        val metadata = images.mapIndexed { i, image -> image?.let {
            val bitmap = requireNotNull(bitmaps[i])
            GlassPreparationImage(System.identityHashCode(it), System.identityHashCode(bitmap),
                bitmap.width, bitmap.height, bitmap.config.toString(), bitmap.isMutable, bitmap.allocationByteCount)
        } }
        observer.record(GlassPreparationWork(kind, owner, epoch, startedNs, endedNs, metadata,
            images.flatMap { a -> images.map { b -> a != null && a === b } },
            bitmaps.flatMap { a -> bitmaps.map { b -> a != null && a === b } },
            parameter, cacheBytes, cacheHits, cacheMisses, cacheEvictions, reportedCopyBytes, failure))
    } catch (problem: Throwable) {
        try { observer.observationFailed(kind, problem.javaClass.name) } catch (_: Throwable) { }
    }
}

/** Worker-stage wall time, excluding outer metadata/export; nested copy-observer work is included. Not CPU or queue time. */
internal inline fun measuredGlassPreparation(observer: GlassPreparationWorkObserver?, kind: String,
    image: ImageBitmap, owner: Int, epoch: Long, parameter: Float = Float.NaN,
    operation: () -> ImageBitmap): ImageBitmap {
    if (observer == null) return operation()
    val start = System.nanoTime()
    val output = try { operation() } catch (failure: Throwable) {
        val end = System.nanoTime()
        observer.glassPreparationEvent(kind, listOf(image), owner, epoch, start, end,
            parameter, failure = failure.javaClass.name)
        throw failure
    }
    val end = System.nanoTime()
    observer.glassPreparationEvent(kind, listOf(image, output), owner, epoch, start, end, parameter)
    return output
}

internal fun GlassPreparationWorkObserver?.glassReadCopyObserver(kind: String, image: ImageBitmap,
    owner: Int = 0, epoch: Long = -1): ((Int) -> Unit)? = this?.let { observer ->
    { bytes -> observer.glassPreparationEvent(kind, listOf(image), owner, epoch, reportedCopyBytes = bytes) }
}
