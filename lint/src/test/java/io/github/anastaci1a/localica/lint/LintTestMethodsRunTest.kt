package io.github.anastaci1a.localica.lint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier

/** #854 round 4 (F8-1): JUnit 3 (LintDetectorTest) runs only public void test* methods and skips the rest without a word, so an
 * expression-bodied `fun testX() = lint()...expectMatches(...)` (which returns TestLintResult) never ran. Every test* method of every
 * lint test class must be one that runs. */
class LintTestMethodsRunTest {
    @Test fun everyJUnit3TestMethodRuns() {
        val root = File(javaClass.protectionDomain.codeSource.location.toURI())
        val classes = root.walk().filter { it.isFile && it.extension == "class" }
            .map { it.relativeTo(root).invariantSeparatorsPath.removeSuffix(".class").replace('/', '.') }
            .map { Class.forName(it, false, javaClass.classLoader) }
            .filter { junit.framework.TestCase::class.java.isAssignableFrom(it) && !Modifier.isAbstract(it.modifiers) }.toList()
        assertTrue("no JUnit 3 lint test classes found under $root", classes.any { it.simpleName == "PopupGateRedTeamTest" })
        val skipped = classes.flatMap { klass ->
            klass.declaredMethods.filter { it.name.startsWith("test") && it.parameterCount == 0 &&
                (it.returnType != Void.TYPE || !Modifier.isPublic(it.modifiers) || Modifier.isStatic(it.modifiers)) }
                .map { klass.simpleName + "." + it.name + ": " + it.returnType.simpleName }
        }.sorted()
        assertEquals("test methods JUnit 3 never runs", emptyList<String>(), skipped)
    }
}
