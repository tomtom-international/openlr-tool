#!/bin/bash
# Load converter output into local.roads / local.intersections.
#
# Source-agnostic: it wants a roads.csv and an intersections.csv in its own
# directory, which is what either converter produces. Deliberately shell, because
# it runs inside the Linux db-setup container rather than on your machine.
#
# Usage:
#   1. Generate the CSVs with either converter, e.g.
#        uv run tools/mnr_to_pg_csv.py -i <distribution> -o ~/mnr-eur
#   2. Copy this script alongside them as dbsetup.sh:
#        cp tools/dbsetup.sh ~/mnr-eur/dbsetup.sh
#   3. Load:
#        cd docker && ./dc setup ~/mnr-eur
#
# PGHOST/PGPORT/PGDATABASE/PGUSER/PGPASSWORD are set by the db-setup container.

set -euo pipefail

cd "$(dirname "$0")"

for f in intersections.csv roads.csv; do
    [ -f "$f" ] || { echo "missing $f" >&2; exit 1; }
done

echo "Ensuring schema exists..."
psql -v ON_ERROR_STOP=1 <<'SQL'
CREATE EXTENSION IF NOT EXISTS postgis;
CREATE SCHEMA IF NOT EXISTS local;

CREATE TABLE IF NOT EXISTS local.intersections (
    id BIGINT NOT NULL PRIMARY KEY,
    meta TEXT,
    geom GEOMETRY(Point, 4326)
);

CREATE TABLE IF NOT EXISTS local.roads (
    id BIGINT NOT NULL PRIMARY KEY,
    meta TEXT,
    frc INTEGER,
    fow INTEGER,
    flowdir INTEGER NOT NULL CHECK (flowdir IN (1, 2, 3)),
    from_int BIGINT,
    to_int BIGINT,
    len DOUBLE PRECISION,
    geom GEOMETRY(LineString, 4326)
);
SQL

# Indexes are dropped for the load and rebuilt afterwards -- COPY into an indexed
# table is markedly slower on a country-sized extract.
echo "Dropping indexes..."
psql -v ON_ERROR_STOP=1 <<'SQL'
DROP INDEX IF EXISTS local.local_intersections_geom_idx;
DROP INDEX IF EXISTS local.local_intersections_meta_idx;
DROP INDEX IF EXISTS local.local_roads_geom_idx;
DROP INDEX IF EXISTS local.local_roads_meta_idx;
DROP INDEX IF EXISTS local.local_roads_from_int_idx;
DROP INDEX IF EXISTS local.local_roads_to_int_idx;
SQL

# Intersections first: findRoadsNear inner-joins them, so a road whose endpoints
# are missing is invisible to the decoder.
echo "Loading intersections..."
psql -v ON_ERROR_STOP=1 -c \
    "\\COPY local.intersections (id, meta, geom) FROM 'intersections.csv' WITH (FORMAT csv, HEADER true)"

echo "Loading roads..."
psql -v ON_ERROR_STOP=1 -c \
    "\\COPY local.roads (id, meta, frc, fow, flowdir, from_int, to_int, len, geom) FROM 'roads.csv' WITH (FORMAT csv, HEADER true)"

echo "Rebuilding indexes..."
psql -v ON_ERROR_STOP=1 <<'SQL'
CREATE INDEX local_intersections_geom_idx ON local.intersections USING GIST (geom);
CREATE INDEX local_intersections_meta_idx ON local.intersections USING BTREE (meta);
CREATE INDEX local_roads_geom_idx ON local.roads USING GIST (geom);
-- UNIQUE: /api/v1/encode resolves its path against meta, so it must identify one
-- segment. This fails the load if the converter emitted repeated meta values.
CREATE UNIQUE INDEX local_roads_meta_idx ON local.roads USING BTREE (meta);
CREATE INDEX local_roads_from_int_idx ON local.roads USING BTREE (from_int);
CREATE INDEX local_roads_to_int_idx ON local.roads USING BTREE (to_int);
ANALYZE local.intersections;
ANALYZE local.roads;
SQL

echo ""
echo "Verifying..."
psql -v ON_ERROR_STOP=1 <<'SQL'
SELECT 'roads' AS table, count(*) FROM local.roads
UNION ALL SELECT 'intersections', count(*) FROM local.intersections;

SELECT flowdir, count(*) FROM local.roads GROUP BY flowdir ORDER BY flowdir;

-- meta must be usable as an encode path element.
SELECT count(*) AS meta_unusable FROM local.roads
WHERE meta IS NULL OR meta = '' OR meta LIKE '-%' OR meta LIKE '% %';

-- Endpoints that do not resolve. Any row here is invisible to findRoadsNear.
SELECT count(*) AS orphan_endpoints
FROM local.roads r
LEFT JOIN local.intersections a ON r.from_int = a.id
LEFT JOIN local.intersections b ON r.to_int   = b.id
WHERE a.id IS NULL OR b.id IS NULL;
SQL

echo "✅ Load complete"
