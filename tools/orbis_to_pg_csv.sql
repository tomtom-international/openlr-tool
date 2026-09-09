-- Convert an osm_tag_mapper CSV (produced from an ORBIS PBF) into CSVs for this
-- repo's PostGIS schema. See tools/README.md.
--
-- Variables: 'src_csv' = source CSV
--            'meta'    = what to put in roads.meta: gers | osm | way | both
--
-- Orbis-only by design: Orbis ships ways already split at junctions, so a way's
-- endpoints are real junctions and its geometry is one junction-to-junction link.
-- A plain OSM PBF does not satisfy that (about a quarter of its highway ways run
-- through a junction mid-way) and must not be fed through this script.

INSTALL spatial;
LOAD spatial;

-- DuckDB's spheroid functions read coordinates as (latitude, longitude) by
-- default, which is the opposite of the (lon, lat) order in the source WKB.
-- Left unset, ST_Length_Spheroid returns lengths roughly 40% long at Dutch
-- latitudes, with an error that varies by latitude -- and `len` feeds OpenLR's
-- distance-to-next-point tolerance, so that would quietly wreck decoding.
-- Declaring the axis order once is safer than flipping at each call site.
SET geometry_always_xy = true;

-- Junction IDs are packed coordinates rather than a sequence, so that regions
-- converted separately agree on the ID of a shared boundary node.
--   lon*1e7 in [-1.8e9, 1.8e9], lat*1e7 in [-9e8, 9e8]
--   max id ~6.48e18, inside signed 64-bit
CREATE OR REPLACE MACRO junction_id(lon, lat) AS
      (CAST(round(lon * 1e7) AS BIGINT) + 1800000000) * 1800000001
    + (CAST(round(lat * 1e7) AS BIGINT) + 900000000);

CREATE OR REPLACE MACRO junction_label(lon, lat) AS
      (CASE WHEN lon >= 0 THEN 'E' ELSE 'W' END)
   || CAST(CAST(round(abs(lon) * 1e7) AS BIGINT) AS VARCHAR)
   || (CASE WHEN lat >= 0 THEN 'N' ELSE 'S' END)
   || CAST(CAST(round(abs(lat) * 1e7) AS BIGINT) AS VARCHAR);

CREATE OR REPLACE TEMP TABLE staged AS
WITH raw AS (
    -- way_id and gers_id were added to osm_tag_mapper's output; a CSV produced
    -- before that fails here by name, which is the intended outcome -- the old
    -- format cannot supply a usable primary key.
    SELECT osm_id, way_id, gers_id, oneway, openlr_frc, openlr_fow,
           geom AS wkb_hex,
           ST_GeomFromHEXWKB(geom) AS g
    FROM read_csv(getvariable('src_csv'), header = true, all_varchar = true)
), attributed AS (
    SELECT
        osm_id,
        way_id,
        gers_id,
        wkb_hex,
        g,
        TRY_CAST(replace(openlr_frc, 'FRC', '') AS INTEGER) AS frc,
        CASE openlr_fow
            WHEN 'UNDEFINED'            THEN 0
            WHEN 'MOTORWAY'             THEN 1
            WHEN 'MULTIPLE_CARRIAGEWAY' THEN 2
            WHEN 'SINGLE_CARRIAGEWAY'   THEN 3
            WHEN 'ROUNDABOUT'           THEN 4
            WHEN 'TRAFFIC_SQUARE'       THEN 5
            WHEN 'SLIP_ROAD'            THEN 6
            WHEN 'SLIPROAD'             THEN 6
            WHEN 'OTHER'                THEN 7
        END AS fow,
        -- osm_tag_mapper.oneway and local.roads.flowdir both use 1/2/3, but 2 and 3
        -- mean opposite things: oneway 2 = forward (with digitisation), flowdir 2 =
        -- one-way AGAINST digitisation. So 2 and 3 swap. NULL for anything else, and
        -- such rows are dropped below rather than defaulting to bidirectional.
        CASE oneway WHEN '1' THEN 1 WHEN '2' THEN 3 WHEN '3' THEN 2 END AS flowdir,
        -- Correct only because geometry_always_xy is set above. Verified against
        -- pyproj Geod on a real segment: 72.62777 both ways.
        ST_Length_Spheroid(g) AS len,
        ST_X(ST_StartPoint(g)) AS slon, ST_Y(ST_StartPoint(g)) AS slat,
        ST_X(ST_EndPoint(g))   AS elon, ST_Y(ST_EndPoint(g))   AS elat
    FROM raw
)
SELECT
    osm_id, way_id, gers_id, wkb_hex, g, frc, fow, flowdir, len,
    slon, slat, elon, elat,
    junction_id(slon, slat) AS from_int,
    junction_id(elon, elat) AS to_int
