package io.github.anastaci1a.localica.ui

import kotlin.math.abs

internal const val MarqueePauseMillis = 1800L
internal const val MarqueeFadeMillis = 220L
internal const val MarqueeReturnMillis = 260L

internal enum class MarqueePhase { Rest, LeadingIn, Scrolling, Returning, LeadingOut }

internal data class MarqueeFrame(
    val offset: Float = 0f,
    val repeatDistance: Float = 0f,
    val leadingFade: Float = 0f,
    val phase: MarqueePhase = MarqueePhase.Rest,
)

/** One retained clock per line. Viewport changes never recreate the text or its motion. */
internal class MarqueeMotion {
    var frame = MarqueeFrame()
        private set
    private var phaseStarted = 0L
    private var lastTime: Long? = null
    private var wasAllowed = false
    private var returnForward = false
    private var returnTravel = 0f
    private var fadeStart = 0f

    fun advance(timeMillis: Long, textWidth: Float, viewportWidth: Float, speed: Float,
        allowed: Boolean): MarqueeFrame {
        val previousTime = lastTime ?: timeMillis
        lastTime = timeMillis
        if (!allowed) when (frame.phase) {
            MarqueePhase.Scrolling -> {
                returnForward = frame.offset > frame.repeatDistance / 2f
                returnTravel = if (returnForward) frame.repeatDistance - frame.offset else frame.offset
                frame = frame.copy(phase = MarqueePhase.Returning, leadingFade = 1f)
                phaseStarted = timeMillis
            }
            MarqueePhase.LeadingIn -> beginFadeOut(timeMillis)
            else -> Unit
        }
        // Keep the incoming copy beyond the resting viewport if expansion makes
        // this line fit. Forward returns anchor that copy, so widening cannot tug it back.
        val distance = maxOf(frame.repeatDistance, viewportWidth, textWidth + viewportWidth / 3f)
        if (frame.repeatDistance > 0f && distance != frame.repeatDistance) {
            frame = frame.copy(repeatDistance = distance,
                offset = if (frame.phase == MarqueePhase.Returning && returnForward)
                    frame.offset + distance - frame.repeatDistance else frame.offset)
        }
        if (allowed && !wasAllowed && frame.phase == MarqueePhase.Rest) phaseStarted = timeMillis
        wasAllowed = allowed
        val elapsed = (timeMillis - phaseStarted).coerceAtLeast(0L)
        when (frame.phase) {
            MarqueePhase.Rest -> {
                frame = frame.copy(repeatDistance = textWidth + viewportWidth / 3f)
                if (allowed && elapsed >= MarqueePauseMillis) {
                    frame = frame.copy(phase = MarqueePhase.LeadingIn)
                    phaseStarted = timeMillis
                }
            }
            MarqueePhase.LeadingIn -> {
                frame = frame.copy(leadingFade = marqueeEase(elapsed.toFloat() / MarqueeFadeMillis))
                if (elapsed >= MarqueeFadeMillis) {
                    frame = frame.copy(phase = MarqueePhase.Scrolling, leadingFade = 1f)
                    phaseStarted = timeMillis
                }
            }
            MarqueePhase.Scrolling -> {
                val offset = frame.offset + speed * (timeMillis - previousTime).coerceAtLeast(0L) / 1000f
                if (offset >= frame.repeatDistance) {
                    frame = frame.copy(offset = 0f)
                    beginFadeOut(timeMillis)
                } else frame = frame.copy(offset = offset)
            }
            MarqueePhase.Returning -> {
                val remaining = returnTravel * (1f - marqueeEase(elapsed.toFloat() / MarqueeReturnMillis))
                frame = frame.copy(offset = if (returnForward) frame.repeatDistance - remaining else remaining,
                    leadingFade = 1f)
                if (elapsed >= MarqueeReturnMillis || abs(returnTravel) < .001f) {
                    // The next copy is pixel-identical at the origin. Normalize only
                    // after arrival; the leading edge remains fully present on this frame.
                    frame = frame.copy(offset = 0f)
                    beginFadeOut(timeMillis)
                }
            }
            MarqueePhase.LeadingOut -> {
                frame = frame.copy(leadingFade = fadeStart * (1f - marqueeEase(elapsed.toFloat() / MarqueeFadeMillis)))
                if (elapsed >= MarqueeFadeMillis) {
                    frame = frame.copy(phase = MarqueePhase.Rest, leadingFade = 0f)
                    phaseStarted = timeMillis
                }
            }
        }
        return frame
    }

    fun pauseRemaining(timeMillis: Long): Long =
        (MarqueePauseMillis - (timeMillis - phaseStarted)).coerceAtLeast(0L)

    private fun beginFadeOut(timeMillis: Long) {
        fadeStart = frame.leadingFade
        frame = frame.copy(phase = MarqueePhase.LeadingOut)
        phaseStarted = timeMillis
    }
}

internal fun marqueeEase(fraction: Float): Float = fraction.coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }

/** The trailing mask follows actual clipped ink, including the incoming wrap copy. */
internal fun marqueeTrailingFade(textWidth: Float, viewportWidth: Float, fadeWidth: Float,
    frame: MarqueeFrame): Float {
    if (fadeWidth <= 0f) return 0f
    var clipped = (textWidth - frame.offset - viewportWidth).coerceAtLeast(0f)
    if (frame.offset > 0f && frame.repeatDistance - frame.offset < viewportWidth) {
        clipped = maxOf(clipped, frame.repeatDistance - frame.offset + textWidth - viewportWidth)
    }
    return marqueeEase(clipped / fadeWidth)
}

internal fun playerMarqueePaused(progress: Float, expanded: Boolean, dragging: Boolean,
    settling: Boolean): Boolean = dragging || settling || progress != if (expanded) 1f else 0f
