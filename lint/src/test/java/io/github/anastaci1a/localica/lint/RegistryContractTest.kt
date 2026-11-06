package io.github.anastaci1a.localica.lint

import com.android.tools.lint.checks.infrastructure.TestFiles.kotlin
import com.android.tools.lint.checks.infrastructure.TestLintTask
import com.android.tools.lint.checks.infrastructure.TestMode
import com.android.tools.lint.detector.api.CURRENT_API
import com.android.tools.lint.detector.api.Severity
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.jar.JarFile
import java.util.concurrent.Executors

class RegistryContractTest {
    @org.junit.Before fun initializeLint() { com.android.tools.lint.client.api.LintClient.clientName = "unit-tests" }
    @Test fun exactlyThirteenErrorsAtTheTestedApi() {
        val registry = LocalicaIssueRegistry()
        assertEquals(UiRuleId.entries.map { it.name }.toSet(), registry.issues.map { it.id }.toSet())
        assertEquals(13, registry.issues.size)
        assertTrue(registry.issues.all { it.defaultSeverity == Severity.ERROR })
        assertFalse(registry.issues.any { it.id == "MotionThreshold" })
        assertEquals(CURRENT_API, registry.api)
        assertEquals(CURRENT_API, registry.minApi)
    }

    @Test fun builtJarServiceIsDiscoveredByAConsumingProject() {
        val jar = File(System.getProperty("localica.registry.jar"))
        JarFile(jar).use { archive ->
            val service = archive.getJarEntry("META-INF/services/com.android.tools.lint.client.api.IssueRegistry")
            assertNotNull(service)
            assertEquals(LocalicaIssueRegistry::class.java.name, archive.getInputStream(service).reader().readText().trim())
            assertFalse(archive.entries().asSequence().any { it.name.startsWith("com/android/") })
        }
        // No direct Issue or Detector is injected here: lint must discover the service in the jar.
        TestLintTask.lint().customRules(jar).allowDelayedIssueRegistration().issueIds("RawUiMotion")
            .files(
                kotlin("src/main/java/Api.kt", "package androidx.compose.animation.core\nfun tween() = 1"),
                kotlin("src/main/java/Screen.kt", "package io.github.anastaci1a.localica.ui.components\nfun bad() = androidx.compose.animation.core.tween()"),
            ).allowMissingSdk().testModes(TestMode.DEFAULT).run().expectMatches("(?s).*RawUiMotion.*UiLayerDependency.*2 errors.*")
    }

    @Test fun parallelRegistriesHaveTheSameImmutableInventory() {
        val pool = Executors.newFixedThreadPool(4)
        try {
            val expected = LocalicaIssueRegistry().issues.map { it.id to it.defaultSeverity }
            val jobs = (1..16).map { pool.submit<List<Pair<String, Severity>>> { LocalicaIssueRegistry().issues.map { it.id to it.defaultSeverity } } }
            jobs.forEach { assertEquals(expected, it.get()) }
        } finally { pool.shutdownNow() }
    }

    @Test fun expiryUsesFrozenStepsAndNumericReleaseOrdering() {
        val entry = EscapeEntry(UiRuleId.IndependentUiClock, "adapter.ready:12", "Readiness", "https://github.com/anastaci1a/localica/issues/600", "platform", "lead", "U13.03a")
        assertTrue(EscapeManifest.isLive(entry, "U13.01,U13.03a", "U13.01", null))
        assertFalse(EscapeManifest.isLive(entry, "U13.01,U13.03a", "U13.03a", null))
        assertFalse(EscapeManifest.isLive(entry, "U13.01", "", null))
        assertFalse(EscapeManifest.isLive(entry, null, null, null))
        val version = entry.copy(expires = "v0.34.0")
        assertTrue(EscapeManifest.isLive(version, null, null, "v0.33.9"))
        assertFalse(EscapeManifest.isLive(version, null, null, "v0.34.0"))
        assertFalse(EscapeManifest.isLive(version, null, null, "v0.100.0"))
        assertFalse(EscapeManifest.isLive(version, null, null, null))
    }

    @Test fun escapeManifestRejectsMalformedDuplicateAndUnknownEntries() {
        val good = """
            - rule: IndependentUiClock
              symbol: io.github.anastaci1a.localica.ui.engine.platform.Adapter.ready:12
              reason: Native readiness adapter
              issue: https://github.com/anastaci1a/localica/issues/600
              owner: platform
              reviewer: lead
              expires: U13.03a
        """.trimIndent()
        assertEquals(1, EscapeManifest.parse(good)?.size)
        assertEquals(emptyList<EscapeEntry>(), EscapeManifest.parse("[]"))
        assertNull(EscapeManifest.parse(good + "\n" + good))
        assertNull(EscapeManifest.parse(good.replace("reviewer: lead", "reviewer: self")))
        assertNull(EscapeManifest.parse(good.replace("IndependentUiClock", "MotionThreshold")))
        assertNull(EscapeManifest.parse(good.replace("Adapter.ready:12", "*")))
        assertNull(EscapeManifest.parse(good + "\n  unknown: value"))
        assertNull(EscapeManifest.parse(good + "\n  reviewer: lead"))
        assertNull(EscapeManifest.parse("!!java.net.URL [https://example.com]"))
        assertNull(EscapeManifest.parse("- invalid: ["))
    }
}