FROM attributed;

-- Rows that cannot be represented. Reported by the wrapper, never written.
CREATE OR REPLACE TEMP TABLE rejected AS
SELECT *,
       CASE
           WHEN frc IS NULL OR frc NOT BETWEEN 0 AND 7 THEN 'bad_frc'
           WHEN fow IS NULL                            THEN 'bad_fow'
           WHEN flowdir IS NULL                        THEN 'bad_oneway'
           WHEN len IS NULL OR len <= 0                THEN 'zero_length'
           WHEN from_int = to_int                      THEN 'closed_ring'
           WHEN TRY_CAST(way_id AS BIGINT) IS NULL
             OR TRY_CAST(way_id AS BIGINT) <= 0        THEN 'bad_way_id'
       END AS reason
FROM staged
WHERE frc IS NULL OR frc NOT BETWEEN 0 AND 7
   OR fow IS NULL OR flowdir IS NULL
   OR len IS NULL OR len <= 0
   OR from_int = to_int
   OR TRY_CAST(way_id AS BIGINT) IS NULL
   OR TRY_CAST(way_id AS BIGINT) <= 0;

-- The representable rows, computed once and used for both outputs.
CREATE OR REPLACE TEMP TABLE kept AS
SELECT * FROM staged
WHERE frc IS NOT NULL AND frc BETWEEN 0 AND 7
  AND fow IS NOT NULL AND flowdir IS NOT NULL
  AND len > 0 AND from_int <> to_int
  AND TRY_CAST(way_id AS BIGINT) > 0;

CREATE OR REPLACE TEMP TABLE roads AS
SELECT
    -- way_id is the id the source file itself uses: the Orbis way id here, unique
    -- per way, positive, and small enough that negating it (which the API does to
    -- mean reverse traversal) stays inside bigint. osm_id cannot serve -- Orbis
    -- reuses one osm_identifier across the pieces it splits a way into.
    CAST(way_id AS BIGINT) AS id,
    -- meta is the reference back into the source network, and is chosen by the
    -- 'meta' variable. Neither cross-reference is complete on its own: gers_id is
    -- empty on about 0.2% of Orbis rows (1,505 of 739,467 in a New Zealand
    -- extract) and is coarse where present (341,976 distinct values across those
    -- 739,467 ways), while osm_id loses the GERS reference. 'both' is the only
    -- option that is never empty, which is why it is the default.
    CASE getvariable('meta')
        WHEN 'gers' THEN COALESCE(nullif(gers_id, ''), osm_id)
        WHEN 'osm'  THEN osm_id
        WHEN 'way'  THEN way_id
        WHEN 'both' THEN CASE WHEN nullif(gers_id, '') IS NULL
                              THEN osm_id ELSE gers_id || '|' || osm_id END
    END AS meta,
    frc, fow, flowdir, from_int, to_int,
    printf('%.3f', len) AS len,
    'SRID=4326;' || ST_AsText(g) AS geom
FROM kept;

-- Both endpoints of every kept road, deduplicated. Derived from `kept` directly:
-- joining back to `staged` would fan out, since one osm_id can appear many times.
CREATE OR REPLACE TEMP TABLE intersections AS
SELECT id, any_value(meta) AS meta, any_value(geom) AS geom
FROM (
    SELECT from_int AS id,
           junction_label(slon, slat) AS meta,
           'SRID=4326;POINT (' || slon || ' ' || slat || ')' AS geom
    FROM kept
    UNION ALL
    SELECT to_int,
           junction_label(elon, elat),
           'SRID=4326;POINT (' || elon || ' ' || elat || ')'
    FROM kept
)
GROUP BY id;

-- Outputs are written by tools/orbis_to_pg_csv.sh: COPY ... TO requires a
-- literal path, so the wrapper appends the two COPY statements.
