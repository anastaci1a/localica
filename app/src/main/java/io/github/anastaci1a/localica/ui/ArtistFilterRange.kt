package io.github.anastaci1a.localica.ui

import androidx.compose.runtime.*
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.ui.MotionDurationScale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Own a short-lived real scroll extent while the NEW filtered content is measured.
 * A visible artist header supplies the exact old pixel offset; no row-height estimate is used.
 * The transparent trailing item prevents Foundation clamping before the ease-out can run.
 */
@Stable internal class ArtistFilterRange {
    class Request(val scrollOffset: Int, val reservePx: Int, val previousLayout: Any)
    var request by mutableStateOf<Request?>(null)
        private set
    var interruption by mutableStateOf(Any())
        private set
    private var motion: Job? = null

    private fun cancelMotion() {
        motion?.cancel()
        motion = null
    }

    fun begin(headerTop: Int?, viewport: Int, previousLayout: Any): Request? {
        // Retire the old animation before publishing replacement rows/scroll extent.
        cancelMotion()
        val scroll = headerTop?.let { (-it.toLong()).coerceAtLeast(0) }
        request = if (scroll == null || viewport <= 0 || scroll + viewport > Int.MAX_VALUE) null
            else Request(scroll.toInt(), (scroll + viewport).toInt(), previousLayout)
        return request
    }
    fun owns(value: Request) = request === value
    fun interrupt() {
        cancelMotion()
        if (request != null) interruption = Any()
    }
    fun bindMotion(value: Request, job: Job): Boolean {
        if (!owns(value)) { job.cancel(); return false }
        if (motion !== job) cancelMotion()
        motion = job
        return true
    }
    fun releaseMotion(job: Job) { if (motion === job) motion = null }
    fun finish(value: Request) { if (owns(value)) request = null }

    /** Keep the animation owner, but release Foundation's scroll flag before awaiting a frame. */
    suspend fun correct(value: Request, state: ScrollableState, ownership: BrowseScrollOwnership,
        excess: Float, reducedMotion: Boolean) {
        val context = currentCoroutineContext()
        val job = context[Job]!!
        if (!bindMotion(value, job)) return
        var consumed = 0f
        suspend fun apply(target: Float) {
            context.ensureActive()
            // A keyboard/accessibility/fling mutation may have begun between frames.
            // Never cancel it by reacquiring an equal-priority mutation for old motion.
            if (!owns(value) || state.isScrollInProgress) throw CancellationException("Artist range interrupted")
            ownership.during {
                state.scroll {
                    // Lazy layout or a retiring mutation can suspend acquisition.
                    context.ensureActive()
                    if (!owns(value)) throw CancellationException("Artist range replaced")
                    consumed += scrollBy(target - consumed)
                }
            }
        }
        try {
            if (reducedMotion) apply(excess)
            else {
                val animation = TargetBasedAnimation(tween<Float>(280, easing = LinearOutSlowInEasing),
                    Float.VectorConverter, 0f, excess)
                val start = withFrameNanos { it }
                var frame = start
                do {
                    val scale = context[MotionDurationScale]?.scaleFactor ?: 1f
                    val playTime = if (scale == 0f) animation.durationNanos else ((frame - start) / scale).toLong()
                    apply(animation.getValueFromNanos(playTime))
                    if (animation.isFinishedFromNanos(playTime)) break
                    frame = withFrameNanos { it }
                } while (true)
            }
        } finally { releaseMotion(job) }
    }

    companion object {
        const val GuardKey = "artist-filter-range-guard"
        /** The guard begins exactly where real rows end. Existing bottom padding is retained.
         * No visible guard means real rows still fill the viewport: the current offset is valid.
         * Foundation clamps negative scroll at zero if the complete new content is shorter.
         */
        fun excess(guardTop: Int?, viewportEnd: Int, bottomPadding: Int): Float =
            if (guardTop == null) 0f else minOf(0L,
                guardTop.toLong() + bottomPadding - viewportEnd).toFloat()
    }
}
