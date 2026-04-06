# OpenLR WebTool - PostgreSQL Docker Deployment

Docker Compose setup for running OpenLR WebTool with PostgreSQL + PostGIS using a minimal OpenLR schema. This deployment model is recommended for production use with large-scale map databases.

The application uses a simple two-table schema (`local.roads` and `local.intersections`) optimized for OpenLR encoding and decoding operations.

## Quick Start

### 1. Build the Containers

```bash
cd docker
./dc build
```

This builds both the application and database setup containers.

### 2. Start the Services

```bash
./dc up
```

This starts:
- PostgreSQL + PostGIS database on port 5432
- OpenLR WebTool application on port 8081

Wait for the application to be ready:
```bash
# Check status
./dc ps

# Watch logs
./dc logs app
```

### 3. Load Map Data

Create a directory with your data files and a `dbsetup.sh` script:

```bash
mkdir ~/my-map-data
cd ~/my-map-data

# Create your setup script
cat > dbsetup.sh <<'EOF'
#!/bin/bash
set -e

echo "Creating schema..."
psql postgresql://openlr:openlrpwd@postgres:5432/openlr_db <<SQL
CREATE SCHEMA IF NOT EXISTS local;
CREATE TABLE IF NOT EXISTS local.roads (
    id BIGSERIAL PRIMARY KEY,
    name TEXT,
    geom GEOMETRY(LineString, 4326)
);
SQL

echo "Loading roads from shapefile..."
ogr2ogr -f PostgreSQL \
    PG:"host=postgres port=5432 dbname=openlr_db user=openlr password=openlrpwd" \
    roads.shp \
    -nln local.roads \
    -lco GEOMETRY_NAME=geom \
    -lco SPATIAL_INDEX=GIST \
    -t_srs EPSG:4326

echo "Done!"
EOF
```

Run the database setup:
```bash
cd /path/to/webtool/docker
./dc setup ~/my-map-data
```

See the example in `../data/dbsetup.sh` for a simple test script.

### 4. Test the Application

The decode endpoint supports three formats:

**Option 1: JSON POST** (recommended for programmatic access)
```bash
curl -X POST http://localhost:8081/api/v1/decode \
  -H "Content-Type: application/json" \
  -d '{"openLrCode": "CwV/mSIeQA4kBgFxAJ8OEA==", "props": "default"}'
```

**Option 2: Form Data POST** (HTTPie friendly)
```bash
# Using HTTPie
http -f http://localhost:8081/api/v1/decode \
  openLrCode=CwV/mSIeQA4kBgFxAJ8OEA== \
  props=default

# Using curl
curl -X POST http://localhost:8081/api/v1/decode \
  -H "Content-Type: application/x-www-form-urlencoded" \
  --data-urlencode "openLrCode=CwV/mSIeQA4kBgFxAJ8OEA==" \
  --data-urlencode "props=default"
```

**Option 3: GET with Query Parameters** (browser friendly)
```bash
curl "http://localhost:8081/api/v1/decode?openLrCode=CwV/mSIeQA4kBgFxAJ8OEA==&props=default"
```

**Sequential Profile Attempts:**

The `props` parameter accepts comma-separated profile names. The decoder tries each in order until one succeeds:

```bash
# Try strict first, fall back to relaxed, then default
curl -X POST http://localhost:8081/api/v1/decode \
  -H "Content-Type: application/json" \
  -d '{"openLrCode": "CwV/mSIeQA4kBgFxAJ8OEA==", "props": "strict,relaxed,default"}'

# HTTPie with fallback profiles
http -f http://localhost:8081/api/v1/decode \
  openLrCode=CwV/mSIeQA4kBgFxAJ8OEA== \
  props=strict,relaxed,default
```

All formats return a GeoJSON FeatureCollection. The `meta.propertySet` field indicates which profile succeeded.

**Other endpoints:**
```bash
# Purge cache
curl http://localhost:8081/api/v1/purgeCache
```

## dc Script Reference

The `dc` script provides convenient commands for managing the Docker Compose stack.

### Get Help

```bash
./dc              # Show usage information
./dc --help       # Show usage information
```

### Building

```bash
./dc build              # Build all images (app + db-setup)
./dc build app          # Build only application image
./dc build db-setup     # Build only database setup image
```

### Starting and Stopping

```bash
# Start services
./dc up                 # Start postgres and app
./dc up postgres        # Start only postgres
./dc up app             # Start app (depends on postgres)

# Stop services
./dc down               # Stop all services
./dc down app           # Stop only app
./dc down postgres      # Stop only postgres

# Restart services
./dc restart            # Restart all services
./dc restart app        # Restart only app
```

### Monitoring

