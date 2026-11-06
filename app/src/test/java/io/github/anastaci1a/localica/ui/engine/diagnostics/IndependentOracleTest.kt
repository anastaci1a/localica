package io.github.anastaci1a.localica.ui.engine.diagnostics

import io.github.anastaci1a.localica.ui.engine.diagnostics.fixture.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.math.*

class IndependentOracleTest {
    private fun curve(value: (Double) -> Double, slope: (Double) -> Double) = object : Curve {
        override fun at(p: Double) = value(p)
        override fun slope(p: Double) = slope(p)
    }
    @Test fun validLinearProductMixAndJoinedCurvesHaveConvergentIndependentSlopes() {
        val registry = CurveRegistry()
        registry.register("linear",curve({ 2*it+1 },{ 2.0 }),floatArrayOf())
        registry.register("product",curve({ it*it },{ 2*it }),floatArrayOf(0f,.8f,.83f))
        registry.register("mix",curve({ it*it*(1-it)+it*.5 },{ 2*it-3*it*it+.5 }),floatArrayOf(.5f))
        registry.register("piecewise-C1",curve({ if (it < .5) 0.0 else (it-.5)*(it-.5) },{ if (it < .5) 0.0 else 2*(it-.5) }),floatArrayOf(.5f))
        assertTrue(registry.verify(600000).isEmpty())
        assertEquals(registry.verify(600000),registry.verify(600000))
    }
    @Test fun incorrectSlopesJumpsTiesAndBlankOrDuplicateIdsAreRejected() {
        val registry = CurveRegistry()
        val slope = curve({ it*it },{ 0.0 }); val joins = floatArrayOf(.5f)
        registry.register("wrong",slope,joins); joins[0] = Float.NaN // Registration owns its copy.
        registry.register("jump",curve({ if (it < .5) it else it+.01 },{ 1.0 }),floatArrayOf(.5f))
        registry.register("tie",curve({ .2*max(.5+it,.5-it) },{ if (it < 0) -.2 else .2 }),floatArrayOf(0f))
        registry.register("nan",curve({ Double.NaN },{ 0.0 }),floatArrayOf())
        val result = registry.verify(600000)
        assertTrue(result.any { it.code == "CurveSlopeMismatch" && it.detail.startsWith("wrong") })
        assertTrue(result.any { it.code == "CurveValueJoin" && it.detail.startsWith("jump") })
        assertTrue(result.any { it.code == "CurveVelocityJoin" && it.detail.startsWith("tie") })
        assertTrue(result.any { it.code == "NonFiniteCurve" })
        assertThrows(IllegalArgumentException::class.java) { registry.register("wrong",curve({ it },{ 1.0 }),floatArrayOf()) }
        assertThrows(IllegalArgumentException::class.java) { registry.register(" ",slope,floatArrayOf()) }
        assertThrows(IllegalArgumentException::class.java) { registry.register("outside",slope,floatArrayOf(1.1f)) }
    }
    @Test fun hermiteEndpointAndDerivativeRootVectorsCatchBetweenFrameOvershoot() {
        val smooth = Hermite(0.0,1.0,0.0,0.0,1.0)
        assertEquals(0.0,smooth.value(0.0),0.0); assertEquals(1.0,smooth.value(1.0),0.0)
        assertEquals(.5,smooth.value(.5),1e-12); assertEquals(1.5,smooth.velocity(.5),1e-12)
        val bad = Hermite(1.049,.99,10.0,-1.0,1.0/120)
        val bounded = Hermite(1.049,.99,10.0,-1.0,.0001)
        assertTrue(bad.extrema().maxOf { bad.value(it) } > 1.05)
        assertTrue(bounded.extrema().all { bounded.value(it) in -.05..1.05 })
        assertEquals(10.0,bounded.velocity(0.0),1e-9); assertEquals(-1.0,bounded.velocity(1.0),1e-9)
    }
    @Test fun twoPoleAndCriticalFixedVectorsRejectTheOldTwoSecondTruncation() {
        val value = dampedDisplacement(2.0,1.0,1.0,0.0,2.0)
        val (position,velocity) = overdampedBounds(2.0,1.0,1.0,0.0,2.0)
        assertEquals(.63036,value,1e-5); assertEquals(.6756653643622399,position,1e-12)
        assertTrue(position >= abs(value)); assertTrue(position > 3e-4 && velocity > 3e-4)
        assertEquals(3*exp(-2.0),dampedDisplacement(1.0,1.0,1.0,0.0,2.0),1e-12)
        assertEquals(cos(2.0),dampedDisplacement(0.0,1.0,1.0,0.0,2.0),1e-12)
        val shortenedTail = min(.064,1/(.85*sqrt(380.0)))
        assertEquals(.06035,shortenedTail,1e-5); assertTrue(shortenedTail*.85*sqrt(380.0) <= 1.0)
    }
    private fun capture(pixels: FloatArray, generation: Long = 1, presented: Long? = generation, width: Int = 2) =
        ReferenceCapture(width,1,pixels,listOf("activity","popup"),1,generation,99,presented)
    @Test fun rgbaOracleRejectsMeanCancellationMissingFieldAlphaAndStaleNativeGeneration() {
        val background = capture(floatArrayOf(0f,0f,0f,0f,0f,0f,0f,0f))
        val reference = capture(floatArrayOf(0f,0f,0f,.2f,0f,0f,0f,.2f))
        val opposite = capture(floatArrayOf(0f,0f,0f,.21f,0f,0f,0f,.19f))
        val oldMean = ((opposite.channel(3)-reference.channel(3))+(opposite.channel(7)-reference.channel(7)))/2
        assertEquals(0f,oldMean,1e-7f) // A mean-only acceptance check passes the corrupt field.
        assertTrue(compareShadow(opposite,reference,background,.01).maximumRgbaError > .008)
        assertEquals(2,compareShadow(background,reference,background,.01).missingCoveragePixels)
        val alpha = capture(floatArrayOf(0f,0f,0f,.19f,0f,0f,0f,.19f))
        assertTrue(compareShadow(alpha,reference,background,.01).maximumRgbaError > .008)
        assertEquals(0.0,compareShadow(reference,reference,background,.01).maximumRgbaError,0.0)
        assertThrows(IllegalArgumentException::class.java) { compareShadow(reference,background,background,.01) }
        assertThrows(IllegalArgumentException::class.java) { compareShadow(capture(floatArrayOf(0f,0f,0f,.2f),width=1),reference,background,.01) }
        assertThrows(IllegalArgumentException::class.java) { compareShadow(capture(FloatArray(8),generation=2),reference,background,.01) }
        assertThrows(IllegalArgumentException::class.java) { compareShadow(capture(FloatArray(8),presented=null),reference,background,.01) }
    }
    @Test fun referenceCaptureOwnsItsBufferAndRejectsInvalidMetadata() {
        val pixels = floatArrayOf(0f,0f,0f,.2f,0f,0f,0f,.2f)
        val image = capture(pixels); pixels.fill(0f); assertEquals(.2f,image.channel(3),0f)
        assertThrows(IllegalArgumentException::class.java) { capture(FloatArray(8),generation=-1) }
        assertThrows(IllegalArgumentException::class.java) { capture(FloatArray(4),width=Int.MAX_VALUE) }
    }
    @Test fun fixtureScalarReadAllocationUsesSupportedWarmedCounterAndControl() {
        // Android compiles this source set; the supported allocation counter exists only on the host JVM.
        val bean = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
        val api = Class.forName("com.sun.management.ThreadMXBean")
        check(api.getMethod("isThreadAllocatedMemorySupported").invoke(bean) == true)
        api.getMethod("setThreadAllocatedMemoryEnabled",Boolean::class.javaPrimitiveType).invoke(bean,true)
        val bytes = api.getMethod("getThreadAllocatedBytes",Long::class.javaPrimitiveType)
        val id = Thread.currentThread().id
        fun allocated() = bytes.invoke(bean,id) as Long
        repeat(64) { allocated() }
        val reader = object { @Volatile var stamp = 1f; fun value(): Float = stamp*.5f }
        fun loop(read: Boolean) { var sum = 0f; repeat(1_000_000) { sum += if (read) reader.value() else .5f }; blackHole = sum }
        repeat(5) { loop(false); loop(true) }
        val start = allocated(); loop(false); val control = allocated()-start
        val before = allocated(); loop(true); val reads = allocated()-before
        assertEquals(control,reads) // Subtract identical reflective counter overhead using the empty-loop control.
        val out = File("build/reports/u12-foundation"); out.mkdirs()
        File(out,"allocation.json").writeText("{\"fixtureOnly\":true,\"productionMotionMeasured\":false,\"reads\":1000000,\"controlBytes\":$control,\"readBytes\":$reads,\"netReadBytes\":${reads-control}}\n")
    }
    companion object { @Volatile private var blackHole = 0f }
}
