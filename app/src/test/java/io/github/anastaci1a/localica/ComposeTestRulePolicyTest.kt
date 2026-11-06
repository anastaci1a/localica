package io.github.anastaci1a.localica

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class ComposeTestRulePolicyTest {
    @Test fun instrumentationRulesUseTheQueuedDispatcherWrapper() {
        val sources = File("src/androidTest")
        assertTrue("Instrumentation sources must be present", sources.isDirectory)
        val wrapper = "java/io/github/anastaci1a/localica/TestComposeRules.kt"
        val rawImport = Regex(
            """(?m)^\s*import\s+androidx\.compose\.ui\.test\.junit4\.(?:createComposeRule|createAndroidComposeRule|\*)(?=\s|;|$)"""
        )
        val violations = sources.walkTopDown()
            .filter { it.isFile && it.extension in setOf("kt", "java") }
            .filter { it.relativeTo(sources).invariantSeparatorsPath != wrapper }
            .filter { rawImport.containsMatchIn(it.readText()) }
            .map { it.relativeTo(sources).invariantSeparatorsPath }
            .sorted().toList()
        assertEquals("Use localicaComposeRule/localicaAndroidComposeRule; raw rules can resume composition on a worker", emptyList<String>(), violations)
    }
}
