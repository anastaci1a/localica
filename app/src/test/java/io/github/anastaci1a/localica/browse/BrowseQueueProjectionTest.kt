package io.github.anastaci1a.localica.browse

import io.github.anastaci1a.localica.library.*
import io.github.anastaci1a.localica.navigation.*
import io.github.anastaci1a.localica.playlist.ManualPlaylist
import io.github.anastaci1a.localica.queue.ListeningQueue
import io.github.anastaci1a.localica.queue.QueueEntry
import io.github.anastaci1a.localica.ui.BrowsePresentationCache
import io.github.anastaci1a.localica.ui.BrowseSourceRevision
import io.github.anastaci1a.localica.ui.PresentedBrowse
import java.lang.ref.WeakReference
import java.util.Locale
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

/** #957 slice B: a song list is a view of its source revision (DESIGN §5 assertions 1-6). */
class BrowseQueueProjectionTest {
    private val threads = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
    private val allocatedBytes = Class.forName("com.sun.management.ThreadMXBean").getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
    private fun <T> allocation(block: () -> T): Pair<T, Long> {
        val id = Thread.currentThread().id
        val before = allocatedBytes.invoke(threads, id) as Long
        val value = block()
        return value to (allocatedBytes.invoke(threads, id) as Long) - before
    }
    private fun collected(ref: WeakReference<*>): Boolean {
        repeat(10) { if (ref.get() == null) return true; System.gc(); Thread.sleep(20) }
        return ref.get() == null
    }
    private fun used(): Long {
        repeat(3) { System.gc(); Thread.sleep(20) }
        return Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
    }

    private fun track(i: Int) = Track("uri:$i", "Song $i", artist = "Artist ${i % 97}", durationMs = 1000L + i,
        fileName = "$i.mp3", relativePath = "$i.mp3", year = 1950 + i % 70, playCount = (i % 5).toLong())
    private fun library(tracks: Int) = LibrarySnapshot(tracks = List(tracks, ::track))
    private fun queue(library: LibrarySnapshot, entries: Int, id: String = "q") = ListeningQueue(id, "Queue",
        entries = List(entries) { QueueEntry("e$it", library.tracks[(it * 7919L % library.tracks.size).toInt()]) })
    private fun detail(queue: ListeningQueue, text: String = "") =
        BrowseRoute(MainTab.Queues, collection = MediaRef(MediaKind.Queue, queue.id), text = text, search = text.isNotEmpty(), key = "detail")
    private fun evaluate(library: LibrarySnapshot, route: BrowseRoute, queues: List<ListeningQueue>,
        playlists: List<ManualPlaylist> = emptyList(), checkActive: () -> Unit = {}) =
        BrowseQueries.evaluate(library, playlists, route, queues = queues, checkActive = checkActive)

    @Test fun queueDetailAndOpeningAreViewsOfTheQueueAtOperatorScale() {
        for (tracks in listOf(13_400, 50_000)) {
            val library = library(tracks)
            val queue = queue(library, 1_000_000)
            evaluate(library, detail(queue), listOf(queue)) // Class loading and lazy library maps.
            val (result, bytes) = allocation { evaluate(library, detail(queue), listOf(queue)) }
            assertTrue("queue detail over $tracks tracks allocated $bytes B", bytes < 4096)
            assertEquals(1_000_000, result.items.size)
            assertEquals(1_000_000, result.rows.size)
            val (opening, openingBytes) = allocation { BrowseQueries.openingQueue(library, queue, detail(queue), "e999999") }
            assertTrue("opening allocated $openingBytes B", openingBytes < 4096)
            assertEquals(result, opening)
            assertEquals("e500000", result.rows[500_000].id)
            assertEquals(queue.entries[500_000].track, result.items[500_000].row!!.track)
        }
    }

