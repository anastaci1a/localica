package io.github.anastaci1a.localica.lastfm

import io.github.anastaci1a.localica.library.Track
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** Frozen copy of the engine as committed at 891c838b, before #779 streamed the archive: the independent full-output oracle for LastFmMatchCacheTest. Never edit; replace only with a deliberate semantics change plus a MATCHER_VERSION bump. */
internal class LegacyLastFmMatchingEngine(tracks: List<Track>) {
    private data class Local(val track: Track, val name: LastFmNames.Name, val album: String,
        val artist: Set<String>, val albumArtist: Set<String>, val tokens: List<String>, val version: Set<String>)
    private data class Query(val name: LastFmNames.Name, val artist: Set<String>, val album: String,
        val tokens: List<String>, val version: Set<String>, val uploader: Boolean)
    private data class Evidence(val local: Local, val score: Int, val tier: LastFmMatchTier, val reason: String)
    private val locals = tracks.distinctBy { it.id }.map { track ->
        val name = LastFmNames.name(track.artist?.takeIf(String::isNotBlank) ?: track.albumArtist, track.title)
        Local(track, name, LastFmNames.album(track.album), artistTokens(name.artist),
            artistTokens(LastFmNames.name(track.albumArtist, "").artist), tokens(name.title), version(track.title, track.album))
    }
    private val byId = locals.associateBy { it.track.id }
    private val byTitle = locals.groupBy { it.name.title }
    private val byArtist = locals.groupBy { it.name.artist }
    private val byAlbum = locals.filter { knownAlbum(it.album) }.groupBy { it.album }
    private val byMbid = locals.filter { !it.track.musicBrainzId.isNullOrBlank() }.groupBy { normalize(it.track.musicBrainzId) }
    private val tokenIndex = buildMap<String, MutableList<Local>> {
        locals.forEach { local -> local.tokens.distinct().filterNot { it in stop }.forEach { getOrPut(it) { mutableListOf() } += local } }
    }

