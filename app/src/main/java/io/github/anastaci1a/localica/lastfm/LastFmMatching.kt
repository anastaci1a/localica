package io.github.anastaci1a.localica.lastfm

import io.github.anastaci1a.localica.library.Track
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Indexed, deterministic multi-clue matching. A score cannot override conflicting recording clues.
 *
 * The archive streams through [resolve] one song at a time. Counts-only matching (#779, every launch
 * whose cache missed) folds each resolution into per-song totals immediately and keeps no per-song
 * result, so its live data is the library index plus the totals; the full result with review and
 * resolution lists is built only for explicit detail demand. Everything derived from a library song
 * or an archive song is computed once, so scoring a candidate allocates almost nothing.
 */
internal class LastFmMatchingEngine(tracks: List<Track>) {
    private class Local(val track: Track, val name: LastFmNames.Name, val album: String,
        val artist: Set<String>, val albumArtist: Set<String>, val tokens: List<String>, val version: Set<String>) {
        val tokenSet: Set<String> = tokens.toHashSet()
        val numbers: Set<String> = tokens.filter { it.all(Char::isDigit) }.toSet()
        val words: Set<String> = tokens.filterNot { it in stop }.toSet()
        val protectedTokens: Set<String> = tokens.filter { it in protected }.toSet()
        val partLabel: String? = part.find(name.title)?.value
        val mbid: String? = track.musicBrainzId?.takeIf(String::isNotBlank)?.let(::normalize)
        val variousOrUnknown = albumArtist == setOf("various", "artists") || albumArtist == setOf("unknown")
    }
    private class Query(val name: LastFmNames.Name, val artist: Set<String>, val album: String,
        val tokens: List<String>, val version: Set<String>, val uploader: Boolean) {
        val numbers: Set<String> = tokens.filter { it.all(Char::isDigit) }.toSet()
        val words: Set<String> = tokens.filterNot { it in stop }.toSet()
        val protectedTokens: Set<String> = tokens.filter { it in protected }.toSet()
        val partLabel: String? = part.find(name.title)?.value
        val generic = tokens.all { it.length <= 1 || it in genericTitles }
        val lookupTokens: List<String> = tokens.distinct().filterNot { it in stop }
        val knownAlbum = knownAlbum(album)
    }
    private class Evidence(val local: Local, val score: Int, val tier: LastFmMatchTier, val reason: String)
    private val locals = tracks.distinctBy { it.id }.map { track ->
        val name = LastFmNames.name(track.artist?.takeIf(String::isNotBlank) ?: track.albumArtist, track.title)
        Local(track, name, LastFmNames.album(track.album), artistTokens(name.artist),
            artistTokens(LastFmNames.name(track.albumArtist, "").artist), tokens(name.title), version(track.title, track.album))
    }
    private val byId = locals.associateBy { it.track.id }
    private val byTitle = locals.groupBy { it.name.title }
    private val byArtist = locals.groupBy { it.name.artist }
    private val byAlbum = locals.filter { knownAlbum(it.album) }.groupBy { it.album }
    private val byMbid = locals.filter { it.mbid != null }.groupBy { requireNotNull(it.mbid) }
    private val tokenIndex = buildMap<String, MutableList<Local>> {
        locals.forEach { local -> local.tokens.distinct().filterNot { it in stop }.forEach { getOrPut(it) { mutableListOf() } += local } }
    }
    private val candidateScratch = LinkedHashSet<Local>()

    /** Receives each archive song's outcome in archive order; implementations decide what to retain. */
    private interface Sink {
        fun resolved(remote: LastFmTrack, tracks: List<Track>, tier: LastFmMatchTier, reason: String, score: Int, manual: Boolean)
        fun review(remote: LastFmTrack, candidates: () -> List<Track>, reason: String)
        fun absent(remote: LastFmTrack)
        fun dismissed(remote: LastFmTrack)
    }