    @Test fun searchKeepsSourceOrderOccurrencesAndDuplicatesWithoutRetainingTheSource() {
        val library = library(2_000)
        val queue = queue(library, 20_000)
        val text = "song 1"
        val expected = queue.entries.filter { LibrarySearchIndex.text(it.track).let { text -> "song" in text && "1" in text } }
        val found = evaluate(library, detail(queue, text), listOf(queue))
        assertEquals(expected.map { it.id }, found.rows.map { it.id })
        assertEquals(expected.map { it.id }, found.keys)
        assertEquals(expected.map { it.track.id }, found.items.map { it.ref.id })
        assertTrue("duplicate tracks keep every occurrence", found.rows.size > found.rows.map { it.track.id }.toSet().size)
        assertTrue(evaluate(library, detail(queue, "no such song"), listOf(queue)).items.isEmpty())

        // Reading a match never runs the predicate again; zero matches retain nothing of the source.
        fun rows() = songResult(List(1_000_000) { SongRow("r$it", library.tracks[it % 2_000]) }, SongListView.Songs)
        val source = rows()
        var tests = 0
        val half = source.matching({}) { tests++; it % 2 == 0 }
        assertEquals(1_000_000, tests)
        assertEquals("r999998", half.rows.last().id)
        assertEquals(half.rows.map { it.id }, half.keys)
        assertEquals(1_000_000, tests)
        val (empty, sourceRef) = zeroMatch(::rows)
        assertTrue(empty.items.isEmpty() && empty.keys.isEmpty())
        assertTrue("a zero-match result must not hold its source", collected(sourceRef))
        // Match-all retains 4 B per match (bound 8 B), not a copy of the rows.
        val before = used()
        val all = source.matching({}) { true }
        val retained = used() - before
        assertEquals(1_000_000, all.items.size)
        assertTrue("match-all hit index retained $retained B", retained <= 8L * 1_000_000)
        var checks = 0
        assertThrows(IllegalStateException::class.java) { source.matching({ check(++checks < 3) }) { true } }
        assertEquals(3, checks)
    }

    @Test fun equalityIsTheSourceRevisionWithoutReadingElements() {
        val library = library(13_400)
        val queue = queue(library, 1_000_000)
        val first = evaluate(library, detail(queue), listOf(queue))
        val checkpoint = queue.copy(positionMs = 1, currentEntryId = "e1")
        val second = evaluate(library, detail(checkpoint), listOf(checkpoint))
        assertEquals(first, second)
        val (equal, bytes) = allocation { first == second }
        assertTrue(equal)
        assertTrue("checkpoint equality allocated $bytes B", bytes < 1024)
        val edited = queue.copy(entries = queue.entries.drop(1))
        assertNotEquals(first, evaluate(library, detail(edited), listOf(edited)))
        // A structurally equal but separate list is another revision; contents are never compared.
        val copied = queue.copy(entries = queue.entries.toList())
        assertNotEquals(first, evaluate(library, detail(copied), listOf(copied)))
        // Another library revision projects other tracks, so it is another projection.
        val relabeled = library.copy(tracks = library.tracks.map { it.copy(title = it.title + "!") })
        assertNotEquals(first, evaluate(relabeled, detail(queue), listOf(queue)))

        // Memoised facts belong to an item over one revision; an edit is a new item with its own facts.
        val item = queueItem(queue)
        assertEquals(item, queueItem(checkpoint))
        val shorter = queue.copy(entries = queue.entries.take(10))
        val other = queueItem(shorter)
        assertNotEquals(item, other)
        assertEquals(shorter.entries.sumOf { it.track.durationMs!! }, other.duration())
        assertEquals(queue.entries.sumOf { it.track.durationMs!! }, item.duration())
    }

    @Test fun indexOfKeyKeepsTheLastDuplicateWithoutAllocating() {
        val library = library(13_400)
        val base = queue(library, 1_000_000)
        val queue = base.copy(entries = base.entries.toMutableList().apply { set(999_000, QueueEntry("e5", this[5].track)) })
        val result = evaluate(library, detail(queue), listOf(queue))
        result.indexOfKey("e0")
        val (index, bytes) = allocation { result.indexOfKey("e0") }
        assertEquals(0, index)
        assertTrue("indexOfKey allocated $bytes B", bytes < 1024)
        assertEquals(999_000, result.indexOfKey("e5"))
        assertEquals(-1, result.indexOfKey("missing"))
    }

