package io.github.anastaci1a.localica.artistphoto

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** #779: per-tile queue lookups read memory; the directory is parsed once and after another writer's change. */
class ArtistPhotoJobsTest {
    @get:Rule val folder = TemporaryFolder()
    private fun request(n: Int) = ArtistPhotoRequest("Artist:$n", "Artist $n", setOf("Album $n"))

    @Test fun lookupsAfterTheFirstLoadDoNotReadTheDirectory() {
        val directory = folder.newFolder("jobs")
        val jobs = ArtistPhotoJobs(directory)
        repeat(50) { jobs.enqueue(request(it), refresh = false) }
        assertEquals(50, jobs.all().size)
        // Remove the files behind the index: an in-memory index keeps answering until the directory stamp changes.
        val stamp = directory.lastModified()
        directory.listFiles().orEmpty().forEach { it.delete() }
        directory.setLastModified(stamp)
        assertEquals("pending", jobs.find(request(7).artistKey)?.state)
        assertEquals(50, jobs.pendingCount())
    }

    @Test fun anotherInstanceSeesEnqueuesCompletionsAndRetries() {
        val directory = folder.newFolder("jobs")
        val writer = ArtistPhotoJobs(directory)
        val reader = ArtistPhotoJobs(directory)
        assertNull(reader.find(request(1).artistKey))
        val job = writer.enqueue(request(1), refresh = false)
        directory.setLastModified(directory.lastModified() + 2000)
        assertEquals(job, reader.find(request(1).artistKey))
        writer.complete(job, succeeded = false)
        directory.setLastModified(directory.lastModified() + 2000)
        assertEquals("failed", reader.find(request(1).artistKey)?.state)
        reader.retryFailures()
        assertEquals("pending", writer.firstPending()?.state)
        writer.complete(requireNotNull(writer.firstPending()), succeeded = true)
        directory.setLastModified(directory.lastModified() + 2000)
        assertTrue(reader.all().isEmpty())
        assertTrue(directory.listFiles().orEmpty().none { it.extension == "json" })
    }

    @Test fun anOlderGenerationCannotCompleteANewerJob() {
        val jobs = ArtistPhotoJobs(folder.newFolder("jobs"))
        val old = jobs.enqueue(request(1), refresh = false)
        val newer = jobs.enqueue(request(1), refresh = true)
        jobs.complete(old, succeeded = true)
        assertEquals(newer, jobs.find(request(1).artistKey))
    }
}