```bash
# View container status
./dc ps

# View logs (follow mode)
./dc logs               # All services
./dc logs app           # App logs only
./dc logs postgres      # PostgreSQL logs only

# Check PostgreSQL health
./dc exec postgres pg_isready -h localhost -U openlr -d openlr_db
```

### Database Operations

```bash
# Execute SQL query
./dc exec postgres psql -U openlr -d openlr_db -c "SELECT COUNT(*) FROM local.roads;"

# Get interactive psql shell
./dc exec postgres psql -U openlr -d openlr_db

# Load map data from directory
./dc setup /path/to/data-directory
```

### Cleanup

```bash
# Stop and remove everything (asks for confirmation)
./dc clean
```

## Database Setup Sidecar

The database setup sidecar container makes it easy to load map data without installing PostgreSQL client tools or GDAL on your host machine.

### Features

- **PostgreSQL Client (`psql`)** - Run SQL scripts and queries
- **GDAL Tools (`ogr2ogr`)** - Load spatial data (Shapefile, GeoJSON, etc.)
- **Pre-configured Environment** - Database connection details automatically set
- **Multi-Architecture** - Native ARM64 and AMD64 builds

### Connection String

The PostgreSQL database is accessible at:
```
postgresql://openlr:openlrpwd@postgres:5432/openlr_db
```

From your `dbsetup.sh` script, you can use:
- **psql**: `psql postgresql://openlr:openlrpwd@postgres:5432/openlr_db`
- **ogr2ogr**: `PG:"host=postgres port=5432 dbname=openlr_db user=openlr password=openlrpwd"`

Or use the pre-configured environment variables:
- `PGHOST=postgres`
- `PGPORT=5432`
- `PGDATABASE=openlr_db`
- `PGUSER=openlr`
- `PGPASSWORD=openlrpwd`

### Simple Example

See `../data/dbsetup.sh` for a minimal working example:

```bash
#!/bin/bash
ogr2ogr --help
psql postgresql://openlr:openlrpwd@postgres:5432/openlr_db -c "select count(*) from local.roads;"
echo "Hello, World!"
```

Run it with:
```bash
./dc setup ../data
```

### Comprehensive Example

See `db-setup/dbsetup.sh.example` for a complete example showing:
- Creating schemas and tables
- Loading from Shapefile, CSV, and SQL dumps
- Creating indexes
- Displaying statistics

### Usage

```bash
# Prepare your data directory
mkdir ~/my-map-data
cd ~/my-map-data

# Add your data files
cp /path/to/roads.shp .
cp /path/to/roads.shx .
cp /path/to/roads.dbf .
cp /path/to/roads.prj .

# Create dbsetup.sh script (see examples above)
vim dbsetup.sh

# Run database setup
cd /path/to/webtool/docker
./dc setup ~/my-map-data
```

The container will:
1. Wait for PostgreSQL to be ready
2. Execute your `dbsetup.sh` script
3. Display the output
4. Automatically clean up when done

For complete documentation, see [db-setup/README.md](db-setup/README.md).

## Configuration

### Environment Variables

Customize the deployment using environment variables:

```bash
# Custom ports
PORT=8082 POSTGRES_PORT=5433 ./dc up

# Custom JVM memory
JAVA_OPTS="-Xmx32g" ./dc up

# Custom data directory for setup
DATA_DIR=/path/to/data ./dc setup
```

| Variable | Default | Description |
|----------|---------|-------------|
| `PORT` | 8081 | Application HTTP port |
| `POSTGRES_PORT` | 5432 | PostgreSQL port |
| `JAVA_OPTS` | `-Xmx24g` | JVM options |
| `DATA_DIR` | `./data` | Data directory for db-setup |

### PostgreSQL Configuration

PostgreSQL is tuned for production workloads:

```yaml
shared_buffers: 4GB
effective_cache_size: 12GB
maintenance_work_mem: 2GB
work_mem: 128MB
max_connections: 200
max_parallel_workers: 8
```

**Minimum RAM**: 16GB recommended for production

### Database Schema

The application uses a minimal OpenLR schema in PostgreSQL with two main tables in the `local` schema:

#### `local.roads` Table

Road segments representing the map network.

| Column | Type | Description |
|--------|------|-------------|
| `id` | bigint | Primary key - unique road segment identifier (positive or negative for direction) |
| `meta` | text | Optional metadata/UUID for the road segment |
| `flowdir` | smallint | Flow direction: 0=both, 1=forward, 2=backward |
| `fow` | smallint | Form of way (FRC classification) |
| `frc` | smallint | Functional road class (0-7, 0=motorway, 7=other) |
| `geom` | geometry(LineString,4326) | Line geometry in WGS84 (SRID 4326) |
| `len` | double precision | Length of road segment in meters |
| `from_int` | bigint | ID of starting intersection |
| `to_int` | bigint | ID of ending intersection |

