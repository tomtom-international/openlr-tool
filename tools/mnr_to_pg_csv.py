# /// script
# requires-python = ">=3.10"
# dependencies = ["duckdb>=1.1"]
# ///
"""Convert a MultiNet-R distribution into CSVs for this repo's PostGIS schema.

    uv run tools/mnr_to_pg_csv.py -i <distribution-dir> -o <outdir> [options]

Walks the directory for country tarballs, extracts the three tables each one needs
(MNR_Junction, MNR_Netw_Geo_Link, MNR_Netw_Route_Link), converts them with
mnr_to_pg_csv.sql, and accumulates every country into one roads.csv and one
intersections.csv. A distribution can hold hundreds of tarballs.

Self-contained and cross-platform: uv installs the DuckDB Python package on first
run, so there is no duckdb CLI to install, and extraction uses the standard
library's tarfile rather than a tar binary.

The attribute mappings live in mnr_to_pg_csv.sql and follow
openlr_customers/avro/create_tables.sql, which loaded the same three tables into
PostgreSQL. See tools/README.md for what differs and why.
"""

from __future__ import annotations

import argparse
import shutil
import sys
import tarfile
import time
from pathlib import Path

import duckdb

MEMBERS = (
    "MNR_Junction.avro",
    "MNR_Netw_Geo_Link.avro",
    "MNR_Netw_Route_Link.avro",
)


def sql_literal(value: str) -> str:
    """Quote a value for interpolation into SQL."""
    return "'" + str(value).replace("'", "''") + "'"


def find_tarballs(root: Path, all_tars: bool) -> list[Path]:
    """Locate candidate country tarballs anywhere beneath `root`.

    The three tables live in a country's core tarball. Restricting to core/ avoids
    spending minutes decompressing content tarballs that cannot contain them; a
    distribution laid out differently can override with --all-tars.
    """
    found = sorted(p for p in root.rglob("*.tar.gz") if p.is_file())
    if not found:
        return []
    if all_tars:
        print(f"Found {len(found)} tarball(s); considering all (--all-tars)")
        return found
    core = [p for p in found if "core" in p.parts]
    if core:
        print(f"Found {len(found)} tarball(s); using the {len(core)} under core/ "
              "(--all-tars to widen)")
        return core
    print(f"Found {len(found)} tarball(s); none under core/, considering all")
    return found


def extract_members(tar_path: Path, dest: Path) -> bool:
    """Extract the three tables, returning False if the archive lacks any.

    Streams the archive and stops as soon as all three have been seen: a gzip
    stream cannot be seeked, so reading to the end would cost minutes per tarball
    for members that sort early. Member names are matched exactly and written to
    paths this function chooses, so no archived path is ever honoured.
    """
    remaining = set(MEMBERS)
    try:
        with tarfile.open(tar_path, "r|gz") as archive:
            for member in archive:
                if member.name not in remaining:
                    continue
                if not member.isfile():
                    continue
                source = archive.extractfile(member)
                if source is None:
                    continue
                with open(dest / member.name, "wb") as out:
                    shutil.copyfileobj(source, out)
                remaining.discard(member.name)
                if not remaining:
                    break
    except (tarfile.TarError, OSError) as exc:
        print(f"  unreadable archive: {exc}", file=sys.stderr)
        return False
    return not remaining


def convert(con: duckdb.DuckDBPyConnection, script: str, avro_dir: Path,
            label: str, include_back_roads: bool, drivable_only: bool) -> None:
    variables = {
        "junction_avro": (avro_dir / MEMBERS[0]).as_posix(),
        "geo_link_avro": (avro_dir / MEMBERS[1]).as_posix(),
        "route_link_avro": (avro_dir / MEMBERS[2]).as_posix(),
        "source_label": label,
        "include_back_roads": "true" if include_back_roads else "false",
        "drivable_only": "true" if drivable_only else "false",
    }
    for name, value in variables.items():
        con.execute(f"SET VARIABLE {name} = {sql_literal(value)}")
    con.execute(script)


