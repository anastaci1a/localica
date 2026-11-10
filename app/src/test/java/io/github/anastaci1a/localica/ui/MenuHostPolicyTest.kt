package io.github.anastaci1a.localica.ui

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/**
 * #757: one finite, reviewed file lists who may opt into the menu internals. Any other file that opts in, a line
 * past its expiry step, or an entry that names no file fails here, so the list cannot grow without this file
 * changing in the same commit.
 */
class MenuHostPolicyTest {
    private class Entry(val kind: String, val path: String, val expires: String)
    private val steps = listOf("M0", "M1", "M2", "M3", "M4", "M5", "M6", "M7")
    private val optIn = Regex("""OptIn\s*\((?:[^)]*,\s*)?(?:[\w.]+\.)?MenuInternal::class""")

    private fun parse(text: String): Pair<String, List<Entry>> {
        val step = Regex("""^# current-step: (\w+)$""", RegexOption.MULTILINE).find(text)?.groupValues?.get(1) ?: error("menu-host-policy.txt names no current step")
        require(step in steps) { "unknown step $step" }
        val entries = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.map { line ->
            val fields = line.split("|").map { it.trim() }
            require(fields.size == 4 && fields.none { it.isEmpty() }) { "unparsable policy line: $line" }
            val (kind, path, expires) = fields
            require(kind in setOf("binder", "hosted", "legacy")) { "unknown kind in: $line" }
            require(path.endsWith(".kt") && '*' !in path) { "entries are exact files: $line" }
            require(if (kind == "legacy") expires in steps else expires == "never" || expires in steps) { "bad expiry in: $line" }
            Entry(kind, path, expires)
        }
        require(entries.map { it.path }.toSet().size == entries.size) { "a file is listed twice" }
        return step to entries
    }

    @Test fun onlyListedUnexpiredFilesOptIntoMenuInternals() {
        val (step, entries) = parse(File("config/menu-host-policy.txt").readText())
        for (entry in entries) {
            assertTrue("${entry.path} does not exist", File("..", entry.path).isFile)
            assertTrue("${entry.path} expired at ${entry.expires}; the policy is at $step", entry.expires == "never" || steps.indexOf(entry.expires) > steps.indexOf(step))
        }
        val listed = entries.map { it.path }.toSet()
        val users = File("src/main/java").walkTopDown().filter { it.extension == "kt" && optIn.containsMatchIn(it.readText()) }
            .map { "app/" + it.path.replace(File.separatorChar, '/') }.toList()
        assertEquals("files that opt into MenuInternal without a policy line", emptyList<String>(), users.filterNot { it in listed })
    }

    @Test fun theParserRejectsWhatThePolicyForbids() {
        val head = "# current-step: M2\n"
        assertEquals(2, parse(head + "binder | app/a/B.kt | never | binder\nlegacy | app/a/C.kt | M3 | caller\n").second.size)
        for (bad in listOf("binder | app/a/*.kt | never | wildcard", "legacy | app/a/C.kt | never | no expiry", "host | app/a/C.kt | never | kind",
            "binder | app/a/dir | never | directory", "binder | app/a/B.kt | never", "binder | app/a/B.kt | never | one\nbinder | app/a/B.kt | never | two"))
            assertThrows(bad, IllegalArgumentException::class.java) { parse(head + bad + "\n") }
        assertThrows(IllegalStateException::class.java) { parse("binder | app/a/B.kt | never | no step\n") }
    }

    @Test fun theMarkerIsAnErrorLevelOptIn() {
        val source = File("src/main/java/io/github/anastaci1a/localica/ui/MenuInternal.kt").readText()
        assertTrue("@RequiresOptIn(level = RequiresOptIn.Level.ERROR" in source)
    }
}
