package io.github.anastaci1a.localica.ui.engine.diagnostics.fixture

import kotlin.math.abs
import kotlin.math.max

/** Synthetic immutable captures only. An asserted marker here is not native capture evidence. */
internal class ReferenceCapture(val width: Int, val height: Int, pixels: FloatArray, windows: List<String>,
    val epoch: Long, val generation: Long, val clockSequence: Long, val presentedGeneration: Long?,
    val colorSpace: String = "linear-sRGB", val premultiplied: Boolean = false) {
    private val rgba = pixels.copyOf()
    val windowIds = windows.toList()
    init { require(width > 0 && height > 0 && width.toLong()*height <= Int.MAX_VALUE/4 && rgba.size.toLong() == width.toLong()*height*4 && epoch >= 0 && generation >= 0 && clockSequence >= 0 && rgba.all { it.isFinite() && it in 0f..1f }) }
    fun channel(index: Int) = rgba[index]
}
internal data class ShadowComparison(val maximumRgbaError: Double, val meanRgbaError: Double, val missingCoveragePixels: Int)
internal fun compareShadow(actual: ReferenceCapture, reference: ReferenceCapture, background: ReferenceCapture, minimumSignal: Double): ShadowComparison {
    require(minimumSignal.isFinite() && minimumSignal > 0)
    for (capture in listOf(actual,reference,background)) {
        require(capture.width == reference.width && capture.height == reference.height && capture.windowIds == reference.windowIds && capture.windowIds.distinct().size == capture.windowIds.size && capture.windowIds.isNotEmpty())
        require(capture.epoch == reference.epoch && capture.generation == reference.generation && capture.clockSequence == reference.clockSequence && capture.colorSpace == reference.colorSpace && capture.premultiplied == reference.premultiplied)
        require(capture.presentedGeneration?.let { it >= capture.generation } == true) { "Incomplete presented-generation evidence" }
    }
    var worst = 0.0; var sum = 0.0; var missing = 0; var signal = false
    for (pixel in 0 until reference.width*reference.height) {
        var expectedSignal = 0.0; var actualSignal = 0.0
        for (channel in 0..3) {
            val i = pixel*4+channel; val error = abs(actual.channel(i).toDouble()-reference.channel(i))
            worst = max(worst,error); sum += error
            expectedSignal = max(expectedSignal,abs(reference.channel(i).toDouble()-background.channel(i)))
            actualSignal = max(actualSignal,abs(actual.channel(i).toDouble()-background.channel(i)))
        }
        if (expectedSignal >= minimumSignal) { signal = true; if (actualSignal < minimumSignal) missing++ }
    }
    require(signal) { "Blank reference cannot establish coverage" }
    return ShadowComparison(worst,sum/(reference.width*reference.height*4),missing)
}