    @Test fun foldedFactsEqualTheFormerExpressionsAndQueuesTabBuildsNoQueueSizedList() {
        val random = Random(957)
        val pool = List(40) { i -> track(i).copy(id = "t${i % 30}", durationMs = if (i % 7 == 0) null else listOf(1L, 61_000L, Long.MAX_VALUE)[i % 3],
            year = if (i % 4 == 0) null else 1990 + i % 5, playCount = if (i % 6 == 0) 0 else i.toLong(),
            addedAt = if (i % 3 == 0) null else i * 10L, modifiedAt = if (i % 5 == 0) null else -i * 10L,
            albumArtist = if (i % 2 == 0) null else "AA ${i % 3}", composer = if (i % 3 == 0) null else "C ${i % 4}", genre = "G ${i % 2}") }
        repeat(300) { n ->
            val songs = List(random.nextInt(0, 12)) { SongRow("o$n-$it", pool[random.nextInt(pool.size)]) }
            val item = BrowserItem(MediaRef(MediaKind.Album, "$n"), "A$n", null, songs)
            val tracks = songs.map { it.track }
            assertEquals(tracks.mapNotNull { it.addedAt }.minOrNull(), item.addedAt)
            assertEquals(tracks.mapNotNull { it.modifiedAt }.maxOrNull(), item.modifiedAt)
            assertEquals(tracks.distinctBy { it.id }.sumOf { it.playCount }, item.playCount)
            assertEquals(tracks.mapNotNull { it.durationMs }.takeIf { it.isNotEmpty() }?.sum(), item.duration())
            assertEquals(longestListenedMs(tracks), item.longestListenedMs())
            for (info in ExtraInfo.entries.filter { it != ExtraInfo.Inherited && it != ExtraInfo.Automatic }) {
                val expected = when (info) {
                    ExtraInfo.Duration -> tracks.mapNotNull { it.durationMs }.takeIf { it.isNotEmpty() }?.sum()?.let {
                        "${it / 60000}:${(it / 1000 % 60).toString().padStart(2, '0')}${if (tracks.any { t -> t.durationMs == null }) " (partial)" else ""}" }
                    ExtraInfo.Year -> tracks.mapNotNull { it.year }.minOrNull()?.toString()
                    ExtraInfo.LongestListened -> listeningTimeLabel(longestListenedMs(tracks)) +
                        if (tracks.any { it.playCount > 0 && it.durationMs == null }) " (partial)" else ""
                    ExtraInfo.Artist, ExtraInfo.AlbumArtist, ExtraInfo.Composer, ExtraInfo.Genre -> tracks.mapNotNull {
                        when (info) { ExtraInfo.Artist -> it.artist; ExtraInfo.AlbumArtist -> it.albumArtist ?: it.artist
                            ExtraInfo.Composer -> it.composer; else -> it.genre }
                    }.distinct().sorted().takeIf { it.isNotEmpty() }?.joinToString(", ")
                    ExtraInfo.MostPlayed -> tracks.distinctBy { it.id }.sumOf { it.playCount }.let { "$it ${if (it == 1L) "play" else "plays"}" }
                    ExtraInfo.Added -> tracks.mapNotNull { it.addedAt }.minOrNull()?.let { java.text.DateFormat.getDateInstance().format(java.util.Date(it)) }
                    ExtraInfo.Modified -> tracks.mapNotNull { it.modifiedAt }.maxOrNull()?.let { java.text.DateFormat.getDateInstance().format(java.util.Date(it)) }
                    ExtraInfo.Count -> songCountLabel(tracks.size)
                    else -> null
                }
                assertEquals("$info of $n", expected, item.information(BrowseStyle(extraInfo = info)))
            }
        }
        val library = library(13_400)
        val queue = queue(library, 1_000_000)
        queueItem(queue).information(BrowseStyle(extraInfo = ExtraInfo.Count)) // Class loading.
        val (item, bytes) = allocation { queueItem(queue) }
        assertTrue("a 1M-entry collection item allocated $bytes B", bytes < 4096)
        assertEquals(1_000_000, item.songs.size)
        assertEquals(queue.entries.map { it.track }.distinctBy { it.id }.sumOf { it.playCount }, item.playCount)
    }

