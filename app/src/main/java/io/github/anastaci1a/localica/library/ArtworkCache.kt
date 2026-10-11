package io.github.anastaci1a.localica.library

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Embedded covers are one provider; custom playlist covers can be supplied without changing rows. */
fun interface CoverProvider {
    suspend fun load(context: Context, uris: List<String>, edge: Int): Bitmap?
}

object EmbeddedCoverProvider : CoverProvider {
    override suspend fun load(context: Context, uris: List<String>, edge: Int): Bitmap? {
        for (uri in uris) ArtworkCache.read(context, uri, edge)?.let { return it }
        return null
    }
}

/** Serial, cancellable loads prevent fast scrolling from flooding metadata readers. Nulls are cached too. */
object ArtworkCache {
    private data class Key(val uri: String, val edge: Int, val revision: Long)
    private data class Cover(val bitmap: Bitmap?)
    private val cache = object : LruCache<Key, Cover>(12 * 1024 * 1024) {
        override fun sizeOf(key: Key, value: Cover) = (value.bitmap?.allocationByteCount ?: 0) + key.uri.length * 2 + 128
    }
    private val gate = Mutex()
    private val mutableRevision = MutableStateFlow(0L)
    val revision = mutableRevision.asStateFlow()
    data class CachedArtwork(val bitmap: Bitmap?)

    /** A memory-only first frame, including known missing art; never starts a reader. */
    fun peek(uris: List<String>, pixels: Int = 0): CachedArtwork? = synchronized(cache) {
        for (uri in uris) {
            val sizes = if (pixels <= 0) listOf(128, 384, 768) else
                listOf(128, 384, 768).filter { it >= bucket(pixels) } + listOf(768, 384, 128).filter { it < bucket(pixels) }
            val cover = sizes.firstNotNullOfOrNull { cache.get(Key(uri, it, revision.value)) } ?: return@synchronized null
            if (cover.bitmap != null) return@synchronized CachedArtwork(cover.bitmap)
        }
        CachedArtwork(null)
    }
    fun bucket(pixels: Int) = when { pixels <= 128 -> 128; pixels <= 384 -> 384; else -> 768 }
    fun invalidate() = synchronized(cache) { cache.evictAll(); mutableRevision.value++ }
    suspend fun read(context: Context, uri: String, pixels: Int): Bitmap? = gate.withLock {
        val key = Key(uri, bucket(pixels), revision.value)
        cache.get(key)?.let { return@withLock it.bitmap }
        val bitmap = ArtworkReader(context.applicationContext).read(uri, key.edge)
        synchronized(cache) { if (key.revision == revision.value) cache.put(key, Cover(bitmap)) }
        bitmap
    }
}