**Indexes:**
- Primary key on `id`
- B-tree indexes on `from_int`, `to_int`, `meta`
- GIST spatial index on `geom`

#### `local.intersections` Table

Junction points where road segments connect.

| Column | Type | Description |
|--------|------|-------------|
| `id` | bigint | Primary key - unique intersection identifier |
| `meta` | text | Optional metadata/UUID for the intersection |
| `geom` | geometry(Point,4326) | Point geometry in WGS84 (SRID 4326) |

**Indexes:**
- Primary key on `id`
- B-tree index on `meta`
- GIST spatial index on `geom`

**Connection String:**
```
postgresql://openlr:openlrpwd@postgres:5432/openlr_db
```

### Application Configuration

The application configuration is split into three layers:

#### 1. Spring Configuration (`config/application.properties`)

Main Spring Boot configuration file:

```properties
spring.profiles.active=generic_pg_mapdb
spring.datasource.url=jdbc:postgresql://postgres:5432/openlr_db
spring.datasource.username=openlr
spring.datasource.password=openlrpwd
spring.datasource.hikari.maximum-pool-size=20

# OpenLR property directories
decoder_properties_dir=/app/config/decoding_properties
encoding_properties_dir=/app/config/encoding_properties
```

This file is mounted from `../config/application.properties` and specifies database connection details and paths to OpenLR configuration directories.

#### 2. Decoding Properties (`config/decoding_properties/`)

OpenLR decoder behavior is controlled by property files in this directory. The `props` parameter in decode API calls selects which property file to use:

**Available Profiles:**
- **`default`** (`default.properties`) - Balanced decoding parameters for general use
- **`relaxed`** (`relaxed.properties`) - More lenient matching (accepts lower ratings)
- **`strict`** (`strict.properties`) - Strict matching requirements (requires higher ratings)

**Example API usage:**
```bash
# Use default decoding properties
curl -X POST http://localhost:8081/api/v1/decode \
  -H "Content-Type: application/json" \
  -d '{"openLrCode": "CwV/mSIeQA4kBgFxAJ8OEA==", "props": "default"}'

# Use strict decoding properties
curl -X POST http://localhost:8081/api/v1/decode \
  -H "Content-Type: application/json" \
  -d '{"openLrCode": "CwV/mSIeQA4kBgFxAJ8OEA==", "props": "strict"}'
```

**Key Parameters** (from `default.properties`):
- `BearingDistance=20` - Distance for bearing calculation
- `MaxNodeDistance=100` - Maximum distance to search for nodes
- `MinimumAcceptedRating=600` - Minimum rating to accept a match
- `FRC_Variance=2` - Allowed functional road class variance
- `MaxNumberRetries=3` - Maximum retry attempts

#### 3. Encoding Properties (`config/encoding_properties/`)

OpenLR encoder configuration:

- **`default`** (`default.properties`) - Standard encoding parameters

**Example API usage:**
```bash
curl -X POST http://localhost:8081/api/v1/encode \
  -d "path=123&path=456&path=789" \
  -d "props=default"
```

#### Customizing Configuration

To modify OpenLR behavior:

1. **Edit property files** in `../config/decoding_properties/` or `../config/encoding_properties/`
2. **Restart the application**:
   ```bash
   ./dc restart app
   ```

3. **Add new profiles** by creating new `.properties` files:
   ```bash
   cp ../config/decoding_properties/default.properties \
      ../config/decoding_properties/custom.properties
   # Edit custom.properties
   ```

4. **Use the new profile**:
   ```bash
   curl -X POST http://localhost:8081/api/v1/decode \
     -H "Content-Type: application/json" \
     -d '{"openLrCode": "...", "props": "custom"}'
   ```

The configuration files are mounted read-only into the container at `/app/config/`, so changes to files in `../config/` on the host are reflected after an app restart.

## Health Checks

Both services include health checks for monitoring:

### PostgreSQL Health Check
```bash
# Check from host
docker exec openlr-postgres pg_isready -h localhost -U openlr -d openlr_db

# Or via dc script
./dc exec postgres pg_isready -h localhost -U openlr -d openlr_db
```

### Application Health Check
```bash
# Check from host
curl -f http://localhost:8081/api/v1/purgeCache
```

Docker monitors these automatically:
- **PostgreSQL**: Every 10s, 5s timeout, 5 retries, 30s start period
- **Application**: Every 30s, 3s timeout, 3 retries, 60s start period

## Data Persistence

PostgreSQL data persists across container restarts in a Docker volume:

