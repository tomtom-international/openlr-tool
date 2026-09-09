#!/bin/bash
# Convert an osm_tag_mapper CSV (from an ORBIS PBF) into PG-schema CSVs.
#
#   tools/orbis_to_pg_csv.sh -i /tmp/nld.csv -o ~/orbis-nld
#
# Requires the duckdb CLI. Orbis input only -- see tools/README.md.

set -euo pipefail

IN="" OUTDIR="" META="way"
while [ $# -gt 0 ]; do
    case "$1" in
        -i|--input)  IN="$2"; shift 2 ;;
        -o|--outdir) OUTDIR="$2"; shift 2 ;;
        -m|--meta)   META="$2"; shift 2 ;;
        *) echo "usage: $0 -i <csv> -o <outdir> [-m gers|osm|way|both]" >&2; exit 2 ;;
    esac
done
case "$META" in
    gers|osm|way|both) ;;
    *) echo "--meta must be one of: gers osm way both" >&2; exit 2 ;;
esac
[ -n "$IN" ] && [ -n "$OUTDIR" ] || { echo "usage: $0 -i <csv> -o <outdir>" >&2; exit 2; }
[ -f "$IN" ] || { echo "no such file: $IN" >&2; exit 1; }

command -v duckdb >/dev/null || { echo "duckdb CLI not found" >&2; exit 1; }

# A CSV from before osm_tag_mapper emitted way_id cannot supply a primary key.
head -1 "$IN" | tr ',' '\n' | grep -qx way_id || {
    echo "error: $IN has no way_id column." >&2
    echo "It predates the osm_tag_mapper change that emits way_id/gers_id; re-run" >&2
    echo "osm_tag_mapper to regenerate it." >&2
    exit 1
}

mkdir -p "$OUTDIR"
SQL_DIR="$(cd "$(dirname "$0")" && pwd)"

# Variables, the conversion script, then a report -- piped as one SQL stream so
# no CLI dot-commands are involved.
{
    echo "SET VARIABLE src_csv = '$IN';"
    echo "SET VARIABLE meta = '$META';"
    cat "$SQL_DIR/orbis_to_pg_csv.sql"
    echo "COPY (SELECT id, meta, frc, fow, flowdir, from_int, to_int, len, geom"
    echo "      FROM roads ORDER BY id) TO '$OUTDIR/roads.csv' (FORMAT csv, HEADER true);"
    echo "COPY (SELECT id, meta, geom FROM intersections ORDER BY id)"
    echo "      TO '$OUTDIR/intersections.csv' (FORMAT csv, HEADER true);"
    cat <<'REPORT'
SELECT metric, value FROM (
    SELECT 'read' AS metric, count(*) AS value FROM staged
    UNION ALL SELECT 'roads', count(*) FROM roads
    UNION ALL SELECT 'intersections', count(*) FROM intersections
    UNION ALL SELECT 'flowdir_' || flowdir, count(*) FROM roads GROUP BY flowdir
    UNION ALL SELECT 'rejected_' || reason, count(*) FROM rejected GROUP BY reason
) ORDER BY metric;
REPORT
} | duckdb
