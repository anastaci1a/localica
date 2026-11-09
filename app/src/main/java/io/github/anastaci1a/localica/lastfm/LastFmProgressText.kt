package io.github.anastaci1a.localica.lastfm

import android.content.res.Resources
import io.github.anastaci1a.localica.R

internal fun LastFmProgress.countSummary(resources: Resources): String = listOf(
    resources.getQuantityString(R.plurals.lastfm_song_count, songs, songs),
    resources.getQuantityString(R.plurals.lastfm_artist_count, artists, artists),
    resources.getQuantityString(R.plurals.lastfm_album_count, albums, albums)
).joinToString(" · ")
