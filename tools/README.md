# Map conversion tools

Turns a road network into CSVs for this repo's `local.roads` /
`local.intersections` schema.

| File | Role |
|------|------|
| `orbis_to_pg_csv.py` + `.sql` | Orbis / OSM PBF, via `osm_tag_mapper` |
| `mnr_to_pg_csv.py` + `.sql` | MultiNet-R distribution |
| `verify_api.py` | Live verification harness for a running deployment |
| `dbsetup.sh` | Loads either converter's CSVs via `./dc setup` |

## Running them

Every script is a self-contained [uv](https://docs.astral.sh/uv/) script: the
dependency declaration is inline, so `uv run` fetches what it needs on first use
and there is nothing to install or activate.

```sh
uv run tools/orbis_to_pg_csv.py --help
uv run tools/mnr_to_pg_csv.py --help
uv run tools/verify_api.py --help
```

The transformations are DuckDB SQL, executed through the DuckDB Python package, so
no `duckdb` CLI is required and the drivers work the same on macOS, Linux and
Windows. `dbsetup.sh` is the exception and is deliberately shell: it runs
*inside* the Linux `db-setup` container, not on your machine.

## Orbis input only

This converter assumes each input row is one junction-to-junction link, so a
way's endpoints are its junctions. **Orbis satisfies that; a plain OSM/Geofabrik
PBF does not.** In a Geofabrik extract about a quarter of highway ways run
*through* a junction mid-way (64,240 of 250,000 sampled, 117,030 shared interior
nodes), and since `osm_tag_mapper` emits one row per way without splitting, those
junctions would simply be absent — the network would be disconnected at every one
of them and decoding would fail or mis-match. Supporting Geofabrik means adding
node-degree splitting upstream first. Don't feed one through this script meanwhile.

`osm_tag_mapper` itself handles both formats fine; it is only this schema mapping
that is Orbis-specific.

## Usage

```sh
# Stage 1 - Orbis PBF to OpenLR attributes (separate repo)
uv run python -m osm_tag_mapper --input nld-orbis.pbf --output /tmp/nld.csv

# Stage 2 - attributes to this repo's schema
uv run tools/orbis_to_pg_csv.py -i /tmp/nld.csv -o ~/orbis-nld

# Load
cp tools/dbsetup.sh ~/orbis-nld/dbsetup.sh
cd docker && ./dc setup ~/orbis-nld
```

Stage 1 must be recent enough to emit `way_id`; the converter refuses a CSV
without it rather than falling back to a synthetic key.

`-m, --meta` picks what `roads.meta` carries; the default `way` (the Orbis way id)
is the only value guaranteed unique, which the API requires. See below.

Needs only the `duckdb` CLI. The Netherlands set — 4,717,150 rows — converts in
about 6 seconds. DuckDB will take whatever memory is available; cap it by adding
`SET memory_limit = '2GB';` to the SQL and it spills to disk instead.

The loader drops indexes, `COPY`s both files, rebuilds indexes, `ANALYZE`s, then
reports row counts, the `flowdir` distribution, and any road whose endpoints do
not resolve to an intersection.

### Validating a change to the SQL

The Python implementation exists for exactly this. It is independent — shapely
and pyproj rather than DuckDB spatial — so agreement is real evidence. Both were
verified identical on the full Netherlands file: 4,717,149 roads and 4,329,259
intersections, zero rows differing in either direction. **If the two disagree, the
SQL is authoritative and the Python needs updating**, not the other way round.

## Conversion notes

Four things here are easy to get wrong.

**DuckDB's spheroid functions default to (latitude, longitude).** The source WKB is
(lon, lat). Left uncorrected, `ST_Length_Spheroid` returns lengths about 40% long
at Dutch latitudes, with an error that varies by latitude — and `len` feeds
OpenLR's distance-to-next-point tolerance, so it would quietly wreck decoding.
The SQL sets `geometry_always_xy = true` once, up front; verified against
`pyproj.Geod` on a real segment (72.62777 m both ways).

**`flowdir` is not `oneway`.** Both use 1/2/3, and 2 and 3 mean opposite things:

| | 1 | 2 | 3 |
|---|---|---|---|
| `osm_tag_mapper.oneway` | bidirectional | forward (with digitisation) | reverse (against) |
| `local.roads.flowdir` | two-way | one-way **against** digitisation | one-way **with** it |

So the mapping is `1→1, 2→3, 3→2`, and geometry is never reversed — digitisation
stays as the source had it, so `meta` still refers to the same directed segment.
An unrecognised `oneway` produces NULL and the row is rejected and counted, rather
than defaulting to bidirectional.

**`id` is `way_id`, the Orbis way id.** `osm_id` cannot be the key: Orbis splits
ways at junctions and reuses one `osm_identifier` across the pieces — 313,623
distinct `osm_id` values across 739,467 New Zealand rows, one repeated 28 times in
a Netherlands sample. `way_id` is the id the PBF itself uses, and it is unique:
739,467 distinct values in 739,467 rows, none NULL. Values stay in the hundreds of
millions to low billions, so negating one — which the API does to mean reverse
traversal — is safe.

**`meta` is the Orbis way id, and it is the identifier the API uses.** Decoding
returns it as `properties.meta`, and `/api/v1/encode` resolves its `path` against
the same column, so a decoded path re-encodes without translation. `local.roads.id`
is an opaque internal key -- the OpenLR library needs a `Long` per line -- and never
appears in a response.

That makes `meta` uniqueness a hard requirement, not a preference: a repeated value
leaves those segments unencodable, and `local.roads` carries a `UNIQUE` index on the
column to enforce it. The Orbis way id satisfies it — 739,455 distinct values across
739,455 New Zealand roads, no spaces, no leading hyphen (which would be read as a
direction prefix).

`-m` selects a different value where that suits a consumer better, but only `way`
is guaranteed unique:

| `--meta` | Content | Unique? |
|---|---|---|
| `way` (default) | Orbis way id | **Yes** — 739,455 / 739,455 |
| `gers` | GERS entity reference, falling back to `osm_id` | No — 341,976 distinct across 739,467 ways |
| `osm` | Original OSM way id | No — 313,623 distinct; one value covers 28 rows |
| `both` | `gers_id\|osm_id` | No — 421,952 distinct, worst case 66 rows per value |

Anything but `way` will be rejected by the unique index at load time.

**Junction IDs are packed coordinates**, not a sequence:

```
id = (round(lon*1e7) + 1_800_000_000) * 1_800_000_001 + (round(lat*1e7) + 900_000_000)
```

Max around 6.48e18, inside signed 64-bit. Two regions converted separately
therefore agree on a shared boundary node, which a counter could not guarantee.
`intersections.meta` keeps the readable `E…N…` form for tracing.

## Verifying a deployment

`verify_api.py` exercises what unit tests cannot: real decoding against a real map,
response shapes, the error contract, and the decode/encode round trip. Standard
library only.

```sh
uv run tools/verify_api.py                        # all non-disruptive checks
uv run tools/verify_api.py --sample 1000          # larger decode corpus
uv run tools/verify_api.py --codes codes.openlrs  # your own corpus
uv run tools/verify_api.py --only decode,roads    # a subset
uv run tools/verify_api.py --disruptive           # also pauses the database
```

Each check prints PASS/FAIL and the exit code is non-zero if any failed, so it works
in CI. `--disruptive` pauses and unpauses `openlr-postgres` to confirm a dependency
failure returns 503 rather than 400, so run it only where that is acceptable.

Baseline on the Netherlands Orbis map (4,717,149 roads / 4,329,259 intersections):
20 checks pass, 95.6% of 1,000 codes decode with `default`, 96.7% with fallback.
