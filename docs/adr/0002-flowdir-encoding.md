# 2. `flowdir` uses 1/2/3 and reserves everything else

## Context

`local.roads.flowdir` says which of the two possible traversals of a segment exist as
OpenLR lines. Three states are needed: two-way, one-way with the digitised direction,
one-way against it.

The original reader mapped `1`→two-way, `2`→against, `3`→with, and **every other
value to two-way**. Two READMEs documented a different convention entirely
(`0`=both, `1`=forward, `2`=backward), and an integration test carried a third. Data
loaded per the READMEs had its one-way-forward segments read as two-way.

Because unrecognised values defaulted to two-way, a mis-coded segment
*over-permitted* traversal. Nothing failed; the decoder simply became willing to
drive the wrong way up a one-way street.

## Decision

`1` = two-way, `2` = one-way against the digitised direction, `3` = one-way with it.
Every other value is **undefined and reserved**, not a synonym for two-way, so that
giving a value a meaning later cannot silently change how existing data is read.

Enforced in the schema as `NOT NULL CHECK (flowdir IN (1, 2, 3))`. The mapping lives
in exactly one function, `FlowDirection.fromDbValue`, which returns null for anything
undefined. A reader encountering an undefined value in a legacy database logs a
warning naming the road and falls back to two-way — a tolerance for old data, not a
meaning.

## Consequences

New data cannot be wrong in this particular way. Existing databases need the
constraint added by hand, and the migration fails loudly if any row violates it,
which is the point.

`NOT NULL` is a breaking change for loaders that omitted the column: an `ogr2ogr`
run from a shapefile without that attribute now fails at insert rather than writing
NULL. Deliberate — a NULL would pass a bare CHECK and be read as two-way.