def deduplicate(con: duckdb.DuckDBPyConnection) -> tuple[int, int]:
    """Collapse rows that appeared in more than one tarball.

    Border junctions and the links touching them are present in both neighbours'
    extracts. A repeat carries the same FEAT_ID and therefore identical values, so
    one hash aggregate removes them -- far cheaper than the per-row index probes
    that maintaining a primary key during accumulation would have cost.
    """
    before_roads = con.execute("SELECT count(*) FROM roads").fetchone()[0]
    before_junctions = con.execute("SELECT count(*) FROM intersections").fetchone()[0]

    con.execute("""
        CREATE OR REPLACE TABLE roads_final AS
        SELECT id, any_value(meta) AS meta, any_value(frc) AS frc,
               any_value(fow) AS fow, any_value(flowdir) AS flowdir,
               any_value(from_int) AS from_int, any_value(to_int) AS to_int,
               any_value(len) AS len, any_value(geom) AS geom
        FROM roads GROUP BY id
    """)
    con.execute("""
        CREATE OR REPLACE TABLE intersections_final AS
        SELECT id, any_value(meta) AS meta, any_value(geom) AS geom
        FROM intersections GROUP BY id
    """)
    after_roads = con.execute("SELECT count(*) FROM roads_final").fetchone()[0]
    after_junctions = con.execute("SELECT count(*) FROM intersections_final").fetchone()[0]
    return before_roads - after_roads, before_junctions - after_junctions


def write_outputs(con: duckdb.DuckDBPyConnection, outdir: Path) -> None:
    roads = (outdir / "roads.csv").as_posix()
    junctions = (outdir / "intersections.csv").as_posix()
    con.execute(f"""
        COPY (SELECT id, meta, frc, fow, flowdir, from_int, to_int,
                     printf('%.3f', len) AS len, geom
              FROM roads_final ORDER BY id)
        TO {sql_literal(roads)} (FORMAT csv, HEADER true)
    """)
    con.execute(f"""
        COPY (SELECT id, meta, geom FROM intersections_final ORDER BY id)
        TO {sql_literal(junctions)} (FORMAT csv, HEADER true)
    """)


def print_table(rows: list[tuple], headers: tuple[str, ...]) -> None:
    widths = [len(h) for h in headers]
    text = [[("" if c is None else str(c)) for c in row] for row in rows]
    for row in text:
        widths = [max(w, len(c)) for w, c in zip(widths, row)]
    line = "  ".join(h.ljust(w) for h, w in zip(headers, widths))
    print("  " + line)
    print("  " + "  ".join("-" * w for w in widths))
    for row in text:
        print("  " + "  ".join(c.ljust(w) for c, w in zip(row, widths)))


def check_output(con: duckdb.DuckDBPyConnection) -> list[str]:
    """Assertions the schema and the API contract require of the output.

    `id` is a 63-bit hash of a UUID, so two FEAT_IDs colliding on one id is
    possible in principle and would have been dropped by INSERT OR IGNORE. Across
    a continent-sized load that is unlikely but not negligible, so it is checked
    rather than assumed.
    """
    checks = {
        "id must be positive": "SELECT count(*) FROM roads_final WHERE id IS NULL OR id <= 0",
        "id must be unique": "SELECT count(*) - count(DISTINCT id) FROM roads_final",
        "meta must be unique (no hash collision)":
            "SELECT count(DISTINCT meta) - count(DISTINCT id) FROM roads_final",
        "meta must be usable as an encode path element":
            "SELECT count(*) FROM roads_final WHERE meta IS NULL OR meta = '' "
            "OR meta LIKE '-%' OR meta LIKE '% %'",
        "flowdir must be 1, 2 or 3":
            "SELECT count(*) FROM roads_final WHERE flowdir NOT IN (1, 2, 3)",
        "frc must be 0-7": "SELECT count(*) FROM roads_final WHERE frc NOT BETWEEN 0 AND 7",
        "fow must be 0-7": "SELECT count(*) FROM roads_final WHERE fow NOT BETWEEN 0 AND 7",
        "len must be positive": "SELECT count(*) FROM roads_final WHERE len IS NULL OR len <= 0",
        "every endpoint must resolve to an intersection":
            "SELECT count(*) FROM roads_final r "
            "LEFT JOIN intersections_final a ON r.from_int = a.id "
            "LEFT JOIN intersections_final b ON r.to_int = b.id "
            "WHERE a.id IS NULL OR b.id IS NULL",
        "geometry must carry an SRID":
            "SELECT count(*) FROM roads_final WHERE geom NOT LIKE 'SRID=4326;%'",
    }
    failures = []
    for description, query in checks.items():
        offenders = con.execute(query).fetchone()[0]
        status = "ok" if offenders == 0 else f"FAILED ({offenders})"
        print(f"  {status:<14} {description}")
        if offenders:
            failures.append(description)
    return failures


