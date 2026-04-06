# PostgreSQL + PostGIS Sidecar for OpenLR WebTool

This guide explains how to use the PostgreSQL/PostGIS sidecar with the OpenLR WebTool application.

## Overview

The PostgreSQL sidecar provides a production-ready database backend for OpenLR map data storage using:

- **PostgreSQL 17** - Latest stable release
- **PostGIS 3.5** - Advanced spatial database features
- **Persistent volumes** - Data survives container restarts
- **Optimized configuration** - Tuned for OpenLR workloads
- **Automatic schema initialization** - Ready to use on first start

## Quick Start

### 1. Start PostgreSQL Sidecar

```bash
cd /path/to/webtool
./scripts/docker-run.sh postgres up
```

This will:
- Pull the `postgis/postgis:17-3.5` image
- Create a persistent volume `openlr_postgres_data`
- Initialize the `openlr_db` database
- Create the `local` schema with `roads` and `intersections` tables
- Set up all necessary indexes
- Create a bridge network `openlr_network`

### 2. Verify PostgreSQL is Ready

```bash
./scripts/docker-run.sh postgres status
```

Expected output:
```
📊 PostgreSQL Sidecar Status:
NAMES            STATUS          PORTS
openlr-postgres  Up 2 minutes    0.0.0.0:5432->5432/tcp

✅ PostgreSQL is ready and accepting connections
```

### 3. Start the Application

```bash
./scripts/docker-run.sh up generic-pg
```

The application will automatically connect to the PostgreSQL sidecar using the `openlr_network` bridge network.

## Management Commands

### Start/Stop PostgreSQL

```bash
# Start PostgreSQL sidecar
./scripts/docker-run.sh postgres up

# Stop PostgreSQL sidecar (data persists)
./scripts/docker-run.sh postgres down

# Reset database (DELETES ALL DATA)
./scripts/docker-run.sh postgres reset
```

### View Logs

```bash
# Follow PostgreSQL logs
./scripts/docker-run.sh postgres logs

# View last 100 lines
docker logs --tail 100 openlr-postgres
```

### Connect with psql

```bash
# Interactive psql session
./scripts/docker-run.sh postgres psql

# Or directly with docker
docker exec -it openlr-postgres psql -U openlr -d openlr_db
```

### Check Status

```bash
./scripts/docker-run.sh postgres status
```

This displays:
- Container status
- PostgreSQL version
- Row counts for `intersections` and `roads` tables

## Database Schema

### Tables

#### local.intersections

Stores road network nodes (junctions, endpoints).

| Column | Type | Description |
|--------|------|-------------|
| `id` | BIGINT | Primary key, unique node identifier |
| `meta` | TEXT | External reference or metadata |
| `geom` | GEOMETRY(Point, 4326) | Point geometry in WGS84 |

**Indexes:**
- `intersections_pkey` - Primary key on `id`
- `local_intersections_geom_idx` - GIST index on `geom`
- `local_intersections_meta_idx` - B-tree index on `meta`

#### local.roads

Stores road network links (segments).

| Column | Type | Description |
|--------|------|-------------|
| `id` | BIGINT | Primary key, unique road segment identifier |
| `meta` | TEXT | External reference or metadata |
| `frc` | INTEGER | Functional Road Class (0-7, 0 = highest) |
| `fow` | INTEGER | Form of Way (0-6, see below) |
| `flowdir` | INTEGER | Flow direction (1 = two-way, 2 = one-way E←S, 3 = one-way S→E) |
| `from_int` | BIGINT | Start intersection ID (FK to intersections.id) |
| `to_int` | BIGINT | End intersection ID (FK to intersections.id) |
| `len` | DOUBLE PRECISION | Length in meters |
| `geom` | GEOMETRY(LineString, 4326) | Line geometry in WGS84 |

**Indexes:**
- `roads_pkey` - Primary key on `id`
- `local_roads_from_int_idx` - B-tree index on `from_int`
- `local_roads_to_int_idx` - B-tree index on `to_int`
- `local_roads_geom_idx` - GIST index on `geom`

### Attribute Values

#### Form of Way (fow)

| Value | Description |
|-------|-------------|
| 0 | Undefined |
| 1 | Motorway |
| 2 | Multiple carriageway |
| 3 | Single carriageway |
| 4 | Roundabout |
| 5 | Traffic square |
| 6 | Sliproad |

#### Functional Road Class (frc)

| Value | Description |
|-------|-------------|
| 0 | Main road (FRC 0) |
| 1 | First class road (FRC 1) |
| 2 | Second class road (FRC 2) |
| 3 | Third class road (FRC 3) |
| 4 | Fourth class road (FRC 4) |
| 5 | Fifth class road (FRC 5) |
| 6 | Sixth class road (FRC 6) |
| 7 | Other road (FRC 7) |