    /** Per-song totals and recording groups, folded as resolutions stream in (weighted union-find). */
    private class CountFolder {
        private val seen = LinkedHashSet<String>()
        private val parent = HashMap<String, String>()
        private val totals = HashMap<String, Long>()
        private fun root(id: String): String {
            var found = id
            while (parent.getValue(found) != found) found = parent.getValue(found)
            var current = id
            while (parent.getValue(current) != found) { val next = parent.getValue(current); parent[current] = found; current = next }
            return found
        }
        private fun union(a: String, b: String) {
            val first = root(a); val other = root(b)
            if (first == other) return
            parent[other] = first
            totals[first] = addPlays(totals[first] ?: 0, totals[other] ?: 0); totals.remove(other)
        }
        fun add(ids: List<String>, plays: Long) {
            ids.forEach { id -> if (seen.add(id)) parent[id] = id }
            for (index in 1 until ids.size) union(ids[0], ids[index])
            val group = root(ids[0])
            totals[group] = addPlays(totals[group] ?: 0, plays.coerceAtLeast(0))
        }
        fun groups(): Map<String, String> = seen.associateWith(::root)
        fun versionCounts(groups: Map<String, String>): Map<String, Long> = groups.mapValues { (_, group) -> totals.getValue(group) }
    }

    fun match(snapshot: LastFmSnapshot, mappings: Map<String, String>, checkActive: () -> Unit = {}): LastFmMatches {
        val review = mutableListOf<LastFmReview>(); val resolved = mutableListOf<LastFmResolution>()
        val editions = mutableListOf<LastFmReview>(); val absent = mutableListOf<LastFmTrack>()
        val folder = CountFolder(); var dismissed = 0
        resolve(snapshot, mappings, checkActive, object : Sink {
            override fun resolved(remote: LastFmTrack, tracks: List<Track>, tier: LastFmMatchTier, reason: String, score: Int, manual: Boolean) {
                resolved += LastFmResolution(remote, tracks, tier, reason, score, manual)
                if (tracks.size > 1) editions += LastFmReview(remote, tracks, reason, tier)
                folder.add(tracks.map { it.id }, remote.plays)
            }
            override fun review(remote: LastFmTrack, candidates: () -> List<Track>, reason: String) { review += LastFmReview(remote, candidates(), reason) }
            override fun absent(remote: LastFmTrack) { absent += remote }
            override fun dismissed(remote: LastFmTrack) { dismissed++; absent += remote }
        })
        val groups = folder.groups()
        return LastFmMatches(folder.versionCounts(groups), review.sortedByDescending { it.remote.plays }, absent.size,
            editions.sortedByDescending { it.remote.plays }, resolved.size, dismissed, resolved, absent, groups, snapshot)
    }

    /** Counts and groups only: identical to [match] for those fields, with no per-song result retained. */
    fun matchCounts(snapshot: LastFmSnapshot, mappings: Map<String, String>, checkActive: () -> Unit = {}): LastFmMatches {
        val folder = CountFolder(); var matched = 0; var absent = 0; var dismissed = 0
        var clear = 0; var judgments = 0; var reviews = 0; var editions = 0
        resolve(snapshot, mappings, checkActive, object : Sink {
            override fun resolved(remote: LastFmTrack, tracks: List<Track>, tier: LastFmMatchTier, reason: String, score: Int, manual: Boolean) {
                matched++; folder.add(tracks.map { it.id }, remote.plays)
                if (tier == LastFmMatchTier.CLEAR) clear++ else if (tier == LastFmMatchTier.PROBABLE) judgments++
                if (tracks.size > 1) editions++
            }
            override fun review(remote: LastFmTrack, candidates: () -> List<Track>, reason: String) { reviews++ }
            override fun absent(remote: LastFmTrack) { absent++ }
            override fun dismissed(remote: LastFmTrack) { dismissed++; absent++ }
        })
        val groups = folder.groups()
        return LastFmMatches(folder.versionCounts(groups), emptyList(), absent, matched = matched, dismissed = dismissed,
            recordingGroups = groups, sourceSnapshot = snapshot, complete = false,
            buckets = LastFmMatchBuckets(matched, clear, judgments, reviews, absent, editions))
    }

