package io.github.anastaci1a.localica.ui.engine

import io.github.anastaci1a.localica.ui.spec.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** #854 review F6 (rule 12): one closed-form damped spring. The engine's Damped flies [SpringKernel] and the spec's admission
 * (springState, springExtrema) composes it, so the reach admission proves is the reach the runtime flies. */
class SpringKernelSingleImplementationTest {
    private val states = listOf(0.0 to 0.0, 0.0 to 5.0, .3 to -20.0, 1.02 to 3.0, -.04 to 40.0)

    @Test fun dampedAndTheAdmissionEvaluateTheSameKernelBitForBit() {
        for (zeta in listOf(.5, .78, 1.0, 1.4, 3.0)) for ((x, v) in states) for (omega in listOf(20.0, 28.0, 120.0)) {
            val q = 1.0
            val kernel = SpringKernel(omega, zeta)
            val damped = Damped(x, v, q, omega, zeta, 1.0, 1.0 / 120)
            for (i in 0..400) {
                val t = i / 400.0
                assertEquals(kernel.response(x - q, v, t, false) + q, damped.raw(t, false), 0.0)
                assertEquals(kernel.response(x - q, v, t, true), damped.raw(t, true), 0.0)
                val (value, velocity) = springState(x, v, q, omega, zeta, t)
                assertEquals("zeta=$zeta x=$x v=$v t=$t", damped.raw(t, false), value, 0.0)
                assertEquals(damped.raw(t, true), velocity, 0.0)
            }
            // The admission's extrema are the kernel's values at its velocity zeros: the same points Damped.extrema evaluates.
            val reach = springExtrema(x, v, q, omega, zeta)
            val zeros = kernel.velocityZeros(x - q, v).filter { it > 0 }.map { damped.raw(it, false) }
            assertEquals((listOf(x, q) + zeros).min(), reach.start, 0.0)
            assertEquals((listOf(x, q) + zeros).max(), reach.endInclusive, 0.0)
        }
    }

    @Test fun theStockReversalReachIsUnchanged() {
        val reach = popupReversalReach(StockPopupMotion)
        assertEquals(0.0, reach.values.minOf { it.start }, 1e-12)
        // The peak is the from-rest open overshoot (1.01992, the reviewers' independent witness); no reversal passes it.
        assertEquals(1 + springOvershoot(StockPopupMotion.open.zeta.toDouble()), reach.values.maxOf { it.endInclusive }, 1e-9)
    }

    /** Source contract: the closed form (the damped-frequency response and its velocity-zero solve) exists once, in the spec. */
    @Test fun noOtherClosedFormExists() {
        val ui = File("src/main/java/io/github/anastaci1a/localica/ui")
        val form = Regex("""cos\(\w+\*t\)|atan2\(v,""")
        val holders = ui.walkTopDown().filter { it.isFile && it.extension == "kt" && form.containsMatchIn(it.readText()) }
            .map { it.relativeTo(ui).invariantSeparatorsPath }.toList()
        assertEquals(listOf("spec/UiSpec.kt"), holders)
        assertTrue("Damped composes the kernel", "SpringKernel(omega,zeta)" in File(ui, "engine/Trajectories.kt").readText())
    }
}