def main() -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("-i", "--input", required=True, type=Path,
                        help="directory to search for MultiNet-R tarballs")
    parser.add_argument("-o", "--outdir", required=True, type=Path,
                        help="directory to write roads.csv and intersections.csv into")
    parser.add_argument("--include-back-roads", action="store_true",
                        help="keep BACK_ROAD <> 0: destination roads, service roads, "
                             "driveways. Default drops them, as the reference SQL did")
    parser.add_argument("--drivable-only", action="store_true",
                        help="drop walkways, stairs, pedestrian zones and parking "
                             "geometry. Default keeps them, mapped to OpenLR FOW 7")
    parser.add_argument("--all-tars", action="store_true",
                        help="consider every *.tar.gz, not just those under core/")
    parser.add_argument("--memory-limit", default=None,
                        help="cap DuckDB's memory, e.g. '8GB'. Germany peaks around "
                             "17 GB unconstrained; with a cap DuckDB spills to the "
                             "work directory instead, which is slower but bounded")
    parser.add_argument("--keep-work", action="store_true",
                        help="leave the extracted tables and the DuckDB database")
    args = parser.parse_args()

    if not args.input.is_dir():
        print(f"no such directory: {args.input}", file=sys.stderr)
        return 1

    script_path = Path(__file__).with_suffix(".sql")
    if not script_path.is_file():
        print(f"missing {script_path}", file=sys.stderr)
        return 1
    script = script_path.read_text(encoding="utf-8")

    args.outdir.mkdir(parents=True, exist_ok=True)
    work = args.outdir / ".work"
    if work.exists():
        shutil.rmtree(work)
    avro_dir = work / "avro"
    avro_dir.mkdir(parents=True)
    database = work / "mnr.duckdb"

    tarballs = find_tarballs(args.input, args.all_tars)
    if not tarballs:
        print(f"no *.tar.gz found under {args.input}", file=sys.stderr)
        return 1

    con = duckdb.connect(database.as_posix())
    # Spill inside the work directory rather than the system temp area, so a large
    # country cannot fill a small /tmp and the space is reclaimed with the rest.
    con.execute(f"SET temp_directory = {sql_literal(work.as_posix())}")
    if args.memory_limit:
        con.execute(f"SET memory_limit = {sql_literal(args.memory_limit)}")
        print(f"DuckDB memory limit: {args.memory_limit}")
    converted = skipped = 0
    try:
        for tar_path in tarballs:
            label = tar_path.name[: -len(".tar.gz")]
            print(f"[{label}] extracting... ", end="", flush=True)
            for stale in avro_dir.glob("*.avro"):
                stale.unlink()

            started = time.monotonic()
            if not extract_members(tar_path, avro_dir):
                print("skipped (does not contain the three MN-R tables)")
                skipped += 1
                continue

            print(f"converting... ", end="", flush=True)
            convert(con, script, avro_dir, label,
                    args.include_back_roads, args.drivable_only)
            placeable = con.execute(
                "SELECT value FROM report WHERE source = ? AND metric = 'links_placeable'",
                [label]).fetchone()
            print(f"{placeable[0] if placeable else 0} links "
                  f"({time.monotonic() - started:.0f}s)")
            converted += 1

        for stale in avro_dir.glob("*.avro"):
            stale.unlink()

        if converted == 0:
            print("no tarball yielded the three MN-R tables", file=sys.stderr)
            return 1

        dup_roads, dup_junctions = deduplicate(con)
        if dup_roads or dup_junctions:
            print(f"\nCollapsed rows seen in more than one tarball: "
                  f"{dup_roads} roads, {dup_junctions} junctions")

        print(f"\nWriting {args.outdir / 'roads.csv'} and "
              f"{args.outdir / 'intersections.csv'}")
        write_outputs(con, args.outdir)

        print("\nPer-tarball:")
        print_table(con.execute("""
            SELECT source, metric, value FROM report ORDER BY source, CASE metric
              WHEN 'route_links_read' THEN 1 WHEN 'links_after_filters' THEN 2
              WHEN 'links_placeable' THEN 3 WHEN 'links_unplaceable' THEN 4 ELSE 5 END
        """).fetchall(), ("source", "metric", "value"))

        print("\nTotals:")
        totals = [("tarballs_converted", converted), ("tarballs_skipped", skipped)]
        totals += con.execute("""
            SELECT 'roads', count(*) FROM roads_final
            UNION ALL SELECT 'intersections', count(*) FROM intersections_final
            UNION ALL SELECT 'flowdir_' || flowdir, count(*) FROM roads_final GROUP BY flowdir
            ORDER BY 1
        """).fetchall()
        print_table(totals, ("metric", "value"))

        print("\nOutput checks:")
        failures = check_output(con)
    finally:
        con.close()
        if not args.keep_work:
            shutil.rmtree(work, ignore_errors=True)
        else:
            print(f"\nWork directory kept at {work}")

    if failures:
        print(f"\n{len(failures)} check(s) failed", file=sys.stderr)
        return 1
    print("\nAll output checks passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
