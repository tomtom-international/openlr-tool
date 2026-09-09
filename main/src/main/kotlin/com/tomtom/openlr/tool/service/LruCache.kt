package com.tomtom.openlr.tool.service

import java.util.concurrent.atomic.AtomicLong

/**
 * A size-bounded, least-recently-used cache.
 *
 * The map caches used to be plain `ConcurrentHashMap`s with no eviction, and
 * `cache_size` was passed as initial capacity rather than a bound. Nothing limited
 * them: memory grew with the amount of distinct geography decoded and never came
 * back. Measured on a 4.7M-road network, 2,173 location references cost 213 MiB of
 * permanently retained cache, and re-decoding the same references added 1 MiB --
 * the signature of unbounded growth rather than warm-up.
 *
 * Eviction is safe here because every cache is a pure lookup keyed by an immutable
 * identifier: a miss re-reads from the database and produces an equal value.
 *
 * Deliberately does not offer an atomic `getOrPut`. Callers [get], and on a miss
 * compute the value *outside* any lock before calling [put], because computing means
 * querying PostgreSQL and holding a lock across that would serialise every request.
 * Two threads may therefore compute the same entry concurrently, which is harmless.
 */
class LruCache<K, V>(val maxSize: Int) {

    init {
        require(maxSize > 0) { "cache size must be positive, got $maxSize" }
    }

    private val hitCount = AtomicLong()
    private val missCount = AtomicLong()
    private val evictionCount = AtomicLong()

    private val map = object : LinkedHashMap<K, V>(INITIAL_CAPACITY, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>): Boolean {
            val evict = size > this@LruCache.maxSize
            if (evict) evictionCount.incrementAndGet()
            return evict
        }
    }

    /** Access order is part of the state, so reads mutate and must be synchronised. */
    fun get(key: K): V? {
        val value = synchronized(map) { map[key] }
        if (value == null) missCount.incrementAndGet() else hitCount.incrementAndGet()
        return value
    }

    fun put(key: K, value: V) {
        synchronized(map) { map[key] = value }
    }

    fun clear() {
        synchronized(map) { map.clear() }
    }

    val size: Int
        get() = synchronized(map) { map.size }

    fun stats(): Stats = Stats(
        size = size,
        maxSize = maxSize,
        hits = hitCount.get(),
        misses = missCount.get(),
        evictions = evictionCount.get()
    )

    data class Stats(
        val size: Int,
        val maxSize: Int,
        val hits: Long,
        val misses: Long,
        val evictions: Long
    ) {
        /** Fraction of lookups served from the cache, or `null` before any lookup. */
        val hitRate: Double?
            get() = (hits + misses).takeIf { it > 0 }?.let { hits.toDouble() / it }
    }

    private companion object {
        const val INITIAL_CAPACITY = 1024
        const val LOAD_FACTOR = 0.75f
    }
}
