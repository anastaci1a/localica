package io.github.anastaci1a.localica

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.unit.IntSize

/** Diagnostic distribution variant ONLY; never part of a production integration diff.
 * Input drives both modes identically. No timer, readback, oracle or intermediate refresh.
 * Native RGBA kernel copied from the byte-zero atlas fixture, including final extraction.
 */
class AtlasBenchmarkActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val packed = intent.getBooleanExtra("atlas", false)
        setContent {
            val graphics = LocalGraphicsContext.current
            var scroll by remember { mutableFloatStateOf(0f) }
            val child = rememberGraphicsLayer()
            val source = rememberGraphicsLayer().apply { clip = true; compositingStrategy = CompositingStrategy.Offscreen }
            val buffers = remember(graphics) { Buffers(graphics) }
            DisposableEffect(buffers) { onDispose { buffers.release() } }
            Canvas(Modifier.fillMaxSize().pointerInput(Unit) {
                detectDragGestures { change, amount -> change.consume(); scroll += amount.y }
            }) {
                val extent = IntSize(size.width.toInt(), size.height.toInt())
                val offset = scroll
                child.record(size = extent) {
                    drawRect(Color(0xFF17253B))
                    for (i in -2..70) {
                        val top = i * 72f + offset % 288f
                        drawRect(if (i % 2 == 0) Color(0xC0E0A020) else Color(0x804020F0),
                            Offset(16.25f, top), Size(size.width - 32.5f, 53.5f))
                        for (column in 0..7) drawCircle(Color(0xA040D0A0), 14.75f,
                            Offset(60f + column * 133.5f, top + 26f))
                    }
                }
                if (buffers.sourceSize != extent) {
                    source.record(size = extent) { drawLayer(child) }
                    buffers.sourceSize = extent
                }
                val cropSize = IntSize(256, 128)
                for (i in 0..1) buffers.crop[i].record(size = cropSize) {
                    clipRect(right = 192f, bottom = 99f) {
                        translate(if (i == 0) -32.25f else -580.5f,
                            if (i == 0) -64.75f else -420.25f) { drawLayer(source) }
                    }
                }
                if (!buffers.ready) {
                    if (packed) buffers.recordAtlas(this, cropSize) else buffers.recordIndependent(this, cropSize)
                    buffers.ready = true
                }
                drawLayer(source)
                for (i in 0..1) translate(if (i == 0) 32.25f else 580.5f,
                    if (i == 0) 64.75f else 420.25f) {
                    scale(192f, 99f, Offset.Zero) { drawLayer(buffers.output[i]) }
                }
            }
        }
    }
    private class Buffers(private val graphics: GraphicsContext) {
        private val owned = mutableListOf<GraphicsLayer>()
        private fun layer() = graphics.createGraphicsLayer().apply {
            compositingStrategy = CompositingStrategy.Offscreen; clip = true; owned += this
        }
        val crop = listOf(layer(), layer())
        val output = mutableListOf<GraphicsLayer>()
        var sourceSize: IntSize? = null
        var ready = false
        fun recordIndependent(scope: DrawScope, start: IntSize) = with(scope) {
            for (input in crop) {
                var previous = input
                var before = start
                while (before.width > 1 || before.height > 1) {
                    val next = IntSize((before.width / 2).coerceAtLeast(1), (before.height / 2).coerceAtLeast(1))
                    val prior = previous; val priorSize = before
                    previous = layer().also { it.record(size = next) {
                        scale(next.width.toFloat() / priorSize.width, next.height.toFloat() / priorSize.height, Offset.Zero) { drawLayer(prior) }
                    } }
                    before = next
                }
                output += previous
            }
        }
        fun recordAtlas(scope: DrawScope, start: IntSize) = with(scope) {
            var previous: GraphicsLayer? = null
            var before = start
            while (before.width > 1 || before.height > 1) {
                val next = IntSize((before.width / 2).coerceAtLeast(1), (before.height / 2).coerceAtLeast(1))
                val prior = previous; val priorSize = before
                previous = layer().also { it.record(size = IntSize(next.width * 4, next.height)) {
                    for (member in 0..1) translate(if (member == 0) 0f else next.width + 1f, 0f) {
                        clipRect(right = next.width.toFloat(), bottom = next.height.toFloat()) {
                            scale(next.width.toFloat() / priorSize.width, next.height.toFloat() / priorSize.height, Offset.Zero) {
                                translate(if (member == 1 && prior != null) -(priorSize.width + 1f) else 0f, 0f) {
                                    drawLayer(prior ?: crop[member])
                                }
                            }
                        }
                    }
                } }
                before = next
            }
            val final = requireNotNull(previous)
            for (member in 0..1) output += layer().also { it.record(size = IntSize(1, 1)) {
                translate(if (member == 0) 0f else -2f, 0f) { drawLayer(final) }
            } }
        }
        fun release() { owned.forEach(graphics::releaseGraphicsLayer) }
    }
}
