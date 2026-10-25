package io.github.anastaci1a.localica.playback

import org.junit.Assert.*
import org.junit.Test

class BoundaryDeclickSessionTest {
    private val mono = BoundaryDeclick.Pcm16Format(48_000, 1)
    private fun pcm(level: Int, frames: Int = 64, channels: Int = 1) =
        ShortArray(frames * channels) { level.toShort() }
    private fun begin(session: BoundaryDeclickSession, id: String, natural: Boolean = true,
        format: BoundaryDeclick.Pcm16Format = mono) = session.beginStream(id, format, natural)
    private fun completed(session: BoundaryDeclickSession, id: String = "a", level: Int = 1000,
        format: BoundaryDeclick.Pcm16Format = mono) {
        begin(session, id, format = format)
        assertArrayEquals(pcm(level, channels = format.channels),
            session.process(pcm(level, channels = format.channels), true))
        session.endStream()
    }
    private fun expected(left: ShortArray, right: ShortArray, format: BoundaryDeclick.Pcm16Format = mono) =
        BoundaryDeclick.plan(left, right, format, format, true).also { assertTrue(it.applied) }.output

    @Test fun naturalTwoAndThreeStreamJoinsUseTheKernelOnlyAtTheHead() {
        val session = BoundaryDeclickSession()
        completed(session)
        begin(session, "b")
        val b = pcm(1200)
        val repaired = session.process(b, true)
        assertArrayEquals(expected(pcm(1000, 8), b), repaired)
        assertEquals(1001.toShort(), repaired[0])
        assertEquals(1200.toShort(), repaired[24])
        val interior = pcm(1400)
        assertArrayEquals(interior, session.process(interior, true))
        session.endStream()
        begin(session, "c")
        val c = pcm(1600)
        assertArrayEquals(expected(pcm(1400, 8), c), session.process(c, true))
    }

    @Test fun disabledFirstHeadConsumesJoinAndMidstreamEnableCannotRepair() {
        val session = BoundaryDeclickSession()
        completed(session)
        begin(session, "b")
        val head = pcm(1200)
        assertArrayEquals(head, session.process(head))
        assertArrayEquals(pcm(1400), session.process(pcm(1400), true))
        assertArrayEquals(pcm(1600), session.process(pcm(1600), false))
        assertArrayEquals(pcm(1800), session.process(pcm(1800), true))
    }

    @Test fun continuousAndSilentBoundariesAreExactCopies() {
        for (level in listOf(0, 1000)) {
            val session = BoundaryDeclickSession()
            completed(session, level = level)
            begin(session, "b")
            val head = pcm(level)
            val result = session.process(head, true)
            assertArrayEquals(head, result)
            assertNotSame(head, result)
        }
    }

    @Test fun emptyChunksPreservePendingJoinButShortHeadConsumesItWithoutBuffering() {
        val session = BoundaryDeclickSession()
        completed(session)
        begin(session, "b")
        repeat(3) { assertEquals(0, session.process(shortArrayOf(), it % 2 == 0).size) }
        val head = pcm(1200)
        assertArrayEquals(expected(pcm(1000, 8), head), session.process(head, true))
        session.endStream()
        begin(session, "c")
        assertArrayEquals(pcm(1400, 1), session.process(pcm(1400, 1), true))
        assertArrayEquals(pcm(1400), session.process(pcm(1400), true))
    }

    @Test fun tailAccumulatesSmallAlignedChunksAndTracksTheLastEightFrames() {
        val session = BoundaryDeclickSession()
        begin(session, "a")
        session.process(pcm(700))
        repeat(8) { assertArrayEquals(pcm(1000, 1), session.process(pcm(1000, 1))) }
        session.endStream()
        begin(session, "b")
        assertArrayEquals(expected(pcm(1000, 8), pcm(1200)), session.process(pcm(1200), true))
    }

    @Test fun shortOrEmptyPreviousStreamsCannotReuseAnOlderTail() {
        for (frames in 0..7) {
            val session = BoundaryDeclickSession()
            completed(session)
            begin(session, "b")
            session.process(pcm(1200, frames), true)
            session.endStream()
            begin(session, "c")
            assertArrayEquals(pcm(1400), session.process(pcm(1400), true))
        }
    }