    fun match(snapshot: LastFmSnapshot, mappings: Map<String, String>, checkActive: () -> Unit = {}): LastFmMatches {
        val counts = linkedMapOf<String, Long>(); val review = mutableListOf<LastFmReview>()
        val resolved = mutableListOf<LastFmResolution>(); val editions = mutableListOf<LastFmReview>()
        val absent = mutableListOf<LastFmTrack>(); var dismissed = 0
        for ((index, remote) in snapshot.tracks.withIndex()) {
            if (index % 64 == 0) checkActive()
            val mapping = mappings[remote.key]
            if (mapping == LastFmMatcher.NONE) { dismissed++; absent += remote; continue }
            val manual = mapping?.let(byId::get)
            val strong = remote.mbid?.takeIf(String::isNotBlank)?.let { byMbid[normalize(it)] }.orEmpty()
            var chosen = emptyList<Local>(); var tier = LastFmMatchTier.CLEAR; var reason = ""; var score = 0
            if (manual != null) {
                chosen = expand(listOf(manual)); reason = "Chosen by you"; score = 100
            } else if (strong.isNotEmpty() && mapping != LastFmMatcher.REVIEW) {
                chosen = expand(strong); reason = "Recording MusicBrainz ID agrees"; score = 100
            } else {
                val evidence = queries(remote).flatMap { query -> candidates(query).mapNotNull { score(remote, query, it) } }
                    .groupBy { it.local.track.id }.values.map { rows -> rows.maxBy { it.score } }
                    .sortedWith(compareByDescending<Evidence> { it.score }.thenBy { it.local.track.id })
                val top = evidence.firstOrNull()
                if (mapping == LastFmMatcher.REVIEW) {
                    val candidates = (strong + evidence.map { it.local }).distinctBy { it.track.id }.map { it.track }
                    review += LastFmReview(remote, candidates, "Automatic match undone by you")
                    continue
                }
                if (top == null) { absent += remote; continue }
                val family = evidence.filter { sameFamily(listOf(top.local, it.local)) }
                val competitor = evidence.firstOrNull { candidate -> family.none { it.local.track.id == candidate.local.track.id } }
                if (top.tier == LastFmMatchTier.UNCERTAIN || competitor != null && top.score - competitor.score < 10) {
                    review += LastFmReview(remote, evidence.map { it.local.track },
                        if (competitor != null && top.score - competitor.score < 10) "Several plausible local recordings" else top.reason)
                    continue
                }
                chosen = expand(family.map { it.local }); tier = top.tier; reason = top.reason; score = top.score
            }
            val selected = chosen.map { it.track }.distinctBy { it.id }
            resolved += LastFmResolution(remote, selected, tier, reason, score, manual != null)
            if (selected.size > 1) editions += LastFmReview(remote, selected, reason, tier)
            selected.forEach { track ->
                val previous = counts[track.id] ?: 0; val plays = remote.plays.coerceAtLeast(0)
                counts[track.id] = if (previous > Long.MAX_VALUE - plays) Long.MAX_VALUE else previous + plays
            }
        }
        val parent = counts.keys.associateWith { it }.toMutableMap()
        fun root(id: String): String {
            var found = id
            while (parent.getValue(found) != found) found = parent.getValue(found)
            var current = id
            while (parent.getValue(current) != found) { val next = parent.getValue(current); parent[current] = found; current = next }
            return found
        }
        resolved.forEach { resolution -> resolution.tracks.drop(1).forEach { track ->
            val first = root(resolution.tracks.first().id); val other = root(track.id)
            if (first != other) parent[other] = first
        } }
        val groups = counts.keys.associateWith(::root)
        val totals = mutableMapOf<String, Long>()
        resolved.forEach { resolution -> val group = groups.getValue(resolution.tracks.first().id)
            totals[group] = addPlays(totals[group] ?: 0, resolution.remote.plays.coerceAtLeast(0)) }
        val versionCounts = groups.mapValues { (_, group) -> totals.getValue(group) }
        return LastFmMatches(versionCounts, review.sortedByDescending { it.remote.plays }, absent.size,
            editions.sortedByDescending { it.remote.plays }, resolved.size, dismissed, resolved, absent, groups, snapshot)
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

    private fun candidates(query: Query): List<Local> = buildList {
        addAll(byTitle[query.name.title].orEmpty())
        addAll(byArtist[query.name.artist].orEmpty())
        if (knownAlbum(query.album)) addAll(byAlbum[query.album].orEmpty())
        query.tokens.distinct().filterNot { it in stop }.forEach { token ->
            tokenIndex[token]?.takeIf { it.size <= 256 }?.let(::addAll)
        }
    }.distinctBy { it.track.id }

    private fun score(remote: LastFmTrack, q: Query, local: Local): Evidence? {
        val exactTitle = q.name.title == local.name.title && q.name.title.isNotBlank()
        val similarity = if (exactTitle) 1.0 else orderedSimilarity(q.tokens, local.tokens)
        val mainArtist = setSimilarity(q.artist, local.artist)
        val albumArtist = if (local.albumArtist == setOf("various", "artists") || local.albumArtist == setOf("unknown")) 0.0
            else setSimilarity(q.artist, local.albumArtist)
        val artist = max(mainArtist, albumArtist)
        val exactArtist = q.name.artist.isNotBlank() && q.name.artist == local.name.artist
        val album = knownAlbum(q.album) && q.album == local.album
        val meaningfulOverlap = exactTitle || q.tokens.any { it !in stop && !it.all(Char::isDigit) && it in local.tokens }
        val generic = q.tokens.all { it.length <= 1 || it in genericTitles }
        if (!(exactTitle && (!generic || artist >= .5 || album) || meaningfulOverlap && similarity >= .4 && (artist >= .9 || album))) return null
        val qNumbers = q.tokens.filter { it.all(Char::isDigit) }.toSet()
        val localNumbers = local.tokens.filter { it.all(Char::isDigit) }.toSet()
        if (qNumbers.isNotEmpty() && localNumbers.isNotEmpty() && qNumbers != localNumbers) return null
        val qPart = part.find(q.name.title)?.value
        val localPart = part.find(local.name.title)?.value
        if (qPart != null && localPart != null && qPart != localPart) return null
        if (q.version.isNotEmpty() && local.version.isNotEmpty() && q.version != local.version) return null
        if (q.name.featured.isNotBlank() && local.name.featured.isNotBlank() && q.name.featured != local.name.featured) return null
        val duration = duration(remote.durationMs, local.track.durationMs)
        val idConflict = !remote.mbid.isNullOrBlank() && !local.track.musicBrainzId.isNullOrBlank() &&
            normalize(remote.mbid) != normalize(local.track.musicBrainzId)
        val versionUnknown = q.version != local.version ||
            q.tokens.filter { it in protected }.toSet() != local.tokens.filter { it in protected }.toSet()
        val guestUnknown = q.name.featured != local.name.featured
        val remoteWords = q.tokens.filterNot { it in stop }.toSet()
        val localWords = local.tokens.filterNot { it in stop }.toSet()
        val sameContent = remoteWords.isNotEmpty() && remoteWords == localWords
        val titleClash = remoteWords.minus(localWords).isNotEmpty() && localWords.minus(remoteWords).isNotEmpty()
        val titleScore = (45 * similarity).roundToInt().coerceAtLeast(if (sameContent && similarity >= .8) 40 else 0)
        val value = titleScore + (30 * mainArtist).roundToInt().coerceAtLeast((25 * albumArtist).roundToInt()) +
            (if (album) 15 else 0) + (if (duration == 2) 10 else if (duration == 1) 5 else 0)
        val clues = buildList {
            if (artist >= .9) add(if (q.uploader) "artist parsed from upload title" else if (exactArtist) "artist" else "artist wording")
            if (similarity >= .8) add(if (exactTitle) "title" else "ordered title tokens")
            if (album) add("album")
            if (duration == 2) add("duration")
        }.joinToString(" + ")
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
            tier != LastFmMatchTier.UNCERTAIN -> clues + if (guestUnknown) "; featured credit only on one side" else ""
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
        val known = family.mapNotNull { it.track.musicBrainzId?.takeIf(String::isNotBlank)?.let(::normalize) }.distinct()
        val anchorId = anchor.track.musicBrainzId?.takeIf(String::isNotBlank)?.let(::normalize)
        val compatible = family.filter { candidate ->
            val id = candidate.track.musicBrainzId?.takeIf(String::isNotBlank)?.let(::normalize)
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
        rows.mapNotNull { it.track.musicBrainzId?.takeIf(String::isNotBlank)?.let(::normalize) }.distinct().size <= 1 &&
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
            2.0 * a.intersect(b).size / (a.size + b.size)
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
