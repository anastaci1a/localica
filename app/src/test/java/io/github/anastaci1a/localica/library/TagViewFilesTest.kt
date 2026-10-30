package io.github.anastaci1a.localica.library

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TagViewFilesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun concurrentSweepDeletesLeftoversButKeepsAnActivePathReadable() {
        val directory = temporary.newFolder()
        val leftover = File(directory, "metadata-old.flac").apply { writeText("old") }
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val active = AtomicReference<File>()
        val failure = AtomicReference<Throwable>()
        val worker = Thread {
            try {
                TagViewFiles.use(directory, "flac") { file ->
                    file.writeText("current")
                    active.set(file); started.countDown()
                    check(release.await(10, TimeUnit.SECONDS)) { "Reader was not released" }
                    assertEquals("current", file.readText())
                }
            } catch (problem: Throwable) { failure.set(problem) }
        }.apply { isDaemon = true; start() }
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            TagViewFiles.sweep(directory)
            assertFalse(leftover.exists())
            assertTrue(active.get().exists())
            assertEquals("current", active.get().readText())
        } finally { release.countDown(); worker.join(10_000) }
        assertFalse("Reader must finish", worker.isAlive)
        failure.get()?.let { throw it }
        assertFalse(active.get().exists())
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test fun nestedReadersHaveIndependentFilesAndTheOuterLeaseSurvivesInnerCleanup() {
        val directory = temporary.newFolder()
        TagViewFiles.use(directory, "wav") { outer ->
            outer.writeText("outer")
            TagViewFiles.use(directory, "wav") { inner ->
                inner.writeText("inner")
                assertNotEquals(outer, inner)
                TagViewFiles.sweep(directory)
                assertEquals("outer", outer.readText())
                assertEquals("inner", inner.readText())
            }
            TagViewFiles.sweep(directory)
            assertEquals(listOf(outer), directory.listFiles().orEmpty().toList())
            assertEquals("outer", outer.readText())
        }
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test fun failedReaderReleasesItsFileAndDoesNotProtectLaterLeftovers() {
        val directory = temporary.newFolder()
        val problem = IllegalStateException("reader failure")
        var path: File? = null
        try {
            TagViewFiles.use(directory, "m4a") { file -> path = file; throw problem }
            fail("Reader failure must propagate")
        } catch (caught: IllegalStateException) { assertSame(problem, caught) }
        val previous = requireNotNull(path)
        assertFalse(previous.exists())
        previous.writeText("abandoned replacement")
        TagViewFiles.sweep(directory)
        assertFalse(previous.exists())
    }

    @Test fun sweepDoesNotDescendIntoDirectoriesAndUseCreatesTheRequestedDirectory() {
        val directory = File(temporary.root, "new-mirrors")
        TagViewFiles.sweep(directory)
        assertFalse(directory.exists())
        TagViewFiles.use(directory, "ogg") { file -> assertTrue(file.exists()); assertEquals(directory, file.parentFile) }
        val child = File(directory, "retained").apply { mkdir() }
        val nested = File(child, "owned-elsewhere").apply { writeText("keep") }
        TagViewFiles.sweep(directory)
        assertEquals("keep", nested.readText())
    }
}