## Loading Data

### Using SQL

```sql
-- Connect to database
\c openlr_db

-- Load intersections
INSERT INTO local.intersections (id, meta, geom)
VALUES
  (1, 'node_1', ST_SetSRID(ST_MakePoint(-0.1276, 51.5074), 4326)),
  (2, 'node_2', ST_SetSRID(ST_MakePoint(-0.1280, 51.5078), 4326));

-- Load roads
INSERT INTO local.roads (id, meta, frc, fow, flowdir, from_int, to_int, len, geom)
VALUES (
  1,
  'road_1',
  3,
  3,
  1,
  1,
  2,
  50.5,
  ST_SetSRID(ST_MakeLine(
    ST_MakePoint(-0.1276, 51.5074),
    ST_MakePoint(-0.1280, 51.5078)
  ), 4326)
);
```

### Using COPY from CSV

```sql
-- Intersections CSV format: id,meta,lon,lat
COPY local.intersections(id, meta, geom)
FROM '/path/to/intersections.csv'
DELIMITER ','
CSV HEADER
TRANSFORM (id, meta, ST_SetSRID(ST_MakePoint(lon, lat), 4326));

-- Roads CSV format: id,meta,frc,fow,flowdir,from_int,to_int,len,wkt_geom
COPY local.roads(id, meta, frc, fow, flowdir, from_int, to_int, len, geom)
FROM '/path/to/roads.csv'
DELIMITER ','
CSV HEADER
TRANSFORM (id, meta, frc, fow, flowdir, from_int, to_int, len, ST_GeomFromText(wkt_geom, 4326));
```

### Using pg_restore

```bash
# Backup existing database
./scripts/docker-run.sh postgres psql -c "\\! pg_dump -U openlr openlr_db > backup.sql"

# Restore from backup
docker exec -i openlr-postgres psql -U openlr openlr_db < backup.sql
```

## Configuration

### Connection Settings

The generic-pg configuration (`configs/generic-pg/application.properties`) contains:

```properties
spring.datasource.url=jdbc:postgresql://openlr_postgres:5432/openlr_db
spring.datasource.username=openlr
spring.datasource.password=openlrpwd
spring.datasource.driver-class-name=org.postgresql.Driver

spring.datasource.hikari.maximum-pool-size=20
spring.datasource.hikari.minimum-idle=5
spring.datasource.hikari.connection-timeout=30000

db_schema=local
roads_table=roads
intersections_table=intersections
cache_size=1000000
```

### Custom Port

```bash
# Start on custom port
POSTGRES_PORT=5433 ./scripts/docker-run.sh postgres up

# Update application.properties
spring.datasource.url=jdbc:postgresql://openlr_postgres:5433/openlr_db
```

### Performance Tuning

The PostgreSQL configuration is optimized for OpenLR workloads. Key settings in `docker-compose-postgres.yml`:

```yaml
command:
  - postgres
  - -c max_connections=200
  - -c shared_buffers=256MB
  - -c effective_cache_size=1GB
  - -c work_mem=4MB
  - -c maintenance_work_mem=64MB
```

To customize, edit `docker/docker-compose-postgres.yml` and restart:

```bash
./scripts/docker-run.sh postgres down
./scripts/docker-run.sh postgres up
```

## Data Management

### Backup

```bash
# Backup to file
docker exec openlr-postgres pg_dump -U openlr openlr_db > backup.sql

# Backup with compression
docker exec openlr-postgres pg_dump -U openlr openlr_db | gzip > backup-$(date +%Y%m%d).sql.gz

# Backup only schema
docker exec openlr-postgres pg_dump -U openlr -s openlr_db > schema.sql

# Backup only data
docker exec openlr-postgres pg_dump -U openlr -a openlr_db > data.sql
```

### Restore

```bash
# Restore from SQL file
docker exec -i openlr-postgres psql -U openlr openlr_db < backup.sql

# Restore from compressed file
gunzip -c backup.sql.gz | docker exec -i openlr-postgres psql -U openlr openlr_db
```

### Export/Import Specific Tables

```bash
# Export intersections
docker exec openlr-postgres psql -U openlr -d openlr_db -c \
  "COPY local.intersections TO STDOUT WITH CSV HEADER" > intersections.csv

# Import intersections
docker exec -i openlr-postgres psql -U openlr -d openlr_db -c \
  "COPY local.intersections FROM STDIN WITH CSV HEADER" < intersections.csv
```

## Troubleshooting

### Connection Refused

**Problem:** Application cannot connect to PostgreSQL.

**Solution:**
1. Check PostgreSQL is running:
   ```bash
   ./scripts/docker-run.sh postgres status
   ```

2. Verify network:
   ```bash
   docker network inspect openlr_network
   ```

