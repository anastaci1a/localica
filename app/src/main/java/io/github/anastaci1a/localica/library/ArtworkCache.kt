package io.github.anastaci1a.localica.library

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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

/** Decoded-cover budget: an eighth of the app heap class, bounded so low-memory devices keep a
 * useful working set and large heaps do not hold unbounded bitmaps. */
internal fun artworkCacheBytes(memoryClassMb: Int): Int =
    (memoryClassMb.toLong() * 1024 * 1024 / 8).coerceIn(MIN_ARTWORK_CACHE_BYTES.toLong(), MAX_ARTWORK_CACHE_BYTES.toLong()).toInt()
internal const val MIN_ARTWORK_CACHE_BYTES = 12 * 1024 * 1024
internal const val MAX_ARTWORK_CACHE_BYTES = 48 * 1024 * 1024
/** Decode edges, ascending. Intermediate buckets keep 5-column tiles from rounding up to 384. */
internal val ARTWORK_BUCKETS = intArrayOf(128, 192, 256, 384, 768)
internal fun artworkBucket(pixels: Int): Int = ARTWORK_BUCKETS.firstOrNull { pixels <= it } ?: ARTWORK_BUCKETS.last()
internal const val ARTWORK_DECODE_PARALLELISM = 3

/** Warm hits do not queue behind unrelated decodes; cold loads are cancellable and at most
 * [ARTWORK_DECODE_PARALLELISM] run at once. */
object ArtworkCache {
    private val store = ArtworkCacheStore { context, uri, edge ->
        ArtworkReader(context.applicationContext).read(uri, edge)
    }
    private val configured = java.util.concurrent.atomic.AtomicBoolean(false)
    val revision = store.revision
    data class CachedArtwork(val bitmap: Bitmap?, val edge: Int = 0)
    /** Size the cache from the device heap class once; safe to call from any composition. */
    fun configure(context: Context) {
        if (!configured.compareAndSet(false, true)) return
        val manager = context.applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        manager?.let { store.resize(artworkCacheBytes(it.memoryClass)) }
    }
    fun peek(uris: List<String>, pixels: Int = 0): CachedArtwork? = store.peek(uris, pixels)
    fun bucket(pixels: Int) = store.bucket(pixels)
    fun invalidate() = store.invalidate()
    suspend fun read(context: Context, uri: String, pixels: Int): Bitmap? { configure(context); return store.read(context, uri, pixels) }
}

/** The production store also permits isolated deterministic loader contracts. */
internal class ArtworkCacheStore(parallelism: Int = ARTWORK_DECODE_PARALLELISM,
    maxBytes: Int = MIN_ARTWORK_CACHE_BYTES, private val load: suspend (Context, String, Int) -> Bitmap?) {
    private data class Key(val uri: String, val edge: Int, val revision: Long)
    private data class Cover(val bitmap: Bitmap?)
    private class KeyLock { val mutex = Mutex(); var users = 0 }
    private val cache = object : LruCache<Key, Cover>(maxBytes) {
        override fun sizeOf(key: Key, value: Cover) = (value.bitmap?.allocationByteCount ?: 0) + key.uri.length * 2 + 128
    }
    /** Bounds concurrent decodes; duplicates of one key queue on [locks] first and then hit the cache. */
    private val permits = Semaphore(parallelism.coerceAtLeast(1))
    private val locks = HashMap<Key, KeyLock>()
    private val mutableRevision = MutableStateFlow(0L)
    val revision = mutableRevision.asStateFlow()

    val maxBytes get() = synchronized(cache) { cache.maxSize() }
    fun resize(bytes: Int) { synchronized(cache) { cache.resize(bytes.coerceAtLeast(1)) } }

    /** A memory-only first frame, including known missing art; never starts a reader. */
    fun peek(uris: List<String>, pixels: Int = 0): ArtworkCache.CachedArtwork? = synchronized(cache) {
        for (uri in uris) {
            val sizes = if (pixels <= 0) ARTWORK_BUCKETS.toList() else {
                val wanted = bucket(pixels)
                ARTWORK_BUCKETS.filter { it >= wanted } + ARTWORK_BUCKETS.reversed().filter { it < wanted }
            }
            val cover = sizes.firstNotNullOfOrNull { edge -> cache.get(Key(uri, edge, revision.value))?.let { ArtworkCache.CachedArtwork(it.bitmap, edge) } }
                ?: return@synchronized null
            if (cover.bitmap != null) return@synchronized cover
        }
        ArtworkCache.CachedArtwork(null)
    }
    fun bucket(pixels: Int) = artworkBucket(pixels)
    fun invalidate() = synchronized(cache) { cache.evictAll(); mutableRevision.value++ }

    private fun cached(key: Key): Cover? = synchronized(cache) { cache.get(key) }
    suspend fun read(context: Context, uri: String, pixels: Int): Bitmap? {
        currentCoroutineContext().ensureActive()
        cached(Key(uri, bucket(pixels), revision.value))?.let { return it.bitmap }
        val lockKey = Key(uri, bucket(pixels), 0L)
        val lock = synchronized(locks) { locks.getOrPut(lockKey) { KeyLock() }.also { it.users++ } }
        try {
            return lock.mutex.withLock { decode(context, uri, pixels) }
        } finally {
            synchronized(locks) { if (--lock.users == 0) locks.remove(lockKey) }
        }
    }
    private suspend fun decode(context: Context, uri: String, pixels: Int): Bitmap? {
        currentCoroutineContext().ensureActive()
        cached(Key(uri, bucket(pixels), revision.value))?.let { return it.bitmap }
        return permits.withPermit {
            currentCoroutineContext().ensureActive()
            val key = Key(uri, bucket(pixels), revision.value)
            cached(key)?.let { return@withPermit it.bitmap }
            val bitmap = load(context, uri, key.edge)
            synchronized(cache) { if (key.revision == revision.value) cache.put(key, Cover(bitmap)) }
            bitmap
        }
    }
}