    @Test fun unevenStereoChunksRetainChronologicalFramesWithoutMixingChannels() {
        val stereo = BoundaryDeclick.Pcm16Format(48_000, 2)
        val session = BoundaryDeclickSession()
        val left = ShortArray(26) { sample ->
            val frame = sample / 2
            (if (sample % 2 == 0) 1000 + frame else -2000 - frame).toShort()
        }
        begin(session, "a", format = stereo)
        var offset = 0
        for (frames in listOf(1, 3, 2, 7)) {
            val chunk = left.copyOfRange(offset, offset + frames * 2)
            assertArrayEquals(chunk, session.process(chunk))
            offset += frames * 2
        }
        session.endStream("a")
        begin(session, "b", format = stereo)
        val right = ShortArray(128) { sample ->
            val frame = sample / 2
            (if (sample % 2 == 0) 1213 + frame else -2213 - frame).toShort()
        }
        val actual = session.process(right, true)
        assertArrayEquals(expected(left.copyOfRange(10, 26), right, stereo), actual)
        // One slope unit plus the rounded first interpolation correction, on distinct channels.
        assertEquals(1014.toShort(), actual[0])
        assertEquals((-2014).toShort(), actual[1])
    }

    @Test fun missingCompletionOrNonnaturalBeginDoesNotArm() {
        for (finish in listOf(false, true)) {
            val session = BoundaryDeclickSession()
            begin(session, "a")
            session.process(pcm(1000))
            if (finish) session.endStream()
            begin(session, "b", natural = !finish)
            assertArrayEquals(pcm(1200), session.process(pcm(1200), true))
        }
    }

    @Test fun repeatedBeginBeforeAndAfterDataDoesNotLoseOrRearmJoin() {
        val session = BoundaryDeclickSession()
        completed(session)
        begin(session, "b")
        begin(session, "b")
        assertArrayEquals(expected(pcm(1000, 8), pcm(1200)), session.process(pcm(1200), true))
        repeat(3) { begin(session, "b") }
        assertArrayEquals(pcm(1400), session.process(pcm(1400), true))
        session.endStream()
        begin(session, "b")
        assertArrayEquals(pcm(1600), session.process(pcm(1600), true))
    }

    @Test fun formatRateAndChannelChangesCannotRepairOrRevivePendingJoin() {
        for (format in listOf(BoundaryDeclick.Pcm16Format(44_100, 1),
            BoundaryDeclick.Pcm16Format(48_000, 2), BoundaryDeclick.Pcm16Format(0, 1),
            BoundaryDeclick.Pcm16Format(48_000, 0), BoundaryDeclick.Pcm16Format(48_000, 9))) {
            val session = BoundaryDeclickSession()
            completed(session)
            begin(session, "b", format = format)
            assertArrayEquals(pcm(1200), session.process(pcm(1200), true))
        }
        val session = BoundaryDeclickSession()
        completed(session)
        begin(session, "b")
        begin(session, "b", format = BoundaryDeclick.Pcm16Format(44_100, 1))
        begin(session, "b")
        assertArrayEquals(pcm(1200), session.process(pcm(1200), true))
    }

    @Test fun withdrawingContinuationOnRepeatedBeginDisarmsAndCannotBeReversed() {
        val session = BoundaryDeclickSession()
        completed(session)
        begin(session, "b")
        begin(session, "b", natural = false)
        begin(session, "b", natural = true)
        assertArrayEquals(pcm(1200), session.process(pcm(1200), true))
    }

    @Test fun resetBeforeOrAfterCompletionDropsAllOwnershipAndIsIdempotent() {
        for (finish in listOf(false, true)) {
            val session = BoundaryDeclickSession()
            begin(session, "a")
            session.process(pcm(1000))
            if (finish) session.endStream()
            repeat(2) { session.reset() }
            session.endStream()
            begin(session, "b")
            assertArrayEquals(pcm(1200), session.process(pcm(1200), true))
        }
        val session = BoundaryDeclickSession()
        completed(session)
        begin(session, "b")
        session.reset() // Seek while a boundary is armed.
        begin(session, "b")
        assertArrayEquals(pcm(1200), session.process(pcm(1200), true))
    }

    @Test fun repeatedEndIsSafeButProcessAfterEndInvalidatesCompletion() {
        val session = BoundaryDeclickSession()
        session.endStream()
        completed(session)
        repeat(2) { session.endStream() }
        begin(session, "b")
        assertArrayEquals(expected(pcm(1000, 8), pcm(1200)), session.process(pcm(1200), true))
        for (late in listOf(shortArrayOf(), pcm(1500))) {
            val reordered = BoundaryDeclickSession()
            completed(reordered)
            assertArrayEquals(late, reordered.process(late, true))
            begin(reordered, "b")
            assertArrayEquals(pcm(1200), reordered.process(pcm(1200), true))
        }
    }

