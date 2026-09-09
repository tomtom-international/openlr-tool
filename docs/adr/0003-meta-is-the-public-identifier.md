# 3. `meta` is the public identifier; `id` is opaque

## Context

The OpenLR library requires a `Long` per line and negates it to express reverse
traversal, so the schema needs an integer primary key. But callers think in terms of
their own network's identifiers, which are often not integers — GERS references are
128-bit, others are UUIDs.

`local.roads.id` cannot be the source identifier in general. For Orbis specifically
it cannot be the OSM way id either: Orbis splits ways at junctions and reuses one
`osm_identifier` across the pieces, up to 28 rows sharing a value.

The API had this half-built: decoding returned only `meta`, while encoding resolved
its `path` against `id`, and nothing bridged the two. A decoded path could not be
re-encoded.

## Decision

`meta` is the only segment identifier in any response, and `POST /encode` resolves
`path` against it. `id` is an opaque internal key that never appears in a response.

Because encoding resolves by `meta`, it must identify exactly one segment:
`local.roads` carries a `UNIQUE` index on the column, and a value matching several is
rejected rather than resolved arbitrarily. Direction rides on a leading `-`, which
is safe because only a leading hyphen is significant.

## Consequences

Decode output feeds straight back into encode. Callers never see an identifier that
means nothing to them, and the tool imposes no identifier scheme on its users.

The uniqueness requirement constrains what may go in `meta`. For Orbis that means the
Orbis way id, since neither the OSM way id nor the GERS reference is unique — so
those cross-references are not available in `meta` at the same time. A caller needing
both must join on their own side.

Rejected: exposing the signed `id` in decode output. It works, and briefly existed,
but it publishes a key that means nothing outside this database and invites callers
to store it, when it is only stable for as long as the map is not reloaded.