    private fun resolve(snapshot: LastFmSnapshot, mappings: Map<String, String>, checkActive: () -> Unit, sink: Sink) {
        for ((index, remote) in snapshot.tracks.withIndex()) {
            if (index % 64 == 0) checkActive()
            val mapping = mappings[remote.key]
            if (mapping == LastFmMatcher.NONE) { sink.dismissed(remote); continue }
            val manual = mapping?.let(byId::get)
            val strong = remote.mbid?.takeIf(String::isNotBlank)?.let { byMbid[normalize(it)] }.orEmpty()
            var chosen: List<Local>; var tier = LastFmMatchTier.CLEAR; var reason: String; var score: Int
            if (manual != null) {
                chosen = expand(listOf(manual)); reason = "Chosen by you"; score = 100
            } else if (strong.isNotEmpty() && mapping != LastFmMatcher.REVIEW) {
                chosen = expand(strong); reason = "Recording MusicBrainz ID agrees"; score = 100
            } else {
                // Best evidence per library song, first maximum kept (as maxBy did), then a total order:
                // the same list the former flatMap/groupBy/sortedWith pipeline produced, without its temporaries.
                bestByTrack.clear(); evidence.clear()
                for (query in queries(remote)) forEachCandidate(query) { local ->
                    val found = score(remote, query, local) ?: return@forEachCandidate
                    val id = local.track.id
                    val current = bestByTrack[id]
                    if (current == null || found.score > current.score) bestByTrack[id] = found
                }
                evidence.addAll(bestByTrack.values); evidence.sortWith(evidenceOrder)
                val top = evidence.firstOrNull()
                if (mapping == LastFmMatcher.REVIEW) {
                    sink.review(remote, { (strong + evidence.map { it.local }).distinctBy { it.track.id }.map { it.track } }, "Automatic match undone by you")
                    continue
                }
                if (top == null) { sink.absent(remote); continue }
                val family = evidence.filter { sameFamily(top.local, it.local) }
                val competitor = evidence.firstOrNull { candidate -> family.none { it.local.track.id == candidate.local.track.id } }
                if (top.tier == LastFmMatchTier.UNCERTAIN || competitor != null && top.score - competitor.score < 10) {
                    sink.review(remote, { evidence.map { it.local.track } },
                        if (competitor != null && top.score - competitor.score < 10) "Several plausible local recordings" else top.reason)
                    continue
                }
                chosen = expand(family.map { it.local }); tier = top.tier; reason = top.reason; score = top.score
            }
            val selected = chosen.map { it.track }.distinctBy { it.id }
            sink.resolved(remote, selected, tier, reason, score, manual != null)
        }
    }

    private fun queries(remote: LastFmTrack): List<Query> {
        fun query(artist: String, title: String, uploader: Boolean): Query {
            val name = LastFmNames.name(artist, title)
            return Query(name, artistTokens(name.artist), LastFmNames.album(remote.album), tokens(name.title), version(title, remote.album), uploader)
        }
        val original = query(remote.artist, remote.title, false)
        val parts = remote.title.split(uploadSeparator, limit = 3)
        val parsed = if (parts.size >= 2) {
            val dropChannel = parts.size == 3 && LastFmNames.name(parts[0], "").artist == original.name.artist
            val artist = parts[if (dropChannel) 1 else 0].trim()
            val title = if (dropChannel) parts[2] else parts.drop(1).joinToString(" - ")
            val name = LastFmNames.name(artist, title)
            if (name.artist.isNotBlank() && name.title.isNotBlank()) query(artist, title, name.artist != original.name.artist) else null
        } else null
        return listOfNotNull(original, parsed).distinctBy { it.name }
    }

    private val bestByTrack = HashMap<String, Evidence>()
    private val evidence = ArrayList<Evidence>()
    private val evidenceOrder = compareByDescending<Evidence> { it.score }.thenBy { it.local.track.id }

    /** Insertion order equals the former list order; locals are unique per track id, so the set deduplicates identically. */
    private inline fun forEachCandidate(query: Query, action: (Local) -> Unit) {
        val scratch = candidateScratch
        scratch.clear()
        byTitle[query.name.title]?.let(scratch::addAll)
        byArtist[query.name.artist]?.let(scratch::addAll)
        if (query.knownAlbum) byAlbum[query.album]?.let(scratch::addAll)
        query.lookupTokens.forEach { token -> tokenIndex[token]?.takeIf { it.size <= 256 }?.let(scratch::addAll) }
        for (local in scratch) action(local)
    }

