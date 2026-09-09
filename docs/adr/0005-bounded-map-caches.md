# 5. Map caches are LRU-bounded, not unbounded

## Context

The map layer caches roads, intersections, OpenLR lines and OpenLR nodes by
identifier. They were plain `ConcurrentHashMap`s, and `cache_size` was passed as
initial capacity — nothing evicted anything.

Measured on the 4.7M-road Netherlands map: decoding 2,173 references retained
213 MiB, and decoding the same references again added 1 MiB. Growth tracked the
amount of distinct geography touched and never came back. A feed decoding across a
whole country converges on caching the entire map.

This was masked for a long time by a separate defect: the container health check
probed `POST /api/v1/cache/clear`, emptying every cache every 30 seconds. Fixing that
removed the only thing bounding memory.

## Decision

All four caches are size-bounded LRU (`LruCache`), limited by `cache_size` — the line
cache at twice that, since a two-way road is two lines. `GET /api/v1/cache/stats`
reports occupancy, bound, hits, misses and evictions.

`LruCache` deliberately offers no atomic `getOrPut`. Callers read, and on a miss
compute the value *outside* the lock before inserting, because computing means
querying PostgreSQL and holding a lock across that would serialise every request.
Two threads may compute the same entry concurrently, which is harmless.

## Consequences

Memory is bounded by configuration rather than by how much of the map has been
touched. Verified with a deliberately small bound: occupancy pinned at the limit,
740k evictions, memory flat across four full passes, and the decode success rate
unchanged at 94.8% — eviction costs no correctness, because every cache is a pure
lookup on an immutable key and a miss simply re-reads.

Eviction can cost throughput when the working set exceeds the bound: at an
artificially small 5,000 entries the road cache thrashed to a 29% hit rate. Sizing
is now an operator's decision, which is why the statistics endpoint exists.

Rejected: Caffeine. It offers better concurrency and richer metrics, but this needs
roughly forty lines against a `LinkedHashMap` and adding a dependency to a service
whose cache access is not yet shown to be contended is premature.