    @Test fun queueRoutesAreNeverCachedSoNoQueueRevisionIsRetained() {
        val library = library(500)
        val queue = queue(library, 50)
        // Round 3 cached this empty detail (no queues) and served it once the queue appeared.
        assertTrue(evaluate(library, detail(queue), emptyList()).items.isEmpty())
        assertEquals(50, evaluate(library, detail(queue), listOf(queue)).items.size)
        val old = (1..5).map { revision(library, queue, it) }
        evaluate(library, BrowseRoute(MainTab.Songs), emptyList())
        old.forEach { assertTrue("an older queue revision is still referenced", collected(it)) }
    }
    private fun revision(library: LibrarySnapshot, queue: ListeningQueue, drop: Int): WeakReference<*> {
        val next = queue.copy(entries = queue.entries.drop(drop))
        evaluate(library, detail(next), listOf(next))
        return WeakReference(next.entries)
    }

    @Test fun anOlderGenerationFinishingLateNeitherInsertsNorEvicts() {
        val library = library(500)
        val newer = listOf(ManualPlaylist("p2", "Newer"))
        val (current, older) = olderFinishingLate(library, newer)
        assertNotNull(current)
        assertSame(current, evaluate(library, BrowseRoute(MainTab.Songs), emptyList(), newer))
        assertTrue("the superseded generation must be collectable", collected(older))
    }
    /** Starts on P1 and, inside its catalog preparation, completes a whole evaluation on P2. */
    private fun olderFinishingLate(library: LibrarySnapshot, newer: List<ManualPlaylist>): Pair<BrowseResult?, WeakReference<*>> {
        val playlists = listOf(ManualPlaylist("p1", "Older"))
        var current: BrowseResult? = null
        var calls = 0
        evaluate(library, BrowseRoute(MainTab.Songs), emptyList(), playlists) {
            if (++calls == 2) current = evaluate(library, BrowseRoute(MainTab.Songs), emptyList(), newer)
        }
        assertTrue(calls > 2)
        return current to WeakReference(playlists)
    }

    @Test fun mainConfinedPutsEvictEveryOtherSourceRevision() {
        val cache = BrowsePresentationCache()
        val library = library(200)
        firstRevisionEvictedBySecond(cache, library).forEach { assertTrue("an evicted revision is still referenced", collected(it)) }
    }
    private fun put(cache: BrowsePresentationCache, library: LibrarySnapshot, queues: List<ListeningQueue>, route: BrowseRoute) =
        cache.put(library, emptyList(), queues, LibraryFilters(), PresentedBrowse(route, BrowseStyle(),
            evaluate(library, route, queues), BrowseSourceRevision(library, emptyList(), queues, LibraryFilters())))
    private fun get(cache: BrowsePresentationCache, library: LibrarySnapshot, queues: List<ListeningQueue>, route: BrowseRoute) =
        cache.get(library, emptyList(), queues, LibraryFilters(), route, BrowseStyle())
    /** Puts run in order on Main, so the latest put is the current revision, which keeps all its routes. */
    private fun firstRevisionEvictedBySecond(cache: BrowsePresentationCache, library: LibrarySnapshot): List<WeakReference<*>> {
        val first = queue(library, 100)
        val tab = detail(first).copy(key = "second-route")
        val firstQueues = listOf(first)
        put(cache, library, firstQueues, detail(first)); put(cache, library, firstQueues, tab)
        assertNotNull(get(cache, library, firstQueues, detail(first)))
        assertNotNull(get(cache, library, firstQueues, tab))
        val secondQueues = listOf(first.copy(entries = first.entries.drop(1)))
        put(cache, library, secondQueues, detail(first))
        assertNotNull(get(cache, library, secondQueues, detail(first)))
        assertNull(get(cache, library, firstQueues, detail(first)))
        assertNull(get(cache, library, firstQueues, tab))
        return listOf(WeakReference(firstQueues), WeakReference(first.entries))
    }

    private fun zeroMatch(rows: () -> BrowseResult): Pair<BrowseResult, WeakReference<*>> {
        val source = rows()
        return source.matching({}) { false } to WeakReference(source.rows)
    }
}

private fun occurrence(entry: QueueEntry) = SongRow(entry.id, entry.track)
/** A collection item over a queue revision, as the Queue catalog builds it. */
private fun queueItem(queue: ListeningQueue) = BrowserItem(MediaRef(MediaKind.Queue, queue.id), queue.name, null, queue.entries.mapView(::occurrence))
