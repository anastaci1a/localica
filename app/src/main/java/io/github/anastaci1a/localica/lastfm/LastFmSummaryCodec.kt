package io.github.anastaci1a.localica.lastfm

import org.json.JSONObject

/** A small, versioned, disposable cache. Album availability is separate from its count. */
internal object LastFmSummaryCodec {
    fun encode(summary: LastFmSettingsSummary): String = JSONObject().apply {
        put("version", 1); put("username", summary.username); put("syncedAt", summary.syncedAt)
        put("stats", JSONObject().apply {
            val stats = summary.stats
            put("entries", stats.entries); put("scrobbles", stats.scrobbles); put("songs", stats.songs)
            put("artists", stats.artists); put("albums", stats.albums); put("entriesWithAlbums", stats.entriesWithAlbums)
        })
        summary.counts?.let { counts -> put("counts", JSONObject().apply {
            put("matched", counts.matched); put("clear", counts.clear); put("judgments", counts.judgments)
            put("review", counts.review); put("absent", counts.absent); put("editions", counts.editions)
        }) }
    }.toString()

    fun decode(text: String): LastFmSettingsSummary = JSONObject(text).run {
        require(getInt("version") == 1)
        val stats = getJSONObject("stats").run {
            LastFmAccountStats(getInt("entries"), getLong("scrobbles"), getInt("songs"), getInt("artists"),
                getInt("albums"), getInt("entriesWithAlbums")).also {
                require(it.entries >= 0 && it.scrobbles >= 0 && it.songs >= 0 && it.artists >= 0 && it.albums >= 0 && it.entriesWithAlbums in 0..it.entries)
            }
        }
        val counts = optJSONObject("counts")?.run {
            LastFmDashboardCounts(getInt("matched"), getInt("clear"), getInt("judgments"), getInt("review"), getInt("absent"), getInt("editions"))
                .also { require(listOf(it.matched, it.clear, it.judgments, it.review, it.absent, it.editions).all { count -> count >= 0 }) }
        }
        LastFmSettingsSummary(getString("username"), getLong("syncedAt"), stats, counts)
    }
}
