package io.github.anastaci1a.localica.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule val profile = BaselineProfileRule()

    @Test fun startup() = profile.collect(PACKAGE, includeInStartupProfile = true) {
        pressHome()
        startActivityAndWait()
    }

    @Test fun journeys() {
        val driver = Journeys()
        val selected = InstrumentationRegistry.getArguments().getString("scenario")
        driver.scenarios.filter { it.getString("id") != "cold-start" && (selected == null || it.getString("id") == selected) }.forEach { scenario ->
            profile.collect(PACKAGE, outputFilePrefix = "journey-" + scenario.getString("id"), includeInStartupProfile = false) {
                driver.prepare(this, scenario)
                driver.run(this, scenario)
                driver.verify(scenario)
            }
        }
    }
}
