package io.github.anastaci1a.localica.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMacrobenchmarkApi
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Diagnostic only. Qualified A35 comparisons use the plain twin and the #971 runner. */
@RunWith(Parameterized::class)
@OptIn(ExperimentalMetricApi::class, ExperimentalMacrobenchmarkApi::class)
class CostMapBenchmark(private val id: String) {
    @get:Rule val benchmark = MacrobenchmarkRule()

    @Test fun scenario() {
        val driver = Journeys()
        val scenario = driver.scenarios.single { it.getString("id") == id }
        val cold = id == "cold-start"
        benchmark.measureRepeated(
            packageName = PACKAGE,
            metrics = listOf(FrameTimingMetric(), TraceSectionMetric("Choreographer#doFrame%", TraceSectionMetric.Mode.Sum, "ChoreographerDoFrame")) +
                if (cold) listOf(StartupTimingMetric()) else emptyList(),
            compilationMode = CompilationMode.Ignore(),
            startupMode = if (cold) StartupMode.COLD else null,
            iterations = InstrumentationRegistry.getArguments().getString("iterations", "5").toInt().also { require(it >= 5) },
            setupBlock = { driver.prepare(this, scenario) },
            measureBlock = { driver.run(this, scenario) }
        )
        driver.verify(scenario)
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun scenarios(): List<Array<String>> {
            val selected = InstrumentationRegistry.getArguments().getString("scenario")
            return Journeys().scenarios.filter { selected == null || it.getString("id") == selected }.map { arrayOf(it.getString("id")) }
        }
    }
}