    /** [sameFamily] for exactly two rows, without building the intermediate lists. */
    private fun sameFamily(a: Local, b: Local): Boolean =
        a.name.artist == b.name.artist && a.name.title == b.name.title &&
            a.album == b.album && a.album.isNotBlank() &&
            !(a.name.featured.isNotBlank() && b.name.featured.isNotBlank() && a.name.featured != b.name.featured) &&
            !(a.mbid != null && b.mbid != null && a.mbid != b.mbid) &&
            duration(a.track.durationMs, b.track.durationMs) != -1

    private fun score(remote: LastFmTrack, q: Query, local: Local): Evidence? {
        val exactTitle = q.name.title == local.name.title && q.name.title.isNotBlank()
        val similarity = if (exactTitle) 1.0 else orderedSimilarity(q.tokens, local.tokens)
        val mainArtist = setSimilarity(q.artist, local.artist)
        val albumArtist = if (local.variousOrUnknown) 0.0 else setSimilarity(q.artist, local.albumArtist)
        val artist = max(mainArtist, albumArtist)
        val exactArtist = q.name.artist.isNotBlank() && q.name.artist == local.name.artist
        val album = q.knownAlbum && q.album == local.album
        val meaningfulOverlap = exactTitle || q.words.any { !it.all(Char::isDigit) && it in local.tokenSet }
        val generic = q.generic
        if (!(exactTitle && (!generic || artist >= .5 || album) || meaningfulOverlap && similarity >= .4 && (artist >= .9 || album))) return null
        if (q.numbers.isNotEmpty() && local.numbers.isNotEmpty() && q.numbers != local.numbers) return null
        if (q.partLabel != null && local.partLabel != null && q.partLabel != local.partLabel) return null
        if (q.version.isNotEmpty() && local.version.isNotEmpty() && q.version != local.version) return null
        if (q.name.featured.isNotBlank() && local.name.featured.isNotBlank() && q.name.featured != local.name.featured) return null
        val duration = duration(remote.durationMs, local.track.durationMs)
        val remoteId = remote.mbid?.takeIf(String::isNotBlank)
        val idConflict = remoteId != null && local.mbid != null && normalize(remoteId) != local.mbid
        val versionUnknown = q.version != local.version || q.protectedTokens != local.protectedTokens
        val guestUnknown = q.name.featured != local.name.featured
        val remoteWords = q.words
        val localWords = local.words
        val sameContent = remoteWords.isNotEmpty() && remoteWords == localWords
        val titleClash = remoteWords.any { it !in localWords } && localWords.any { it !in remoteWords }
        val titleScore = (45 * similarity).roundToInt().coerceAtLeast(if (sameContent && similarity >= .8) 40 else 0)
        val value = titleScore + (30 * mainArtist).roundToInt().coerceAtLeast((25 * albumArtist).roundToInt()) +
            (if (album) 15 else 0) + (if (duration == 2) 10 else if (duration == 1) 5 else 0)
        val canAuto = !idConflict && !versionUnknown && !titleClash && duration != -1
        val tier = when {
            canAuto && exactArtist && exactTitle && !q.uploader && !guestUnknown -> LastFmMatchTier.CLEAR
            canAuto && artist >= .9 && value >= 70 && (exactTitle || similarity >= .8 && (album || duration == 2 || sameContent)) -> LastFmMatchTier.PROBABLE
            else -> LastFmMatchTier.UNCERTAIN
        }
        val reason = when {
            idConflict -> "Recording IDs disagree"
            versionUnknown -> "Version needs a decision"
            titleClash -> "Title wording may identify different songs"
            duration == -1 -> "Duration differs substantially"
            tier != LastFmMatchTier.UNCERTAIN -> buildList {
                if (artist >= .9) add(if (q.uploader) "artist parsed from upload title" else if (exactArtist) "artist" else "artist wording")
                if (similarity >= .8) add(if (exactTitle) "title" else "ordered title tokens")
                if (album) add("album")
                if (duration == 2) add("duration")
            }.joinToString(" + ") + if (guestUnknown) "; featured credit only on one side" else ""
            exactTitle && artist < .9 -> "Title only; artist differs"
            else -> "Artist agrees; title needs a decision"
        }
        return Evidence(local, value, tier, reason)
    }

