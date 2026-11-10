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
    @Test fun exactlyFourteenUiAndEightMenuErrorsAtTheTestedApi() {
        val registry = LocalicaIssueRegistry()
        // Menu rules (#757) are appended after the UI rules; the order is part of the registry export.
        assertEquals(UiRuleId.entries.map { it.name } + MenuRuleId.entries.map { it.name }, registry.issues.map { it.id })
        assertEquals(14, UiRuleId.entries.size)
        assertEquals(22, registry.issues.size)
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

    /** The comparison tooling takes its rule list from this export of the built jar, never from a hand-written array (#757). */
    @Test fun builtJarExportsItsRegistry() {
        val jar = File(System.getProperty("localica.registry.jar"))
        // Registry classes come from the jar first; the lint API comes from the test classpath.
        val jarFirst = object : java.net.URLClassLoader(arrayOf(jar.toURI().toURL()), javaClass.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> =
                if (name.startsWith("io.github.anastaci1a.localica.lint.")) findLoadedClass(name) ?: findClass(name) else super.loadClass(name, resolve)
        }
        // Rule enums are read from the jar without creating a second set of Issue objects in this JVM.
        val ids = jarFirst.use { loader -> listOf(UiRuleId::class.java, MenuRuleId::class.java).flatMap { type ->
            val inJar = Class.forName(type.name, false, loader)
            assertNotSame(type, inJar)
            inJar.enumConstants.map { (it as Enum<*>).name }
        } }
        assertEquals(LocalicaIssueRegistry().issues.map { it.id }, ids)
        File(jar.parentFile, "registry-export.json").writeText(ids.joinToString(",", "{\"vendor\":\"Localica\",\"issues\":[", "]}\n") { "\"$it\"" })
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