3. Check both containers are on same network:
   ```bash
   docker inspect openlr-postgres | grep NetworkMode
   docker inspect openlr-generic-pg | grep NetworkMode
   ```

### Port Already in Use

**Problem:** Port 5432 is already occupied.

**Solution:**
```bash
# Use custom port
POSTGRES_PORT=5433 ./scripts/docker-run.sh postgres up

# Update application.properties
vim configs/generic-pg/application.properties
# Change: spring.datasource.url=jdbc:postgresql://openlr_postgres:5433/openlr_db
```

### Slow Queries

**Problem:** Queries are taking too long.

**Solution:**
1. Check if indexes exist:
   ```sql
   \d+ local.roads
   \d+ local.intersections
   ```

2. Analyze query performance:
   ```sql
   EXPLAIN ANALYZE SELECT * FROM local.roads WHERE from_int = 123;
   ```

3. Update statistics:
   ```sql
   ANALYZE local.roads;
   ANALYZE local.intersections;
   ```

4. Rebuild indexes:
   ```sql
   REINDEX TABLE local.roads;
   REINDEX TABLE local.intersections;
   ```

### Out of Memory

**Problem:** PostgreSQL crashes with OOM errors.

**Solution:**

Adjust memory settings in `docker-compose-postgres.yml`:

```yaml
shm_size: 512mb  # Increase from 256mb

command:
  - -c shared_buffers=512MB  # Increase from 256MB
  - -c work_mem=8MB          # Increase from 4MB
```

Then restart:
```bash
./scripts/docker-run.sh postgres down
./scripts/docker-run.sh postgres up
```

### Data Corruption

**Problem:** Database reports corruption after crash.

**Solution:**
```bash
# Stop PostgreSQL
./scripts/docker-run.sh postgres down

# Restore from backup
docker compose -f docker/docker-compose-postgres.yml up -d
./scripts/docker-run.sh postgres psql < backup.sql
```

## Monitoring

### Check Database Size

```sql
SELECT pg_size_pretty(pg_database_size('openlr_db')) AS database_size;
```

### Check Table Sizes

```sql
SELECT
  schemaname,
  tablename,
  pg_size_pretty(pg_total_relation_size(schemaname||'.'||tablename)) AS size
FROM pg_tables
WHERE schemaname = 'local'
ORDER BY pg_total_relation_size(schemaname||'.'||tablename) DESC;
```

### Active Connections

```sql
SELECT count(*) as active_connections
FROM pg_stat_activity
WHERE datname = 'openlr_db';
```

### Long Running Queries

```sql
SELECT
  pid,
  now() - query_start AS duration,
  query
FROM pg_stat_activity
WHERE state = 'active'
  AND now() - query_start > interval '1 minute';
```

## Migration Guide

### From Existing PostgreSQL Database

If you have an existing PostgreSQL database with map data:

1. Dump existing data:
   ```bash
   pg_dump -h oldhost -U user -d olddb -t roads -t intersections > migration.sql
   ```

2. Start PostgreSQL sidecar:
   ```bash
   ./scripts/docker-run.sh postgres up
   ```

3. Import data:
   ```bash
   docker exec -i openlr-postgres psql -U openlr -d openlr_db < migration.sql
   ```

### From SQLite Database

See the SQLite-to-PostgreSQL migration script in `scripts/migrate-sqlite-to-postgres.sh` (to be created).

## Architecture

```
┌─────────────────────────────────────────┐
│  OpenLR WebTool Container               │
│  (openlr-generic-pg)                    │
│                                         │
│  Spring Boot Application                │
│  ├─ Generic PG Profile                  │
│  ├─ JdbcTemplate                        │
│  └─ Connection Pool (HikariCP)          │
│      ↓                                  │
└──────┼──────────────────────────────────┘
       │ openlr_network (bridge)
       ↓
┌─────────────────────────────────────────┐
│  PostgreSQL + PostGIS Container         │
│  (openlr-postgres)                      │
│                                         │
│  PostgreSQL 17 + PostGIS 3.5            │
│  ├─ openlr_db database                  │
│  │  └─ local schema                     │
│  │     ├─ roads table                   │
│  │     └─ intersections table           │
│  └─ Persistent volume                   │
│     (openlr_postgres_data)              │
└─────────────────────────────────────────┘
```

## Next Steps

1. **Load Map Data**: Import your OpenLR-compatible map data
2. **Test Encoding**: Use the REST API to test OpenLR encoding
3. **Test Decoding**: Decode OpenLR references to verify accuracy
4. **Optimize**: Tune cache settings and database parameters
5. **Monitor**: Set up monitoring for production use

## See Also

- [Generic PG Configuration README](../configs/generic-pg/README.md)
- [OpenLR WebTool Documentation](../README.md)
- [PostgreSQL Documentation](https://www.postgresql.org/docs/)
- [PostGIS Documentation](https://postgis.net/documentation/)