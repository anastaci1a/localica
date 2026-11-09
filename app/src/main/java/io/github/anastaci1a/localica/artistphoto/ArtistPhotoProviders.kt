package io.github.anastaci1a.localica.artistphoto

import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder

/** Limited first-page searches: no unbounded pagination or provider URLs accepted from library tags. */
internal class ArtistPhotoProviders(private val http: PhotoTransport) {
    private val mbid = Regex("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")
    private suspend fun json(url: String) = JSONObject(String(http.get(url, 512 * 1024), Charsets.UTF_8))
    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")
    private fun JSONArray.objects(limit: Int) = (0 until minOf(length(), limit)).mapNotNull { optJSONObject(it) }
    private fun JSONObject.text(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() && it.length <= 2048 }

    suspend fun search(provider: ArtistPhotoProvider, local: ArtistPhotoRequest,
        onCandidate: suspend (RemoteArtist) -> Unit = {}): List<RemoteArtist> {
        if (local.name.isBlank() || local.name.length > 256) return emptyList()
        return when (provider) {
            ArtistPhotoProvider.DEEZER -> {
                val entries = json("https://api.deezer.com/search/artist?q=${enc(local.name)}&limit=5").optJSONArray("data") ?: JSONArray()
                entries.objects(5).mapNotNull { entry ->
                    val id = entry.optLong("id", -1).takeIf { it > 0 } ?: return@mapNotNull null
                    val name = entry.text("name") ?: return@mapNotNull null
                    if (ArtistPhotoMatching.normalize(name) != ArtistPhotoMatching.normalize(local.name)) return@mapNotNull null
                    safely { deezer(entry, id.toString(), name).also { onCandidate(it) } }
                }
            }
            ArtistPhotoProvider.WIKIMEDIA -> {
                // Escape Lucene syntax; an exact artist clause avoids user text becoming a query.
                val escaped = local.name.replace(Regex("([+\\-!(){}\\[\\]^\"~*?:\\\\/|&])"), "\\\\$1")
                val entries = json("https://musicbrainz.org/ws/2/artist/?query=${enc("artist:\"$escaped\"")}&fmt=json&limit=5")
                    .optJSONArray("artists") ?: JSONArray()
                entries.objects(5).mapNotNull { entry ->
                    val id = entry.text("id")?.takeIf(mbid::matches) ?: return@mapNotNull null
                    val name = entry.text("name") ?: return@mapNotNull null
                    if (ArtistPhotoMatching.normalize(name) != ArtistPhotoMatching.normalize(local.name)) return@mapNotNull null
                    safely { musicbrainz(id, name).also { onCandidate(it) } }
                }
            }
        }
    }

    suspend fun resolve(choice: ArtistPhotoChoice): RemoteArtist? = safely {
        when (choice.provider) {
            ArtistPhotoProvider.DEEZER -> {
                if (!choice.id.matches(Regex("[1-9][0-9]{0,18}"))) return@safely null
                val entry = json("https://api.deezer.com/artist/${choice.id}")
                if (entry.optLong("id").toString() != choice.id) return@safely null
                deezer(entry, choice.id, entry.text("name") ?: return@safely null)
            }
            ArtistPhotoProvider.WIKIMEDIA -> {
                if (!mbid.matches(choice.id)) return@safely null
                val entry = json("https://musicbrainz.org/ws/2/artist/${choice.id}?inc=url-rels&fmt=json")
                if (entry.text("id") != choice.id) return@safely null
                val name = entry.text("name") ?: return@safely null
                val artist = musicbrainz(choice.id, name)
                val qid = entry.optJSONArray("relations")?.objects(30)?.firstNotNullOfOrNull { relation ->
                    if (relation.text("type") != "wikidata") null else relation.optJSONObject("url")?.text("resource")?.let { url ->
                        runCatching { URI(url).takeIf { it.scheme == "https" && it.host == "www.wikidata.org" && it.userInfo == null && it.port == -1 }
                            ?.path?.substringAfterLast('/')?.takeIf { it.matches(Regex("Q[1-9][0-9]{0,12}")) } }.getOrNull()
                    }
                } ?: return@safely artist
                val claims = json("https://www.wikidata.org/wiki/Special:EntityData/$qid.json")
                    .optJSONObject("entities")?.optJSONObject(qid)?.optJSONObject("claims")?.optJSONArray("P18")
                val title = claims?.objects(10)?.filter { it.optString("rank") != "deprecated" }?.firstNotNullOfOrNull {
                    it.optJSONObject("mainsnak")?.optJSONObject("datavalue")?.text("value")
                } ?: return@safely artist
                val commons = json("https://commons.wikimedia.org/w/api.php?action=query&format=json&prop=imageinfo&iiprop=url%7Cextmetadata&iiurlwidth=1000&titles=${enc("File:$title")}")
                    .optJSONObject("query")?.optJSONObject("pages") ?: return@safely artist
                val info = commons.keys().asSequence().take(2).firstNotNullOfOrNull {
                    commons.optJSONObject(it)?.optJSONArray("imageinfo")?.optJSONObject(0)
                } ?: return@safely artist
                val meta = info.optJSONObject("extmetadata") ?: return@safely artist
                fun credit(key: String) = meta.optJSONObject(key)?.text("value")?.let(::plainPhotoText)
                val licence = credit("LicenseShortName") ?: return@safely artist
                val by = credit("Artist") ?: return@safely artist
                val licenceUrl = meta.optJSONObject("LicenseUrl")?.text("value")?.takeIf { value ->
                    runCatching { URI(value).let { it.scheme == "https" && it.host in setOf("creativecommons.org", "www.gnu.org") && it.userInfo == null } }.getOrDefault(false)
                } ?: return@safely artist
                val source = info.text("descriptionurl")?.takeIf { value -> runCatching { URI(value).host == "commons.wikimedia.org" && safePhotoUrl(value) }.getOrDefault(false) }
                    ?: return@safely artist
                val image = (info.text("thumburl") ?: info.text("url"))?.takeIf { safePhotoUrl(it) && URI(it).host == "upload.wikimedia.org" }
                RemoteArtist(choice, name, artist.releases, image, PhotoAttribution(by, credit("Credit").orEmpty(), licence, licenceUrl, source))
            }
        }
    }

    private suspend fun deezer(entry: JSONObject, id: String, name: String): RemoteArtist {
        val releases = json("https://api.deezer.com/artist/$id/top?limit=25").optJSONArray("data")?.objects(25)
            ?.mapNotNull { it.optJSONObject("album")?.text("title") }?.distinct().orEmpty()
        val image = if (entry.has("md5_image") && entry.text("md5_image") == null) null else
            sequenceOf(entry.text("picture_xl"), entry.text("picture_big")).filterNotNull().firstOrNull {
                safePhotoUrl(it) && !URI(it).path.orEmpty().contains("/artist//") &&
                    URI(it).host in setOf("cdn-images.dzcdn.net", "cdn-images.deezer.com", "e-cdns-images.dzcdn.net")
            }
        return RemoteArtist(ArtistPhotoChoice(ArtistPhotoProvider.DEEZER, id), name, releases, image)
    }
    private suspend fun musicbrainz(id: String, name: String): RemoteArtist {
        val releases = json("https://musicbrainz.org/ws/2/release-group?artist=$id&fmt=json&limit=25")
            .optJSONArray("release-groups")?.objects(25)?.mapNotNull { it.text("title") }.orEmpty()
        return RemoteArtist(ArtistPhotoChoice(ArtistPhotoProvider.WIKIMEDIA, id), name, releases)
    }
}

internal suspend fun <T> safely(block: suspend () -> T): T? = try { block() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { null }

/** Provider HTML is never displayed or logged; retain bounded attribution text only. */
internal fun plainPhotoText(value: String): String? = value.replace(Regex("<[^>]*>"), " ")
    .replace(Regex("https?://\\S+"), " ").replace(Regex("\\s+"), " ").trim().take(512).takeIf(String::isNotEmpty)