    @Test fun abandonedPendingStreamCannotPassAnOlderCompletionToAnotherIdentity() {
        val session = BoundaryDeclickSession()
        completed(session)
        begin(session, "b")
        begin(session, "c")
        assertArrayEquals(pcm(1200), session.process(pcm(1200), true))
    }

    @Test fun identityBoundEndRejectsStaleOwnersWithoutCompletingTheNewStream() {
        val session = BoundaryDeclickSession()
        begin(session, "a")
        session.process(pcm(1000))
        session.endStream("a")
        session.endStream("a") // Duplicate completion remains harmless.
        begin(session, "b")
        assertArrayEquals(expected(pcm(1000, 8), pcm(1200)), session.process(pcm(1200), true))
        session.endStream("a") // Delayed old completion must not end B.
        assertArrayEquals(pcm(1400), session.process(pcm(1400), true))
        session.endStream("b")
        begin(session, "c")
        assertArrayEquals(expected(pcm(1400, 8), pcm(1600)), session.process(pcm(1600), true))
        session.endStream("c")
        session.endStream("unknown") // Also invalidates completed ownership.
        begin(session, "d")
        assertArrayEquals(pcm(1800), session.process(pcm(1800), true))
    }

    @Test fun staleCompletionDisarmsPendingJoinButCannotCompleteCurrentOwnership() {
        val session = BoundaryDeclickSession()
        completed(session)
        begin(session, "b")
        session.endStream("a")
        assertArrayEquals(pcm(1200), session.process(pcm(1200), true))
        // Missing B's real completion still prevents any join to C.
        begin(session, "c")
        assertArrayEquals(pcm(1400), session.process(pcm(1400), true))
        session.endStream("c")
        begin(session, "d")
        assertArrayEquals(expected(pcm(1400, 8), pcm(1600)), session.process(pcm(1600), true))
    }

    @Test fun malformedStereoChunkInvalidatesItsStreamAndNeverChangesSampleCount() {
        val stereo = BoundaryDeclick.Pcm16Format(48_000, 2)
        val session = BoundaryDeclickSession()
        completed(session, format = stereo)
        begin(session, "b", format = stereo)
        val malformed = shortArrayOf(1200, -1200, 1200)
        assertArrayEquals(malformed, session.process(malformed, true))
        assertArrayEquals(pcm(1200, channels = 2), session.process(pcm(1200, channels = 2), true))
        session.endStream()
        begin(session, "c", format = stereo)
        assertArrayEquals(pcm(1400, channels = 2), session.process(pcm(1400, channels = 2), true))
    }

    @Test fun callerInputAndReturnedArraysCannotMutateRetainedTail() {
        val session = BoundaryDeclickSession()
        begin(session, "a")
        val input = pcm(1000)
        val result = session.process(input)
        assertNotSame(input, result)
        input.fill(0)
        result.fill(0)
        session.endStream()
        begin(session, "b")
        val head = pcm(1200)
        val repaired = session.process(head, true)
        assertArrayEquals(pcm(1200), head)
        assertArrayEquals(expected(pcm(1000, 8), head), repaired)
        repaired.fill(0)
        head.fill(0)
        session.endStream()
        begin(session, "c")
        assertArrayEquals(expected(pcm(1200, 8), pcm(1400)), session.process(pcm(1400), true))
    }

    @Test fun supportedRatesAndChannelsPreserveLengthChannelOrderAndUnmodifiedSuffix() {
        for (rate in listOf(8000, 44100, 48000, 192000)) for (channels in listOf(1, 2, 8)) {
            val format = BoundaryDeclick.Pcm16Format(rate, channels)
            val session = BoundaryDeclickSession()
            val left = ShortArray(8 * channels) { if (it % channels % 2 == 0) 1000 else -2000 }
            val right = ShortArray((rate / 2000 + 9) * channels) { if (it % channels % 2 == 0) 1200 else -2200 }
            begin(session, "a", format = format)
            assertArrayEquals(left, session.process(left, true))
            session.endStream()
            begin(session, "b", format = format)
            val result = session.process(right, true)
            assertArrayEquals(expected(left, right, format), result)
            assertEquals(right.size, result.size)
            for (i in rate / 2000 * channels until right.size) assertEquals(right[i], result[i])
        }
    }
}
