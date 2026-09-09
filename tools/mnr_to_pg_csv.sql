-- Convert one MultiNet-R country extract into this repo's PostGIS schema.
--
-- Run once per tarball by tools/mnr_to_pg_csv.sh, which sets the variables below
-- and accumulates every country into one pair of tables. The attribute mappings
-- follow openlr_customers/avro/create_tables.sql, which loaded the same three MN-R
-- tables into PostgreSQL; the differences from it are called out in comments.
--
-- Variables:
--   junction_avro, geo_link_avro, route_link_avro  paths to the extracted tables
--   source_label                                   tarball name, for reporting
--   include_back_roads                             'true' keeps BACK_ROAD <> 0
--   drivable_only                                  'true' drops walkways, stairs,
--                                                  pedestrian zones and parking

LOAD avro;

-- MN-R geometry is WKT in (longitude, latitude) order. Nothing here calls a
-- spheroid function -- length comes from CENTIMETERS -- but the setting is
-- declared so that adding one later cannot silently pick up (lat, lon) order.
LOAD spatial;
SET geometry_always_xy = true;

-- Deliberately unconstrained. These accumulate one country per tarball, and
-- maintaining a primary key and a UNIQUE index during that is the dominant cost:
-- appending Germany's 11.65M links to an empty indexed table is optimised into a
-- bulk build, but appending them to a table already holding another country means
-- 11.65M index probes over 36-character UUIDs, which took the same conversion from
-- 3 minutes to over 20. Duplicates are collapsed once, on output, and uniqueness is
-- asserted there instead.
CREATE TABLE IF NOT EXISTS roads (
    id BIGINT,
    meta VARCHAR,
    frc INTEGER,
    fow INTEGER,
    flowdir INTEGER,
    from_int BIGINT,
    to_int BIGINT,
    len DOUBLE,
    geom VARCHAR
);

CREATE TABLE IF NOT EXISTS intersections (
    id BIGINT,
    meta VARCHAR,
    geom VARCHAR
);

CREATE TABLE IF NOT EXISTS report (
    source VARCHAR,
    metric VARCHAR,
    value BIGINT
);

-- FEAT_ID is a UUID, so it cannot be the BIGINT primary key the OpenLR library
-- needs. It becomes `meta` -- the identifier the API exposes and the one /encode
-- resolves against -- and `id` is md5's low 64 bits shifted positive. md5 rather
-- than DuckDB's hash() because hash() is not guaranteed stable across versions.
-- The shell script asserts afterwards that no two FEAT_IDs collided on one id.
CREATE OR REPLACE MACRO surrogate_id(feat_id) AS
    CAST(md5_number_lower(feat_id) >> 1 AS BIGINT);

-- Each avro file is decoded exactly once, into a table holding only the columns
-- this conversion needs. MNR_Netw_Route_Link has 54 columns and Germany's is 900 MB,
-- so projecting early matters; reading it a second time for a row count, as an
-- earlier version did for the report, cost a full extra decode.
CREATE OR REPLACE TEMP TABLE route_raw AS
SELECT FEAT_ID, NETW_GEO_ID, JUNCTION_ID_FROM, JUNCTION_ID_TO,
       SIMPLE_TRAFFIC_DIRECTION, FORM_OF_WAY, DISPLAY_CLASS, BACK_ROAD
FROM read_avro(getvariable('route_link_avro'));

CREATE OR REPLACE TEMP TABLE geo_raw AS
SELECT FEAT_ID, CENTIMETERS, GEOM
FROM read_avro(getvariable('geo_link_avro'))
WHERE GEOM IS NOT NULL AND CENTIMETERS IS NOT NULL AND CENTIMETERS > 0;

CREATE OR REPLACE TEMP TABLE junction_raw AS
SELECT FEAT_ID, GEOM
FROM read_avro(getvariable('junction_avro'))
WHERE GEOM IS NOT NULL;