```bash
# View volumes
docker volume ls | grep openlr

# Backup PostgreSQL data
docker exec openlr-postgres pg_dump -U openlr openlr_db > backup.sql

# Restore PostgreSQL data
cat backup.sql | docker exec -i openlr-postgres psql -U openlr -d openlr_db
```

To completely remove all data:
```bash
./dc clean  # Removes containers, networks, and volumes (asks for confirmation)
```

## Troubleshooting

### Connection Errors

**Application can't connect to PostgreSQL**:
```bash
# Check PostgreSQL health
./dc ps
./dc logs postgres

# Verify PostgreSQL is ready
./dc exec postgres pg_isready -h localhost -U openlr -d openlr_db
```

### Port Conflicts

**Port 8081 or 5432 already in use**:
```bash
# Use custom ports
PORT=8082 POSTGRES_PORT=5433 ./dc up

# Find what's using a port
lsof -i :8081
lsof -i :5432
```

### Memory Issues

**Out of memory errors**:
```bash
# Reduce JVM heap size
JAVA_OPTS="-Xmx16g" ./dc up

# Check Docker memory usage
docker stats openlr-tool openlr-postgres
```

### Database Setup Issues

**dbsetup.sh not found**:
```bash
# Error: No dbsetup.sh found in: /path/to/data
# Solution: Create dbsetup.sh in your data directory
# See db-setup/dbsetup.sh.example for template
```

**Setup fails to connect**:
```bash
# Ensure PostgreSQL is healthy first
./dc up postgres
./dc ps  # Check postgres health status

# Then run setup
./dc setup /path/to/data
```

**Permission errors**:
Your data directory is mounted read-write, so scripts can create logs and output files.

## Architecture

```
┌─────────────────┐     ┌──────────────────┐     ┌─────────────────┐
│                 │     │                  │     │                 │
│  OpenLR WebTool │────▶│   PostgreSQL     │◀───│   DB Setup      │
│  (Spring Boot)  │     │   + PostGIS      │    │   Sidecar       │
│                 │     │                  │     │  (on-demand)    │
└─────────────────┘     └──────────────────┘     └─────────────────┘
      :8081                   :5432                  (psql + ogr2ogr)
```

### Components

- **postgres**: PostgreSQL 16 with PostGIS 3.4 (kartoza/postgis)
- **app**: OpenLR WebTool application with `generic_pg_mapdb` profile
- **db-setup**: On-demand sidecar for loading map data

All services share the `openlr_network` Docker network and use native architecture builds (ARM64/AMD64).

### Multi-Architecture Support

All images build for native architecture automatically:
- **ARM64** - Native on Apple Silicon Macs
- **AMD64** - Native on Intel/AMD systems

To verify:
```bash
docker image inspect openlr-tool:latest --format '{{.Architecture}}'
docker image inspect openlr-db-setup:latest --format '{{.Architecture}}'
```

## Production Deployment

### Recommended Setup

```bash
# 1. Build images
./dc build

# 2. Start services
PORT=8080 JAVA_OPTS="-Xmx32g" ./dc up

# 3. Load map data
./dc setup /path/to/production-data

# 4. Verify health
./dc ps
curl http://localhost:8080/api/v1/purgeCache

# 5. Monitor logs
./dc logs app
```

### Production Checklist

- [ ] PostgreSQL data volume backed up regularly
- [ ] Health checks monitored
- [ ] Resource limits configured appropriately
- [ ] Application logs collected and monitored
- [ ] Database performance metrics tracked
- [ ] Backup strategy for PostgreSQL data

## Comparison with Multi-Config Deployment

This repository supports two deployment models:

| Feature | PostgreSQL Compose | Multi-Config |
|---------|-------------------|--------------|
| **Script** | `./docker/dc` | `./scripts/docker-run.sh` |
| **Database** | PostgreSQL + PostGIS | SQLite |
| **Use Case** | Production, large datasets | Development, multiple configs |
| **Management** | Docker Compose | Individual containers |
| **Documentation** | This file | [DOCKER.md](DOCKER.md) |

Choose PostgreSQL deployment for:
- Production environments
- Large-scale map databases
- Multi-user scenarios
- Advanced spatial queries

Choose multi-config deployment for:
- Development and testing
- Multiple independent configurations
- Single-user scenarios

## Files

- `docker-compose.yml` - Service definitions for postgres, app, and db-setup
- `dc` - Management script with convenient commands
- `Dockerfile` - Application container image
- `db-setup/` - Database setup sidecar container
  - `Dockerfile` - db-setup container image
  - `entrypoint.sh` - Setup script executor
  - `dbsetup.sh.example` - Example setup script
  - `README.md` - Complete db-setup documentation
- `init-db/` - PostgreSQL initialization scripts (optional)
- `README.md` - This file

## License

This project is licensed under the Apache License 2.0. See the repository `LICENSE` file for details.
