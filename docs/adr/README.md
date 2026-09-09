# Architecture decision records

One file per decision, `NNNN-short-title.md`, newest last. A record states the
context, the decision, and what it costs — including the options rejected, since
that is the part nobody can reconstruct later.

Write one when a choice would otherwise only be visible as a line of code that looks
arbitrary: an identifier convention, a schema constraint, a dependency, an interface
boundary. Do not write one for an implementation detail a reader can simply read.

| # | Decision |
|---|----------|
| [0001](0001-two-table-minimal-schema.md) | A minimal two-table schema, not a routing-grade map |
| [0002](0002-flowdir-encoding.md) | `flowdir` uses 1/2/3 and reserves everything else |
| [0003](0003-meta-is-the-public-identifier.md) | `meta` is the public identifier; `id` is opaque |
| [0004](0004-duckdb-for-map-conversion.md) | Map conversion in DuckDB SQL, not Python |
| [0005](0005-bounded-map-caches.md) | Map caches are LRU-bounded, not unbounded |