CREATE OR REPLACE TEMP TABLE link AS
WITH filtered AS (
    SELECT * FROM route_raw
    WHERE (getvariable('include_back_roads') = 'true' OR BACK_ROAD = 0)
      -- FORM_OF_WAY 13 Road In Pedestrian Zone, 16 ETA Parking Area,
      -- 19 ETA Parking Building, 20 Walkway, 21 Stairs, 23 ETA Gallery.
      AND (getvariable('drivable_only') <> 'true'
           OR FORM_OF_WAY IS NULL
           OR FORM_OF_WAY NOT IN (13, 16, 19, 20, 21, 23))
      AND JUNCTION_ID_FROM IS NOT NULL
      AND JUNCTION_ID_TO IS NOT NULL
      AND JUNCTION_ID_FROM <> JUNCTION_ID_TO   -- a ring has no distinct endpoints
)
SELECT
    r.FEAT_ID AS meta,
    -- DISPLAY_CLASS to OpenLR FRC. MN-R has nine classes to OpenLR's eight, so
    -- 70 Local Road Of Minor Importance and 80 Other Road both land on FRC7.
    CASE r.DISPLAY_CLASS
        WHEN 10 THEN 0   -- Motorway
        WHEN 20 THEN 1   -- Major Road Of High Importance
        WHEN 30 THEN 2   -- Other Major Road
        WHEN 40 THEN 3   -- Secondary Road
        WHEN 51 THEN 4   -- Local Connecting Road
        WHEN 52 THEN 5   -- Local Road Of High Importance
        WHEN 60 THEN 6   -- Local Road
        ELSE 7           -- 70, 80, NULL
    END AS frc,
    -- FORM_OF_WAY to OpenLR FOW. A dual or single carriageway that is also
    -- DISPLAY_CLASS 10 is a motorway. Everything OpenLR has no value for --
    -- walkways, service roads, car parks -- is OTHER, which is what FOW 7 is for.
    CASE
        WHEN r.FORM_OF_WAY = 2 AND r.DISPLAY_CLASS = 10 THEN 1   -- Motorway
        WHEN r.FORM_OF_WAY = 2 THEN 2                            -- Dual Carriageway
        WHEN r.FORM_OF_WAY = 3 AND r.DISPLAY_CLASS = 10 THEN 1   -- Motorway
        WHEN r.FORM_OF_WAY IN (3, 25) THEN 3                     -- Single Carriageway, Connector
        WHEN r.FORM_OF_WAY = 4 THEN 4                            -- Roundabout
        WHEN r.FORM_OF_WAY = 10 THEN 5                           -- Road In Enclosed Traffic Area
        WHEN r.FORM_OF_WAY IN (5, 6, 9, 18) THEN 6               -- slip roads, parallel road
        WHEN r.FORM_OF_WAY IS NULL THEN 0                        -- Undefined
        ELSE 7                                                   -- Other
    END AS fow,
    -- SIMPLE_TRAFFIC_DIRECTION is the allowed flow for passenger cars, relative to
    -- the digitised direction, which the data confirms always runs
    -- JUNCTION_ID_FROM -> JUNCTION_ID_TO. MN-R's positive direction is therefore
    -- flowdir 3 ("with digitisation") and negative is flowdir 2, so 2 and 3 swap.
    -- 9 is "Not Present" and is read as two-way, as the reference does; about a
    -- fifth of links carry it, most of them walkways.
    CASE r.SIMPLE_TRAFFIC_DIRECTION
        WHEN 1 THEN 1
        WHEN 2 THEN 3
        WHEN 3 THEN 2
        ELSE 1
    END AS flowdir,
    r.JUNCTION_ID_FROM AS from_meta,
    r.JUNCTION_ID_TO   AS to_meta,
    g.CENTIMETERS / 100.0 AS len,
    'SRID=4326;' || g.GEOM AS geom
FROM filtered r
JOIN geo_raw g ON r.NETW_GEO_ID = g.FEAT_ID
;

-- Only junctions an accepted link actually references. The reference SQL loaded
-- every row of MNR_Junction; with BACK_ROAD filtered that leaves junctions no road
-- points at, and findRoadsNear inner-joins the table so they can never be used.
CREATE OR REPLACE TEMP TABLE referenced AS
SELECT DISTINCT from_meta AS meta FROM link
UNION
SELECT DISTINCT to_meta FROM link;

CREATE OR REPLACE TEMP TABLE junction AS
SELECT j.FEAT_ID AS meta, 'SRID=4326;' || j.GEOM AS geom
FROM junction_raw j
JOIN referenced ref ON j.FEAT_ID = ref.meta;

-- A link whose junction is absent from this extract cannot be placed. Border
-- crossings are the usual reason; the neighbouring country's tarball carries them.
CREATE OR REPLACE TEMP TABLE placeable AS
SELECT l.* FROM link l
JOIN junction jf ON l.from_meta = jf.meta
JOIN junction jt ON l.to_meta   = jt.meta;

-- Plain appends. A border junction and its links appear in both neighbours'
-- extracts, so rows repeat across tarballs; because a repeat carries the same
-- FEAT_ID it is byte-identical, and the output step collapses it.
INSERT INTO intersections (id, meta, geom)
SELECT surrogate_id(meta), meta, geom FROM junction;

INSERT INTO roads (id, meta, frc, fow, flowdir, from_int, to_int, len, geom)
SELECT surrogate_id(meta), meta, frc, fow, flowdir,
       surrogate_id(from_meta), surrogate_id(to_meta), len, geom
FROM placeable;

INSERT INTO report
SELECT getvariable('source_label'), 'route_links_read', count(*) FROM route_raw
UNION ALL SELECT getvariable('source_label'), 'links_after_filters', count(*) FROM link
UNION ALL SELECT getvariable('source_label'), 'links_placeable', count(*) FROM placeable
UNION ALL SELECT getvariable('source_label'), 'links_unplaceable',
                 (SELECT count(*) FROM link) - (SELECT count(*) FROM placeable)
UNION ALL SELECT getvariable('source_label'), 'junctions_referenced', count(*) FROM junction;

-- Free the per-country scratch before the next tarball.
DROP TABLE IF EXISTS route_raw;
DROP TABLE IF EXISTS geo_raw;
DROP TABLE IF EXISTS junction_raw;
