package io.github.anastaci1a.localica.lastfm

import kotlinx.coroutines.*
import io.github.anastaci1a.localica.network.NetworkConsent
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class LastFmFailure(message: String, val retryable: Boolean = false, val needsFullRescan: Boolean = false) : Exception(message)
data class LastFmPage(val number: Int, val totalPages: Int, val tracks: List<LastFmTrack>)
data class LastFmRecentTrack(val artist: String, val title: String, val timestampSeconds: Long, val album: String? = null, val mbid: String? = null)
data class LastFmRecentPage(val number: Int, val totalPages: Int, val tracks: List<LastFmRecentTrack>)
fun interface LastFmApi {
    suspend fun page(username: String, number: Int): LastFmPage
    fun supportsQuickRefresh(): Boolean = false
    suspend fun recent(username: String, number: Int, from: Long, through: Long): LastFmRecentPage = throw LastFmFailure("Use a full rescan in this build.")
    suspend fun info(username: String, track: LastFmTrack): LastFmTrack = throw LastFmFailure("Use a full rescan in this build.")
}

/** Three fixed read-only HTTPS methods. Transport URLs, keys and raw API messages never reach diagnostics/UI. */
class HttpLastFmApi(private val key: String, private val consent: NetworkConsent,
    private val connectionFactory: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }) : LastFmApi {
    override fun supportsQuickRefresh() = true
    override suspend fun page(username: String, number: Int) = LastFmResponses.parse(request(linkedMapOf(
        "method" to "user.getTopTracks", "period" to "overall", "limit" to "200", "page" to number.toString(), "user" to username)))
    override suspend fun recent(username: String, number: Int, from: Long, through: Long) = LastFmResponses.recent(request(linkedMapOf(
        "method" to "user.getRecentTracks", "limit" to "200", "page" to number.toString(), "user" to username,
        "from" to from.toString(), "to" to through.toString())))
    override suspend fun info(username: String, track: LastFmTrack) = LastFmResponses.info(request(linkedMapOf(
        "method" to "track.getInfo", "artist" to track.artist, "track" to track.title, "username" to username, "autocorrect" to "0")), track)
    private suspend fun request(params: Map<String, String>): String = withContext(Dispatchers.IO) {
        if (!consent.allowed.value) throw LastFmFailure("Online features are off. Enable them before syncing.")
        check(key.isNotBlank())
        val query = (params + mapOf("format" to "json", "api_key" to key)).entries.joinToString("&") {
            URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8") }
        val connection = connectionFactory(URL("https://ws.audioscrobbler.com/2.0/?$query"))
        try {
            connection.requestMethod = "GET"; connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000; connection.readTimeout = 20_000
            connection.setRequestProperty("Accept", "application/json")
            if (!consent.allowed.value) throw LastFmFailure("Online features are off. Enable them before syncing.")
            val status = connection.responseCode
            if (status != 200) throw LastFmFailure(if (status == 429) "Last.fm rate limit reached. Try again later." else "Last.fm could not be reached.", status == 429 || status >= 500)
            connection.inputStream.bufferedReader().use { reader ->
                val result = StringBuilder(); val buffer = CharArray(8192)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = reader.read(buffer); if (read < 0) break
                    if (result.length + read > 4_000_000) throw LastFmFailure("Last.fm returned an oversized response.")
                    result.append(buffer, 0, read)
                }
                result.toString()
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: LastFmFailure) { throw failure }
        catch (_: Exception) { throw LastFmFailure("Last.fm is unavailable. Your previous archive is kept.", true) }
        finally { connection.disconnect() }
    }
}

object LastFmResponses {
    private fun JSONObject.optionalText(key: String): String? = if (isNull(key)) null else optString(key).takeIf(String::isNotBlank)
    private fun artist(track: JSONObject): String = when (val value = track.get("artist")) {
        is JSONObject -> value.optionalText("name") ?: value.optionalText("#text").orEmpty()
        is String -> value
        else -> error("Invalid artist")
    }
    private fun album(track: JSONObject): String? = when (val value = track.opt("album")) {
        is JSONObject -> value.optionalText("#text") ?: value.optionalText("name") ?: value.optionalText("title")
        is String -> value.takeIf(String::isNotBlank)
        else -> null
    }
    private fun rows(root: JSONObject): List<JSONObject> {
        val raw = when (val value = root.opt("track")) {
            null, JSONObject.NULL -> JSONArray()
            is JSONArray -> value
            is JSONObject -> JSONArray().put(value)
            else -> error("Invalid track list")
        }
        return (0 until raw.length()).map(raw::getJSONObject)
    }
    private fun checkError(json: JSONObject, info: Boolean = false) {
        if (!json.has("error")) return
        val code = json.getInt("error")
        throw LastFmFailure(when {
            info && code == 6 -> "A changed song could not be read. A full rescan is needed."
            code in setOf(6, 7) -> "The Last.fm username was not found or is private."
            code in setOf(10, 26) -> "The bundled Last.fm API key is unavailable."
            code == 29 -> "Last.fm rate limit reached. Try again later."
            else -> "Last.fm could not complete the request."
        }, code in setOf(8, 11, 16, 29), info && code == 6)
    }
    private inline fun <T> response(body: String, read: (JSONObject) -> T): T {
        try { return read(JSONObject(body)) }
        catch (failure: LastFmFailure) { throw failure }
        catch (_: Exception) { throw LastFmFailure("Last.fm returned an invalid response. Your previous archive is kept.") }
    }
    fun parse(body: String): LastFmPage = response(body) { json ->
        checkError(json)
        val root = json.getJSONObject("toptracks"); val attr = root.getJSONObject("@attr")
        val page = attr.get("page").toString().toInt(); val total = attr.get("totalPages").toString().toInt()
        require(page > 0 && total in 0..10_000)
        val tracks = rows(root).map { track ->
            val artist = artist(track); val title = track.getString("name"); val plays = track.get("playcount").toString().toLong()
            require(artist.isNotBlank() && title.isNotBlank() && plays >= 0)
            val seconds = track.optionalText("duration")?.toLongOrNull()?.takeIf { it > 0 && it <= Long.MAX_VALUE / 1000 }
            LastFmTrack(artist, title, plays, album(track), track.optionalText("mbid"), seconds?.times(1000))
        }
        require(total != 0 || tracks.isEmpty())
        LastFmPage(page, maxOf(1, total), tracks)
    }
    fun recent(body: String): LastFmRecentPage = response(body) { json ->
        checkError(json)
        val root = json.getJSONObject("recenttracks"); val attr = root.getJSONObject("@attr")
        val page = attr.get("page").toString().toInt(); val total = attr.get("totalPages").toString().toInt()
        require(page > 0 && total in 0..10_000)
        val tracks = rows(root).mapNotNull { track ->
            if (track.optJSONObject("@attr")?.optString("nowplaying") == "true") return@mapNotNull null
            val date = track.optJSONObject("date") ?: error("Missing scrobble date")
            val timestamp = date.get("uts").toString().toLong(); require(timestamp >= 0)
            val artist = artist(track); val title = track.getString("name"); require(artist.isNotBlank() && title.isNotBlank())
            LastFmRecentTrack(artist, title, timestamp, album(track), track.optionalText("mbid"))
        }
        require(total != 0 || tracks.isEmpty())
        LastFmRecentPage(page, maxOf(1, total), tracks)
    }
    fun info(body: String, expected: LastFmTrack): LastFmTrack = response(body) { json ->
        checkError(json, info = true)
        val track = json.getJSONObject("track")
        val name = track.getString("name"); val artist = artist(track)
        if (normalize(name) != normalize(expected.title) || normalize(artist) != normalize(expected.artist))
            throw LastFmFailure("Last.fm returned a different song. A full rescan is needed.", needsFullRescan = true)
        val mbid = track.optionalText("mbid")
        if (!expected.mbid.isNullOrBlank() && mbid != null && normalize(expected.mbid) != normalize(mbid))
            throw LastFmFailure("Last.fm changed a recording ID. A full rescan is needed.", needsFullRescan = true)
        // Never fall back to the global playcount. Only userplaycount is an account count.
        val plays = track.get("userplaycount").toString().toLong(); require(plays >= 0)
        val duration = track.optionalText("duration")?.toLongOrNull()?.takeIf { it > 0 }
        expected.copy(plays = plays, durationMs = duration ?: expected.durationMs)
    }
}

/** One pacer covers every page, info request, retry and fallback in a manual sync. */
class LastFmSync(private val api: LastFmApi, private val pause: suspend (Long) -> Unit = { delay(it) }) {
    private class FullRescanNeeded : Exception()
    private inner class Pacer(private val mayRequest: () -> Boolean) {
        var requests = 0
        suspend fun <T> call(request: suspend () -> T): T {
            for (attempt in 0..2) {
                if (requests++ > 0) pause(if (attempt == 0) 500 else 2000L * attempt)
                currentCoroutineContext().ensureActive()
                if (!mayRequest()) throw LastFmFailure("Online features or Last.fm are off. Your archive is kept.")
                try { return request() }
                catch (failure: LastFmFailure) { if (!failure.retryable || attempt == 2) throw failure }
            }
            error("Unreachable retry")
        }
    }
    suspend fun fetch(username: String, onProgress: suspend (LastFmProgress) -> Unit = {}, mayRequest: () -> Boolean = { true }): List<LastFmTrack> =
        full(username, Pacer(mayRequest), onProgress)

    suspend fun refresh(username: String, previous: LastFmSnapshot?, fullRescan: Boolean, through: Long,
        onProgress: suspend (LastFmProgress) -> Unit = {}, mayRequest: () -> Boolean = { true }): List<LastFmTrack> {
        val pacer = Pacer(mayRequest)
        val boundary = previous?.refreshBoundarySeconds ?: previous?.syncedAt?.div(1000)
        if (!fullRescan && api.supportsQuickRefresh() && previous != null && previous.username.equals(username, true) && boundary != null && boundary <= through) {
            try { return quick(username, previous, (boundary - 1).coerceAtLeast(0), through + 1, pacer, onProgress) }
            catch (_: FullRescanNeeded) { /* The same pacer continues; no partial result has been published. */ }
        }
        return full(username, pacer, onProgress)
    }
    private suspend fun full(username: String, pacer: Pacer, progress: suspend (LastFmProgress) -> Unit): List<LastFmTrack> {
        val collected = linkedMapOf<String, LastFmTrack>(); var page = 1; var total = 1
        while (page <= total) {
            val response = pacer.call { api.page(username, page) }
            if (response.number != page || response.totalPages < page || response.tracks.isEmpty() && page < response.totalPages)
                throw LastFmFailure("Last.fm returned incomplete pagination. Your previous archive is kept.")
            total = maxOf(total, response.totalPages)
            response.tracks.forEach { track ->
                val old = collected[track.key]
                if (old == null || old.plays < track.plays) collected[track.key] = track
            }
            if (collected.size > 1_000_000 || total > 10_000) throw LastFmFailure("This Last.fm library is too large to sync.")
            progress(status(page, total, collected.values)); page++
        }
        return collected.values.toList()
    }
    private suspend fun quick(username: String, old: LastFmSnapshot, from: Long, through: Long, pacer: Pacer,
        report: suspend (LastFmProgress) -> Unit): List<LastFmTrack> {
        val changed = linkedMapOf<Pair<String, String>, LastFmRecentTrack>()
        val budget = maxOf(1, (old.tracks.size + 199) / 200)
        var page = 1; var total = 1
        while (page <= total) {
            val response = pacer.call { api.recent(username, page, from, through) }
            if (response.number != page || response.totalPages < page || response.tracks.isEmpty() && page < response.totalPages) throw LastFmFailure("Last.fm returned incomplete recent history. Your previous archive is kept.")
            total = maxOf(total, response.totalPages)
            response.tracks.filter { it.timestampSeconds in (from + 1) until through }.forEach { track ->
                changed.putIfAbsent(normalize(track.artist) to normalize(track.title), track)
            }
            if (changed.size > budget || total > budget || total + changed.size > maxOf(2, budget)) throw FullRescanNeeded()
            report(status(page, total, changed.values.map { LastFmTrack(it.artist, it.title, 0, it.album, it.mbid) }).copy(reportedPercent = 30 * page / total))
            page++
        }
        val result = old.tracks.associateByTo(linkedMapOf()) { it.key }
        val byName = old.tracks.groupBy { normalize(it.artist) to normalize(it.title) }
        changed.values.forEachIndexed { index, track ->
            val possible = byName[normalize(track.artist) to normalize(track.title)].orEmpty()
            val compatible = possible.filter { it.mbid.isNullOrBlank() || track.mbid.isNullOrBlank() || normalize(it.mbid) == normalize(track.mbid) }
            if (compatible.size > 1 || possible.isNotEmpty() && compatible.isEmpty()) throw FullRescanNeeded()
            val previous = compatible.singleOrNull()
            val wanted = previous ?: LastFmTrack(track.artist, track.title, 0, track.album, track.mbid)
            val updated = try { pacer.call { api.info(username, wanted) } }
                catch (failure: LastFmFailure) { if (failure.needsFullRescan) throw FullRescanNeeded() else throw failure }
            result[wanted.key] = wanted.copy(plays = updated.plays, durationMs = updated.durationMs ?: wanted.durationMs)
            report(status(index + 1, changed.size, changed.values.take(index + 1).map {
                LastFmTrack(it.artist, it.title, 0, it.album, it.mbid) }).copy(reportedPercent = 30 + 70 * (index + 1) / changed.size))
        }
        if (changed.isEmpty()) report(LastFmProgress(1, 1))
        return result.values.toList()
    }
    private fun status(page: Int, total: Int, tracks: Collection<LastFmTrack>): LastFmProgress = LastFmProgress(page, total, tracks.size,
        tracks.map { LastFmNames.name(it.artist, "").artist }.distinct().size,
        tracks.filter { !it.album.isNullOrBlank() }.map { LastFmNames.name(it.artist, "").artist to LastFmNames.album(it.album) }.distinct().size)
}
