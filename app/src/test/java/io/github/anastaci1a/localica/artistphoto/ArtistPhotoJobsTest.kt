package io.github.anastaci1a.localica.artistphoto

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** #779: per-tile queue lookups read memory; the directory is parsed once per generation marker. */
class ArtistPhotoJobsTest {
    @get:Rule val folder = TemporaryFolder()
    private fun request(n: Int) = ArtistPhotoRequest("Artist:$n", "Artist $n", setOf("Album $n"))

    @Test fun lookupsAfterTheFirstLoadDoNotReadTheDirectory() {
        val directory = folder.newFolder("jobs")
        val jobs = ArtistPhotoJobs(directory)
        repeat(50) { jobs.enqueue(request(it), refresh = false) }
        assertEquals(50, jobs.all().size)
        // Remove the job files behind the index but keep the generation marker: lookups still answer from memory.
        directory.listFiles().orEmpty().filter { it.extension == "json" }.forEach { it.delete() }
        assertEquals("pending", jobs.find(request(7).artistKey)?.state)
        assertEquals(50, jobs.pendingCount())
        assertNotNull(jobs.firstPending())
    }

    @Test fun anotherInstanceSeesEveryWriteWithoutTimestampsIncludingBackToBackWrites() {
        val directory = folder.newFolder("jobs")
        val writer = ArtistPhotoJobs(directory)
        val reader = ArtistPhotoJobs(directory)
        assertNull(reader.find(request(1).artistKey))
        val job = writer.enqueue(request(1), refresh = false)
        assertEquals(job, reader.find(request(1).artistKey))
        // Two writes within the same instant: the second must still be visible to the reader.
        val stamp = directory.lastModified()
        val second = writer.enqueue(request(2), refresh = false)
        val third = writer.enqueue(request(3), refresh = false)
        directory.setLastModified(stamp)
        assertEquals(second, reader.find(request(2).artistKey)); assertEquals(third, reader.find(request(3).artistKey))
        writer.complete(job, succeeded = false)
        assertEquals("failed", reader.find(request(1).artistKey)?.state)
        reader.retryFailures()
        assertEquals("pending", writer.find(request(1).artistKey)?.state)
        listOf(1, 2, 3).forEach { n -> writer.complete(requireNotNull(writer.find(request(n).artistKey)), succeeded = true) }
        assertTrue(reader.all().isEmpty())
        assertTrue(directory.listFiles().orEmpty().none { it.extension == "json" })
    }

    @Test fun aDirectoryWrittenBeforeTheMarkerExistedLoadsOnce() {
        val directory = folder.newFolder("jobs")
        ArtistPhotoJobs(directory).enqueue(request(1), refresh = false)
        File(directory, "generation").delete()
        val jobs = ArtistPhotoJobs(directory)
        assertEquals("pending", jobs.find(request(1).artistKey)?.state)
        jobs.enqueue(request(2), refresh = false)
        assertTrue(File(directory, "generation").isFile)
        assertEquals(2, ArtistPhotoJobs(directory).all().size)
    }

    @Test fun anOlderGenerationCannotCompleteANewerJob() {
        val jobs = ArtistPhotoJobs(folder.newFolder("jobs"))
        val old = jobs.enqueue(request(1), refresh = false)
        val newer = jobs.enqueue(request(1), refresh = true)
        jobs.complete(old, succeeded = true)
        assertEquals(newer, jobs.find(request(1).artistKey))
    }
}