    /** Expand only a compatible recording family; an untagged copy cannot bridge conflicting known recordings. */
    private fun expand(anchors: List<Local>): List<Local> = anchors.flatMap { anchor ->
        val family = byTitle[anchor.name.title].orEmpty().filter {
            it.name.artist == anchor.name.artist && anchor.album.isNotBlank() && it.album == anchor.album &&
                (it.name.featured.isBlank() || anchor.name.featured.isBlank() || it.name.featured == anchor.name.featured)
        }
        val known = family.mapNotNull { it.mbid }.distinct()
        val anchorId = anchor.mbid
        val compatible = family.filter { candidate ->
            val id = candidate.mbid
            when {
                candidate.track.id == anchor.track.id -> true
                anchorId != null && id != null -> anchorId == id
                known.size > 1 -> false
                else -> duration(anchor.track.durationMs, candidate.track.durationMs) != -1
            }
        }
        if (sameFamily(compatible)) compatible else listOf(anchor)
    }.distinctBy { it.track.id }

    private fun sameFamily(rows: List<Local>): Boolean = rows.isNotEmpty() &&
        rows.map { it.name.artist to it.name.title }.distinct().size == 1 &&
        rows.map { it.album }.distinct().singleOrNull()?.isNotBlank() == true &&
        rows.mapNotNull { it.name.featured.takeIf(String::isNotBlank) }.distinct().size <= 1 &&
        rows.mapNotNull { it.mbid }.distinct().size <= 1 &&
        rows.all { duration(rows.first().track.durationMs, it.track.durationMs) != -1 }

    private companion object {
        val stop = setOf("a", "an", "the", "and", "of", "to", "in", "on", "at", "for", "with")
        val genericTitles = setOf("intro", "interlude", "outro", "untitled", "song", "track", "theme", "skit", "introduction")
        val protected = setOf("live", "acoustic", "instrumental", "remix", "demo", "reprise", "revisited", "intro", "interlude", "outro", "cover", "karaoke", "extended", "edit", "medley", "part")
        val uploadSeparator = Regex("\\s+[-–—]\\s+")
        val bracket = Regex("[\\[(]([^\\])]+)[\\])]")
        val part = Regex("\\b(?:part|pt)\\s+(?:[ivx]+|\\d+)\\b")
        fun tokens(value: String) = value.split(' ').filter(String::isNotBlank)
        fun artistTokens(value: String) = tokens(LastFmNames.text(value)).filterNot { it == "the" || it == "and" }.toSet()
        fun knownAlbum(value: String) = value.isNotBlank() && value !in setOf("unknown", "unknown album", "none", "compilation")
        fun setSimilarity(a: Set<String>, b: Set<String>) = if (a.isEmpty() || b.isEmpty()) 0.0 else
            2.0 * a.count { it in b } / (a.size + b.size)
        fun orderedSimilarity(a: List<String>, b: List<String>): Double {
            if (a.isEmpty() || b.isEmpty() || a.size > 64 || b.size > 64) return 0.0
            val previous = IntArray(b.size + 1)
            a.forEach { left -> var diagonal = 0
                b.forEachIndexed { index, right -> val old = previous[index + 1]
                    previous[index + 1] = if (left == right) diagonal + 1 else max(previous[index + 1], previous[index]); diagonal = old }
            }
            return 2.0 * previous.last() / (a.size + b.size)
        }
        // 0 means unavailable, 2 close, 1 weak support, -1 a substantial mismatch. Zero never agrees with anything.
        fun duration(a: Long?, b: Long?): Int {
            if (a == null || b == null || a <= 0 || b <= 0) return 0
            val difference = abs(a.toDouble() - b.toDouble()); val longest = max(a, b).toDouble()
            return when { difference <= max(2000.0, longest * .02) -> 2
                difference <= max(5000.0, longest * .05) -> 1
                difference > max(15000.0, longest * .15) -> -1
                else -> 0 }
        }
        fun version(title: String, album: String?): Set<String> = buildSet {
            bracket.findAll(title).map { LastFmNames.text(it.groupValues[1]) }.filter { text -> tokens(text).any { it in protected } }.forEach(::add)
            uploadSeparator.split(title).drop(1).map(LastFmNames::text).filter { text -> tokens(text).any { it in protected } }.forEach(::add)
            val albumName = LastFmNames.text(album)
            if (albumName == "live" || albumName.startsWith("live at ") || albumName.startsWith("live from ")) add(albumName)
        }
    }
}
