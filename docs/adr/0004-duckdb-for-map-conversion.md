# 4. Map conversion in DuckDB SQL, not Python

## Context

Converting an Orbis network into this schema means reading a multi-million-row CSV,
deriving junction identifiers, computing geodesic lengths, mapping attribute
enumerations, and writing two CSVs for `COPY`.

A Python implementation using shapely and pyproj existed first and worked. The
Netherlands network is 4.7M rows.

## Decision

DuckDB SQL, driven by a thin shell wrapper. The Python implementation was kept only
while the SQL was validated against it, then removed.

## Consequences

26x faster on the full file — 5.9 s against 152 s — and the only dependency is the
`duckdb` binary rather than a virtualenv with shapely and pyproj. Memory is bounded
by DuckDB's buffer manager and spills to disk, where the Python version held junction
and identifier maps in dictionaries that grew with the input and would not survive a
continent-sized extract.

The two implementations were confirmed byte-identical on all 4.7M rows, in both
directions, before the Python one was deleted. That cross-check is what caught
DuckDB's spheroid functions defaulting to (latitude, longitude) axis order: without
`geometry_always_xy`, `ST_Length_Spheroid` returned lengths about 40% long at Dutch
latitudes, an error that varies with latitude and would have quietly corrupted every
`len` value.

The cost is losing that independent oracle. A future change to the SQL has only its
own tests to answer to, so the conversion notes in `tools/README.md` carry the
reasoning that the second implementation used to check.
