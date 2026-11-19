package io.github.anastaci1a.localica

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Picture
import android.graphics.RuntimeShader
import io.github.anastaci1a.localica.ui.GlassColorShader
import io.github.anastaci1a.localica.ui.GlassTone
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

/** #633: the production AGSL tone ([GlassColorShader]) matches the Kotlin [GlassTone] on the GPU, including the
 * trim limit above weight 2, and its rendered rows never reverse brightness. Runtime shaders need API 33. */
class GlassToneShaderParityTest {
    private val source = """
        uniform float3 tone;
        $GlassColorShader
        half4 main(float2 p) {
            float x = floor(p.x) / 255.0;
            return half4(half3(glassTone(float3(x), 0.0, tone)), 1.0);
        }
    """

    @androidx.test.filters.SdkSuppress(minSdkVersion = 33)
    @Test fun shaderMatchesKotlinAtSamplePoints() {
        val tones = listOf(
            GlassTone(.25f, 2f, false), GlassTone(.25f, 2f, true), GlassTone(.5f, 2f, true), GlassTone(.5f, 1f, false),
            GlassTone(.5f, 1.5f, true), GlassTone(.3f, 3.7f, true), GlassTone(.3f, 3.7f, false),
            GlassTone(.5f, 8f, true), GlassTone(.5f, 8f, false), GlassTone.mixed(.5f, 8f, .5f),
            GlassTone(.1f, 8f, true), GlassTone(.125f, 8f, false), GlassTone(.4f, 5.5f, true), GlassTone.mixed(.35f, 6f, .3f))
        fun renderTone(fragment: String): Pair<Bitmap, Bitmap> {
            val picture = Picture()
            val canvas = picture.beginRecording(256, tones.size)
            tones.forEachIndexed { row, tone ->
                val shader = RuntimeShader(source.replace(GlassColorShader, fragment))
                    .apply { setFloatUniform("tone", tone.trim, tone.weight, tone.lightMix) }
                canvas.drawRect(0f, row.toFloat(), 256f, row + 1f, Paint().apply { this.shader = shader })
            }
            picture.endRecording()
            val hardware = Bitmap.createBitmap(picture)
            return hardware to requireNotNull(hardware.copy(Bitmap.Config.ARGB_8888, false))
        }
        val (hardware, rendered) = renderTone(GlassColorShader)
        val (originalHardware, originalRendered) = renderTone(FrozenBroadToneShader)
        val failures = mutableListOf<String>()
        tones.forEachIndexed { row, tone ->
            var last = -1
            for (i in 0..255) {
                assertEquals("Broad grade keeps original GPU bytes at row=$row sample=$i",
                    originalRendered.getPixel(i, row), rendered.getPixel(i, row))
                val gpu = android.graphics.Color.red(rendered.getPixel(i, row))
                val kotlin = tone.channel(i / 255f) * 255f
                if (abs(gpu - kotlin) > 1.5f) failures += "$tone at $i: shader $gpu, Kotlin $kotlin"
                if (gpu < last - 1) failures += "$tone at $i: shader reverses $last -> $gpu"
                last = maxOf(last, gpu)
            }
        }
        assertTrue(failures.take(20).joinToString("\n"), failures.isEmpty())
        // The limit is applied by the shader itself: at weight 8 white maps to 7/8, not to 1 − .5.
        val white = android.graphics.Color.red(rendered.getPixel(255, tones.indexOf(GlassTone(.5f, 8f, true))))
        assertEquals(.875f * 255f, white.toFloat(), 1.5f)
        val samples = ((0..4).flatMap { r -> (0..4).flatMap { g -> (0..4).map { b -> floatArrayOf(r / 4f, g / 4f, b / 4f) } } } + listOf(floatArrayOf(4f, 4f, 4f)))
            .flatMap { rgb -> listOf(0f, .25f, .5f, 1f).map { alpha -> rgb + alpha } }
        val face = Picture()
        val faceCanvas = face.beginRecording(samples.size, 1)
        samples.forEachIndexed { x, rgb ->
            val fill = RuntimeShader("""
                uniform float4 sample;
                $GlassColorShader
                half4 main(float2 p) { return half4(half3(glassColor(sample.rgb / max(sample.a, 0.0001), .5, float3(.5, 2.0, 0.0)) * sample.a / 2.0), 1.0); }
            """).apply { setFloatUniform("sample", rgb[0] * rgb[3], rgb[1] * rgb[3], rgb[2] * rgb[3], rgb[3]) }
            faceCanvas.drawRect(x.toFloat(), 0f, x + 1f, 1f, Paint().apply { shader = fill })
        }
        face.endRecording()
        val faceHardware = Bitmap.createBitmap(face)
        val facePixels = requireNotNull(faceHardware.copy(Bitmap.Config.ARGB_8888, false))
        try {
            val rows = arrayOf(floatArrayOf(.5400391f, -.2017822f, -.0203094f),
                floatArrayOf(-.0599365f, .3984375f, -.0203705f), floatArrayOf(-.0599976f, -.2016602f, .5795898f))
            samples.forEachIndexed { x, rgb ->
                val pixel = facePixels.getPixel(x, 0)
                val channels = listOf(android.graphics.Color.red(pixel), android.graphics.Color.green(pixel), android.graphics.Color.blue(pixel))
                rows.forEachIndexed { channel, row ->
                    val expected = minOf(row.indices.sumOf { (row[it] * rgb[it]).toDouble() }.toFloat() + .7001953f, 1.06961f) * rgb[3] * .5f * 255f
                    assertEquals("Face sample=$x channel=$channel", expected, channels[channel].toFloat(), 1.5f)
                }
            }
        } finally { facePixels.recycle(); faceHardware.recycle(); rendered.recycle(); hardware.recycle(); originalRendered.recycle(); originalHardware.recycle() }

    }
}

private const val FrozenBroadToneShader = """
    // effectiveGlassTrim (#633): weights above 1/trim limit the trim to 1/weight, so brightness never inverts.
    float glassTrim(float3 tone) { return tone.x * tone.y > 1.0 ? 1.0 / tone.y : tone.x; }
    float3 glassColor(float3 color, float whiteTint, float3 tone) {
        float luma = clamp(dot(color, float3(0.2126, 0.7152, 0.0722)), 0.0, 1.0);
        float lifted = 1.0 - (1.0 - luma) * (1.0 - whiteTint) / max(1.0 - whiteTint * luma, 0.0001);
        float tint = clamp((lifted - luma) / max(1.0 - luma, 0.0001), 0.0, 1.0);
        color = mix(color, float3(1), tint);
        color = clamp(color, 0.0, 1.0);
        if (tone.x > 0.0) {
            // tone.z is the light-ink mix: 0 and 1 are the two exact polarities, between them their blend.
            float trim = glassTrim(tone);
            float3 darkEnd = 1.0 - color;
            float3 darkWeighted = tone.y == 2.0 ? darkEnd * darkEnd : tone.y == 1.0 ? darkEnd : pow(darkEnd, float3(tone.y));
            float3 lightWeighted = tone.y == 2.0 ? color * color : tone.y == 1.0 ? color : pow(color, float3(tone.y));
            color += mix(darkWeighted * trim, -lightWeighted * trim, clamp(tone.z, 0.0, 1.0));
        }
        return color;
    }
    float3 glassTone(float3 color, float whiteTint, float3 tone) { return glassColor(color, whiteTint, tone); }
"""
