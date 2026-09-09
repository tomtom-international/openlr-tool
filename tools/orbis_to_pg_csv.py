# /// script
# requires-python = ">=3.10"
# dependencies = ["duckdb>=1.1"]
# ///
"""Convert an Orbis road network into CSVs for this repo's PostGIS schema.

    uv run tools/orbis_to_pg_csv.py -i <osm_tag_mapper.csv> -o <outdir> [-m META]

Input is the CSV produced by osm_tag_mapper from an Orbis PBF. The transformation
lives in orbis_to_pg_csv.sql; see tools/README.md for the three things about this
mapping that are easy to get wrong.

Self-contained and cross-platform: uv installs the DuckDB Python package on first
run, so there is no duckdb CLI to install.

Orbis-only by design. It assumes each input row is one junction-to-junction link,
which Orbis satisfies and a plain OSM extract does not: about a quarter of
Geofabrik highway ways run through a junction mid-way.
"""

from __future__ import annotations

import argparse
import csv
import sys
from pathlib import Path

import duckdb

META_CHOICES = ("way", "gers", "osm", "both")


def sql_literal(value: str) -> str:
    return "'" + str(value).replace("'", "''") + "'"


def header_of(path: Path) -> list[str]:
    with path.open(newline="", encoding="utf-8") as handle:
        return next(csv.reader(handle), [])


def main() -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("-i", "--input", required=True, type=Path,
                        help="CSV produced by osm_tag_mapper from an Orbis PBF")
    parser.add_argument("-o", "--outdir", required=True, type=Path,
                        help="directory to write roads.csv and intersections.csv into")
    parser.add_argument("-m", "--meta", default="way", choices=META_CHOICES,
                        help="what roads.meta carries. Default 'way' (the Orbis way "
                             "id) is the only value guaranteed unique, which the API "
                             "requires")
    args = parser.parse_args()

    if not args.input.is_file():
        print(f"no such file: {args.input}", file=sys.stderr)
        return 1

    # A CSV from before osm_tag_mapper emitted way_id cannot supply a primary key.
    columns = header_of(args.input)
    if "way_id" not in columns:
        print(f"error: {args.input} has no way_id column.", file=sys.stderr)
        print("It predates the osm_tag_mapper change that emits way_id/gers_id; "
              "re-run osm_tag_mapper to regenerate it.", file=sys.stderr)
        return 1

    script_path = Path(__file__).with_suffix(".sql")
    if not script_path.is_file():
        print(f"missing {script_path}", file=sys.stderr)
        return 1

    args.outdir.mkdir(parents=True, exist_ok=True)
    con = duckdb.connect()
    try:
        con.execute(f"SET VARIABLE src_csv = {sql_literal(args.input.as_posix())}")
        con.execute(f"SET VARIABLE meta = {sql_literal(args.meta)}")
        con.execute(script_path.read_text(encoding="utf-8"))

        roads = (args.outdir / "roads.csv").as_posix()
        junctions = (args.outdir / "intersections.csv").as_posix()
        con.execute(f"""
            COPY (SELECT id, meta, frc, fow, flowdir, from_int, to_int, len, geom
                  FROM roads ORDER BY id)
            TO {sql_literal(roads)} (FORMAT csv, HEADER true)
        """)
        con.execute(f"""
            COPY (SELECT id, meta, geom FROM intersections ORDER BY id)
            TO {sql_literal(junctions)} (FORMAT csv, HEADER true)
        """)

        rows = con.execute("""
            SELECT metric, value FROM (
                SELECT 'read' AS metric, count(*) AS value FROM staged
                UNION ALL SELECT 'roads', count(*) FROM roads
                UNION ALL SELECT 'intersections', count(*) FROM intersections
                UNION ALL SELECT 'flowdir_' || flowdir, count(*) FROM roads GROUP BY flowdir
                UNION ALL SELECT 'rejected_' || reason, count(*) FROM rejected GROUP BY reason
            ) ORDER BY metric
        """).fetchall()
    finally:
        con.close()

    width = max(len(str(m)) for m, _ in rows)
    for metric, value in rows:
        print(f"  {metric:<{width}}  {value}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
