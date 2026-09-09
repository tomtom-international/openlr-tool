package com.tomtom.openlr.tool.service

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LruCacheTest {

    @Test
    fun `entries can be stored and read back`() {
        val cache = LruCache<Long, String>(10)
        cache.put(1L, "one")
        assertEquals("one", cache.get(1L))
        assertNull(cache.get(2L))
    }

    @Test
    fun `size never exceeds the bound`() {
        val cache = LruCache<Int, Int>(100)
        for (i in 1..10_000) cache.put(i, i)
        assertEquals(100, cache.size)
        assertEquals(9_900, cache.stats().evictions)
    }

    @Test
    fun `the least recently used entry is evicted first`() {
        val cache = LruCache<String, Int>(3)
        cache.put("a", 1); cache.put("b", 2); cache.put("c", 3)

        cache.get("a")          // promotes "a", making "b" the eldest
        cache.put("d", 4)       // evicts "b"

        assertEquals(1, cache.get("a"))
        assertNull(cache.get("b"))
        assertEquals(3, cache.get("c"))
        assertEquals(4, cache.get("d"))
    }

    @Test
    fun `re-putting an existing key does not grow the cache`() {
        val cache = LruCache<String, Int>(2)
        cache.put("a", 1)
        cache.put("a", 2)
        assertEquals(1, cache.size)
        assertEquals(2, cache.get("a"))
    }

    @Test
    fun `clear empties the cache but keeps the bound`() {
        val cache = LruCache<Int, Int>(5)
        for (i in 1..5) cache.put(i, i)
        cache.clear()
        assertEquals(0, cache.size)
        assertEquals(5, cache.maxSize)
        assertNull(cache.get(1))
    }

    @Test
    fun `stats count hits, misses and evictions`() {
        val cache = LruCache<Int, Int>(2)
        cache.put(1, 1)
        cache.get(1)            // hit
        cache.get(99)           // miss
        cache.put(2, 2)
        cache.put(3, 3)         // evicts one

        val stats = cache.stats()
        assertEquals(1L, stats.hits)
        assertEquals(1L, stats.misses)
        assertEquals(1L, stats.evictions)
        assertEquals(2, stats.size)
        assertEquals(0.5, stats.hitRate!!, 1e-9)
    }

    @Test
    fun `hit rate is null before any lookup`() {
        assertNull(LruCache<Int, Int>(4).stats().hitRate)
    }

    @Test
    fun `a non-positive bound is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { LruCache<Int, Int>(0) }
        assertThrows(IllegalArgumentException::class.java) { LruCache<Int, Int>(-1) }
    }

    @Test
    fun `concurrent writers do not corrupt the cache or breach the bound`() {
        // LinkedHashMap in access order is not thread safe; this asserts the
        // synchronisation actually holds under contention.
        val cache = LruCache<Int, Int>(500)
        val threads = 8
        val perThread = 5_000
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)

        val futures = (0 until threads).map { t ->
            pool.submit {
                start.await()
                for (i in 0 until perThread) {
                    val key = t * perThread + i
                    cache.put(key, key)
                    cache.get(key)
                }
            }
        }
        start.countDown()
        futures.forEach { it.get(60, TimeUnit.SECONDS) }
        pool.shutdown()

        assertTrue(cache.size <= 500, "size was ${cache.size}")
        assertEquals(threads * perThread, (cache.stats().hits + cache.stats().misses).toInt())
    }
}
