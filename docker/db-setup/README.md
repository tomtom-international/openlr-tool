# Database Setup Sidecar Container

This sidecar container provides an easy way for users to load their map data into the PostgreSQL database without needing to install PostgreSQL client tools or GDAL on their host machine.

## Features

- **PostgreSQL Client (`psql`)** - Run SQL scripts and queries
- **GDAL Tools (`ogr2ogr`)** - Load spatial data from various formats (Shapefile, GeoJSON, etc.)
- **Pre-configured Environment** - Database connection details automatically set
- **Multi-Architecture** - Builds for both ARM64 and AMD64 automatically
- **Network Integrated** - Shares the same Docker network as the database
- **User-Friendly** - Simple command-line interface via `dc` script

## Quick Start

### 1. Prepare Your Data Directory

Create a directory with your data files and a `dbsetup.sh` script:

```
my-map-data/
├── dbsetup.sh       # Your setup script (required)
├── roads.shp        # Example: Shapefile data
├── roads.shx
├── roads.dbf
├── roads.prj
└── schema.sql       # Example: SQL schema
```

### 2. Create Your Setup Script

Create `dbsetup.sh` in your data directory:

```bash
#!/bin/bash
set -e

echo "Loading schema..."
psql -f schema.sql

echo "Loading roads..."
ogr2ogr -f PostgreSQL \
    PG:"host=$PGHOST dbname=$PGDATABASE user=$PGUSER password=$PGPASSWORD" \
    roads.shp \
    -nln local.roads \
    -lco GEOMETRY_NAME=geom \
    -lco SPATIAL_INDEX=GIST \
    -t_srs EPSG:4326

echo "Done!"
```

See `dbsetup.sh.example` for a complete example with multiple data loading methods.

### 3. Run Database Setup

```bash
./dc setup /path/to/my-map-data
```

That's it! The container will:
1. Wait for PostgreSQL to be ready
2. Execute your `dbsetup.sh` script
3. Display the output
4. Clean up automatically

## Environment Variables

The following environment variables are automatically configured in your script:

- `PGHOST=postgres` - Database hostname
- `PGPORT=5432` - Database port
- `PGDATABASE=openlr_db` - Database name
- `PGUSER=openlr` - Database user
- `PGPASSWORD=openlrpwd` - Database password

You don't need to specify connection details in your commands!

## Available Tools

### PostgreSQL Client (psql)

Execute SQL commands directly:

```bash
psql -c "SELECT COUNT(*) FROM local.roads;"
```

Run SQL files:

```bash
psql -f schema.sql
psql -f load_data.sql
```

Use heredocs for inline SQL:

```bash
psql <<EOF
CREATE TABLE test (id SERIAL PRIMARY KEY);
INSERT INTO test VALUES (1), (2), (3);
EOF
```

### GDAL/OGR Tools (ogr2ogr)

Load Shapefiles:

```bash
ogr2ogr -f PostgreSQL \
    PG:"host=$PGHOST dbname=$PGDATABASE" \
    roads.shp \
    -nln local.roads
```

Load GeoJSON:

```bash
ogr2ogr -f PostgreSQL \
    PG:"host=$PGHOST dbname=$PGDATABASE" \
    roads.geojson \
    -nln local.roads
```

Convert and load with transformations:

```bash
ogr2ogr -f PostgreSQL \
    PG:"host=$PGHOST dbname=$PGDATABASE" \
    roads.shp \
    -nln local.roads \
    -t_srs EPSG:4326 \
    -lco GEOMETRY_NAME=geom \
    -lco SPATIAL_INDEX=GIST
```

## Examples

### Example 1: Simple SQL Schema

**dbsetup.sh:**
```bash
#!/bin/bash
set -e

psql <<EOF
CREATE SCHEMA IF NOT EXISTS local;
CREATE TABLE local.roads (
    id BIGSERIAL PRIMARY KEY,
    name TEXT,
    geom GEOMETRY(LineString, 4326)
);
EOF
```

### Example 2: Load Shapefile

**dbsetup.sh:**
```bash
#!/bin/bash
set -e

ogr2ogr -f PostgreSQL \
    PG:"host=$PGHOST dbname=$PGDATABASE" \
    germany_roads.shp \
    -nln local.roads \
    -overwrite
```

### Example 3: Multiple Data Sources

**dbsetup.sh:**
```bash
#!/bin/bash
set -e

# Create schema
psql -f schema.sql

# Load roads from Shapefile
ogr2ogr -f PostgreSQL PG:"host=$PGHOST dbname=$PGDATABASE" roads.shp -nln local.roads

# Load intersections from CSV
psql -c "\COPY local.intersections FROM 'intersections.csv' WITH (FORMAT csv, HEADER true)"

# Create indexes
psql -f indexes.sql

# Show statistics
psql -c "SELECT COUNT(*) FROM local.roads;"
```

## Troubleshooting

### Script Not Found

```
❌ Error: No dbsetup.sh found in: /path/to/data
```

**Solution:** Make sure your data directory contains a file named exactly `dbsetup.sh`.

### Connection Errors

The container automatically waits up to 60 seconds for PostgreSQL to be ready. If you still see connection errors, ensure the `postgres` service is healthy:

```bash
./dc ps
```

### View Logs

Check the output of the setup container:

```bash
./dc logs db-setup
```

## Advanced Usage

### Custom Data Directory Default

Set a default data directory using an environment variable:

```bash
export DATA_DIR=/Users/dave/default-map-data
./dc --profile setup run --rm db-setup
```

### Interactive Debugging

Get a shell in the container:

```bash
export DATA_DIR=/path/to/data
./dc --profile setup run --rm db-setup /bin/bash
```

Then manually run commands:

```bash
psql -c "SELECT version();"
ogr2ogr --version
cd /data && ls -la
```

## Architecture

This container is built using the same multi-architecture approach as the main application:

- **No platform specifications** - Builds for native architecture
- **ARM64** - Native on Apple Silicon Macs
- **AMD64** - Native on Intel/AMD systems

The base image (`ubuntu:22.04`) has multi-arch support, so it will automatically use the correct architecture for your system.

## Files

- `Dockerfile` - Container definition with psql and ogr2ogr
- `entrypoint.sh` - Setup script executor
- `dbsetup.sh.example` - Complete example showing all features
- `README.md` - This file
