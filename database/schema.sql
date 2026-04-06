-- Initialize OpenLR database schema
-- This script is automatically executed when the PostgreSQL container starts for the first time

-- Enable PostGIS extension
CREATE EXTENSION IF NOT EXISTS postgis;

-- Create the local schema
CREATE SCHEMA IF NOT EXISTS local;

-- Set search path to include local schema
SET search_path TO local, public;

-- Create intersections table
CREATE TABLE IF NOT EXISTS local.intersections (
    id BIGINT NOT NULL PRIMARY KEY,
    meta TEXT,
    geom GEOMETRY(Point, 4326)
);

-- Create roads table
CREATE TABLE IF NOT EXISTS local.roads (
    id BIGINT NOT NULL PRIMARY KEY,
    meta TEXT,
    frc INTEGER,
    fow INTEGER,
    flowdir INTEGER,
    from_int BIGINT,
    to_int BIGINT,
    len DOUBLE PRECISION,
    geom GEOMETRY(LineString, 4326)
);

-- Create indexes on intersections table
CREATE INDEX IF NOT EXISTS local_intersections_geom_idx ON local.intersections USING GIST (geom);
CREATE INDEX IF NOT EXISTS local_intersections_meta_idx ON local.intersections USING BTREE (meta);

-- Create indexes on roads table
CREATE INDEX IF NOT EXISTS local_roads_from_int_idx ON local.roads USING BTREE (from_int);
CREATE INDEX IF NOT EXISTS local_roads_to_int_idx ON local.roads USING BTREE (to_int);
CREATE INDEX IF NOT EXISTS local_roads_geom_idx ON local.roads USING GIST (geom);

-- Add foreign key constraints (optional - uncomment if needed)
-- ALTER TABLE local.roads ADD CONSTRAINT roads_from_int_fkey FOREIGN KEY (from_int) REFERENCES local.intersections(id);
-- ALTER TABLE local.roads ADD CONSTRAINT roads_to_int_fkey FOREIGN KEY (to_int) REFERENCES local.intersections(id);

-- Grant privileges to openlr user
GRANT ALL PRIVILEGES ON SCHEMA local TO openlr;
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA local TO openlr;
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA local TO openlr;

-- Set default privileges for future tables
ALTER DEFAULT PRIVILEGES IN SCHEMA local GRANT ALL ON TABLES TO openlr;
ALTER DEFAULT PRIVILEGES IN SCHEMA local GRANT ALL ON SEQUENCES TO openlr;

-- Display schema information
\echo 'OpenLR database schema initialized successfully'
\echo 'Schema: local'
\echo 'Tables: intersections, roads'
\echo 'Indexes created on geometry columns and foreign keys'