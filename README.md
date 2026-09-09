
A Spring Boot application for OpenLR encoding/decoding using PostgreSQL + PostGIS with a minimal OpenLR schema. Includes an interactive web visualization tool for decoding and analyzing OpenLR location references.

## Features

- **Interactive Web Visualization Tool**: Browser-based interface with Leaflet maps, measurement tools, and LRP display
- **PostgreSQL + PostGIS**: Spatial database backend
- **Minimal OpenLR Schema**: Two-table design (`local.roads` and `local.intersections`)
- **Docker Compose Deployment**: Containerized with database setup sidecar
- **Map Converters**: MultiNet-R and Orbis/OSM networks into the OpenLR schema
- **Multiple Decode Formats**: JSON POST, form data POST, and GET query parameters
- **GeoJSON Responses**: GeoJSON FeatureCollection output
- **Configurable Decoder**: Multiple decoding profiles (default, relaxed, strict)
- **Database Setup Sidecar**: Data loading with psql and ogr2ogr tools

## Quick Start

### Prerequisites
- Docker and Docker Compose
- (Optional) Java 17+ and Gradle for local development

### Docker Deployment (Recommended)

#### 1. Build the Containers

```bash
cd docker
./dc build
```

#### 2. Start PostgreSQL and Application

```bash
./dc up
```

The application will be available at `http://localhost:8081`

#### 3. Load Map Data

Two converters ship in [`tools/`](tools/) and produce the CSVs the loader wants. Both
are covered in full under [Map conversion](#map-conversion) below.

```bash
# From a MultiNet-R distribution
uv run tools/mnr_to_pg_csv.py -i /path/to/mnr-distribution -o ~/my-map-data

# ...or from an Orbis PBF, via osm_tag_mapper
uv run tools/orbis_to_pg_csv.py -i roads.csv -o ~/my-map-data

# Then load, from either
cp tools/dbsetup.sh ~/my-map-data/dbsetup.sh
cd docker && ./dc setup ~/my-map-data
```

For a source neither converter handles, write your own `dbsetup.sh` in the data
directory; `./dc setup` runs it inside a container that has `psql` and `ogr2ogr`.
[`tools/dbsetup.sh`](tools/dbsetup.sh) is a working example — it drops the indexes,
`COPY`s both files, rebuilds them, `ANALYZE`s, and then reports row counts, the
`flowdir` distribution and any road whose endpoints do not resolve. The schema it
must populate is in [Database Schema](#database-schema).

**On macOS, `/tmp` is not shared with Docker Desktop.** `./dc setup /tmp/...` mounts
an empty directory and reports `No /data/dbsetup.sh found`. Put the data somewhere
under `/Users`.

#### 4. Test the API

```bash
# JSON POST with single profile
curl -X POST http://localhost:8081/api/v1/decode \
  -H "Content-Type: application/json" \
  -d '{"openLrCode": "CwV/mSIeQA4kBgFxAJ8OEA==", "props": "default"}'

# JSON POST with fallback profiles (try strict, then relaxed, then default)
curl -X POST http://localhost:8081/api/v1/decode \
  -H "Content-Type: application/json" \
  -d '{"openLrCode": "CwV/mSIeQA4kBgFxAJ8OEA==", "props": "strict,relaxed,default"}'

# Form data POST with fallback profiles
curl -X POST http://localhost:8081/api/v1/decode \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "openLrCode=CwV/mSIeQA4kBgFxAJ8OEA==" \
  -d "props=strict,relaxed,default"

# HTTPie with fallback profiles
http --ignore-stdin -f http://localhost:8081/api/v1/decode \
  openLrCode=CwV/mSIeQA4kBgFxAJ8OEA== \
  props=strict,relaxed,default

# Returns GeoJSON FeatureCollection with road segments
# The meta.propertySet field indicates which profile succeeded
```

See [docker/README.md](docker/README.md) for complete deployment documentation.

## Web Visualization Tool

The OpenLR WebTool includes a browser-based visualization interface for interactive OpenLR decoding and map analysis.

### Overview

The webapp provides:
- **Interactive Map Interface**: Leaflet-based map for visualizing OpenLR decoding results
- **Client-Side OpenLR Parsing**: Uses openlr-js library to parse OpenLR binary codes
- **Location Reference Point (LRP) Display**: Shows LRPs even when backend decoding fails
- **Measurement Tools**: Distance and bearing measurement tools
- **Configurable Decoder**: Select between default, strict, and relaxed decoding profiles
- **Detailed Diagnostics**: Comprehensive error reporting with diagnostic information

### Architecture

The webapp consists of three Docker containers:
- **postgres** (port 5432): PostGIS database with OpenLR schema
- **app** (port 8081): Spring Boot backend API
- **webapp** (port 3000): Node.js/Express server serving static frontend

The frontend uses:
- **Leaflet 1.9.4**: Interactive map library
- **openlr-js v3.x**: Client-side OpenLR binary parsing
- **Leaflet TextPath**: Arrow visualization for bearing measurements

### Quick Start

#### Prerequisites
- Docker and Docker Compose
- Map data loaded into PostgreSQL (see Quick Start section above)

#### 1. Start All Services

```bash
cd docker
./dc up
```

This starts all three containers:
- PostgreSQL + PostGIS (localhost:5432)
- OpenLR API backend (localhost:8081)
- Webapp frontend (localhost:3000)

#### 2. Access the Web Interface

Open your browser to:
```
http://localhost:3000
```

#### 3. Custom Port Configuration

To run the webapp on a different port:

```bash
# Run webapp on port 3002
cd docker
WEBAPP_PORT=3002 ./dc up

# Access at http://localhost:3002
```

#### 4. Managing the Webapp Container

```bash
cd docker

# Build webapp only
./dc build webapp
WEBAPP_PORT=3002 docker-compose build webapp

# Restart webapp only
./dc restart webapp
WEBAPP_PORT=3002 docker-compose restart webapp

# View webapp logs
./dc logs webapp
WEBAPP_PORT=3002 docker-compose logs -f webapp

# Stop webapp only
WEBAPP_PORT=3002 docker-compose stop webapp

# Rebuild and restart webapp
WEBAPP_PORT=3002 docker-compose build webapp && \
WEBAPP_PORT=3002 docker-compose up -d webapp
```

### Features and Usage

#### Decoding OpenLR Codes

1. **Enter OpenLR Code**: Paste a base64-encoded OpenLR code in the input field
   - Example: `CwV/mSIeQA4kBgFxAJ8OEA==`

2. **Select Decoder Profile**: Choose from dropdown:
   - `default`: Balanced parameters for general use
   - `strict`: Higher matching requirements
   - `relaxed`: More lenient matching

3. **Click Decode**: The system will:
   - Parse the OpenLR binary using openlr-js (client-side)
   - Send to backend for map matching
   - Display results on the map

#### Understanding the Display

**When decoding succeeds:**
- **Decoded Path**: Displayed as a blue polyline on the map
- **LRP Markers**: Location Reference Points shown as numbered markers
- **Offset Markers**: Start/end points shown with offset adjustments
- **Sidebar Information**:
  - Offsets (absolute and relative percentages)
  - Path metadata (length, applied offsets)
  - LRP details (coordinates, bearing, FRC, FOW, distance)
  - Segment information (FRC, FOW, length)

**When decoding fails:**
- **LRPs Still Displayed**: Shows parsed Location Reference Points even without successful map matching
- **Error Details**: Click the red error banner for comprehensive diagnostics including:
  - Error type and category
  - Backend response details
  - HTTP status codes
  - Request details
  - Client-side parsing status
  - Troubleshooting hints

#### Measurement Tools

##### Distance Tool (📏)

1. Click the ruler button (📏) in the top-left map controls
2. Click points on the map to measure distances
3. See segment distances and cumulative total
4. Double-click to finish measurement
5. Click the ruler button again to clear and start new measurement

**Display format:**
```
Segment: 123.45m
Total: 456.78m
```

##### Bearing Tool (🧭)

1. Click the compass button (🧭) in the top-left map controls
2. Click the first point (origin)
3. Click the second point (destination)
4. See bearing information:
   - Forward bearing (A→B)
   - Reverse bearing (B→A)
   - Distance between points
5. Visual arrow shows direction
6. Click the compass button again to clear and start new measurement

**Display format:**
```
Bearing: 123.4°
Reverse: 303.4°
Distance: 456.78m
```

**Bearing values:**
- 0° = North
- 90° = East
- 180° = South
- 270° = West

#### Interactive Segment Selection

- **Click Segment Rows**: Click any row in the Segments table to:
  - Highlight the segment on the map (yellow highlight)
  - Zoom to the segment
  - Display segment details in a popup

- **Hover Over Rows**: Hover to preview segment location

#### Sidebar Controls

- **Collapsible Sections**: Click section headers to expand/collapse:
  - Offsets
  - Path Metadata
  - Location Reference Points
  - Segments

- **Resizable Sidebar**: Drag the right edge of the sidebar to resize

- **Toggle Sidebar**: Click the arrow button on the left edge to show/hide

#### Error Diagnostics

When decoding fails, click the red error banner to view:

1. **Error Basics**: Type, message, category
2. **Connection Errors**: Backend unavailable or network issues
3. **HTTP Errors**: Status codes and server responses
4. **Backend Responses**: Detailed error messages from the API
5. **Request Details**: OpenLR code, decoder profile, timestamp
6. **Client-Side Status**: Whether openlr-js successfully parsed the binary
7. **Stack Trace**: Full JavaScript error stack (when available)
8. **Troubleshooting Hints**: Suggested actions based on error type

### Configuration

#### Environment Variables

The webapp container accepts the following environment variables:

| Variable | Default | Description |
|----------|---------|-------------|
| `PORT` | `3000` | Port webapp listens on (inside container) |
| `WEBAPP_PORT` | `3000` | Port exposed on host machine |
| `API_URL` | `http://app:8081` | Backend API URL |

#### Example Configurations

**Custom backend API:**
```bash
cd docker
API_URL=http://custom-api:9000 WEBAPP_PORT=3002 ./dc up webapp
```

**Production deployment:**
```bash
cd docker
WEBAPP_PORT=80 API_URL=http://api.production.com:8081 ./dc up webapp
```

#### Health Checks

The webapp container includes health checks:
- **Interval**: Every 30 seconds
- **Timeout**: 3 seconds
- **Retries**: 3
- **Start Period**: 10 seconds

Check webapp health:
```bash
docker ps --filter name=openlr-webapp
curl http://localhost:3000/health
```

### File Structure

```
webapp/
├── Dockerfile              # Node.js Alpine-based container
├── package.json           # Node.js dependencies (express, http-proxy-middleware)
├── server.js              # Express server with API proxy
└── public/
    ├── index.html         # Main application page
    ├── css/
    │   └── style.css      # Application styles
    └── js/
        └── app.js         # Main application logic
```

### Development

#### Local Development (Without Docker)

```bash
cd webapp

# Install dependencies
npm install

# Set environment variables
export PORT=3000
export API_URL=http://localhost:8081

# Start development server
npm run dev

# Access at http://localhost:3000
```

#### Making Changes

1. **Edit Files**: Modify files in `webapp/public/`
   - `public/index.html` - HTML structure
   - `public/css/style.css` - Styling
   - `public/js/app.js` - Application logic

2. **Update Cache Buster**: Increment version in `index.html`:
   ```html
   <script src="/js/app.js?v=24"></script>
   ```

3. **Rebuild Container**:
   ```bash
   cd docker
   WEBAPP_PORT=3002 docker-compose build webapp
   WEBAPP_PORT=3002 docker-compose up -d webapp
   ```

4. **Verify Changes**: Check logs and reload browser
   ```bash
   docker logs openlr-webapp
   ```

#### Adding External Libraries

Edit `webapp/public/index.html` to add CDN libraries:

```html
<!-- Example: Adding a new Leaflet plugin -->
<script src="https://unpkg.com/leaflet-plugin@1.0.0/dist/plugin.js"></script>
```

For Node.js dependencies, edit `webapp/package.json` and rebuild.

#### Debugging

**Browser Console**: The webapp does not output console logs in production. Use browser DevTools:
- Network tab: Monitor API requests
- Elements tab: Inspect DOM and styles
- Sources tab: Debug JavaScript with breakpoints

**Server Logs**: View Express server output:
```bash
docker logs -f openlr-webapp
```

**API Proxy**: The webapp proxies `/api/*` requests to the backend:
```
http://localhost:3000/api/v1/decode → http://app:8081/api/v1/decode
```

### Troubleshooting

#### Webapp Container Won't Start

```bash
# Check container status
docker ps -a | grep openlr-webapp

# View logs
docker logs openlr-webapp

# Check dependencies
docker ps | grep openlr-tool
docker ps | grep openlr-postgres

# Restart with fresh build
cd docker
WEBAPP_PORT=3002 docker-compose stop webapp
WEBAPP_PORT=3002 docker-compose rm -f webapp
WEBAPP_PORT=3002 docker-compose build --no-cache webapp
WEBAPP_PORT=3002 docker-compose up -d webapp
```

#### Cannot Connect to Backend

**Symptoms**: Decoding fails with "Network/Connection Error"

**Solutions**:
1. Verify backend is running: `docker ps | grep openlr-tool`
2. Check backend health: `curl -f http://localhost:8081/api/v1/health`
3. Verify proxy configuration in `webapp/server.js`
4. Check container networking: `docker network inspect openlr_network`

#### Map Not Loading

**Symptoms**: Blank map area, no tiles

**Solutions**:
1. Check browser console for errors
2. Verify internet connection (map tiles load from external CDN)
3. Check if Leaflet CSS loaded: View page source, verify CSS link
4. Try different browser or clear browser cache

#### Sidebar Not Showing

**Symptoms**: Decode succeeds but sidebar doesn't appear

**Solutions**:
1. Check if toggle button visible (left edge of map)
2. Clear browser cache and hard reload (Ctrl+Shift+R / Cmd+Shift+R)
3. Check browser console for JavaScript errors
4. Verify cache buster version in URL: `app.js?v=24`

#### Port Already in Use

**Symptoms**: Container fails to start, port conflict error

**Solutions**:
```bash
# Use different port
WEBAPP_PORT=3002 ./dc up

# Find process using port 3000
lsof -i :3000

# Stop conflicting container
docker stop <container-using-port>
```

### Local Development

#### Build and Run

```bash
# Build the application
./gradlew bootJar

# Run with local PostgreSQL
./gradlew bootRun --args='--spring.config.location=file:config/application.properties'
```

#### Configuration

Edit `config/application.properties` to point to your PostgreSQL instance:

```properties
# Database connection
spring.profiles.active=generic_pg_mapdb
spring.datasource.url=jdbc:postgresql://localhost:5432/openlr_db
spring.datasource.username=openlr
spring.datasource.password=openlrpwd

# Server port (default: 8081)
server.port=8081
```

**Change listening port:**

For local development:
```bash
# Via command line
./gradlew bootRun --args='--server.port=9000'

# Or edit config/application.properties
server.port=9000
```

For Docker deployment:
```bash
# Use PORT environment variable
PORT=9000 ./docker/dc up
```

## Map conversion

Converting a source network into the two-table schema is the one part of setup that
depends on where your map comes from. Two converters are supported.

| Source | Converter | Input |
|--------|-----------|-------|
| MultiNet-R distribution | [`tools/mnr_to_pg_csv.py`](tools/mnr_to_pg_csv.py) | The distribution directory, containing country tarballs |
| Orbis / OSM PBF | [`tools/orbis_to_pg_csv.py`](tools/orbis_to_pg_csv.py) | A CSV from [`osm_tag_mapper`](https://github.com/tomtom-international/osm_tag_mapper) |

Both emit `roads.csv` and `intersections.csv` for `local.roads` and
`local.intersections`, and both are loaded the same way, with
[`tools/dbsetup.sh`](tools/dbsetup.sh) and `./dc setup`.

### Running the converters

Each is a self-contained [uv](https://docs.astral.sh/uv/) script with its
dependencies declared inline, so there is nothing to install or activate:

```bash
uv run tools/mnr_to_pg_csv.py --help
uv run tools/orbis_to_pg_csv.py --help
```

The transformations themselves are DuckDB SQL — `mnr_to_pg_csv.sql` and
`orbis_to_pg_csv.sql`, which sit beside the drivers and are the files to read if you
want to know exactly what an attribute becomes. They run through the DuckDB Python
package, so no `duckdb` CLI is needed and the drivers behave the same on macOS,
Linux and Windows.

`tools/dbsetup.sh` is the exception and is deliberately shell: it executes *inside*
the Linux `db-setup` container, not on your machine.

### MultiNet-R

```bash
uv run tools/mnr_to_pg_csv.py -i /path/to/distribution -o ~/mnr-eur

cp tools/dbsetup.sh ~/mnr-eur/dbsetup.sh
cd docker && ./dc setup ~/mnr-eur
```

Point it at a whole distribution, not one country: it searches recursively for
`*.tar.gz` and merges every country it finds into one network. It reads three tables
from each tarball — `MNR_Netw_Route_Link` for attributes, `MNR_Netw_Geo_Link` for
geometry and length, `MNR_Junction` for nodes — and skips any archive that does not
contain all three, reporting it.

Where tarballs sit under a `core/` directory only those are considered, since that is
where the three tables live and decompressing a content tarball to discover otherwise
costs minutes. `--all-tars` widens the search.

| Option | Effect |
|--------|--------|
| `--include-back-roads` | Keep `BACK_ROAD <> 0`. The default drops destination roads, service roads and driveways — 8.6% of links in the Netherlands |
| `--drivable-only` | Drop walkways, stairs, pedestrian zones and parking. The default keeps them, mapped to OpenLR FOW 7 (OTHER) |
| `--all-tars` | Consider every tarball, not only those under `core/` |
| `--memory-limit` | Cap DuckDB's memory, e.g. `8GB`. Germany peaks near 17 GB unconstrained; with a cap it spills to the work directory instead |
| `--keep-work` | Leave the extracted tables and the DuckDB database for inspection |

Measured on the six-country EUR sample — **under four minutes** end to end,
extraction included, for **15,628,506 roads and 13,345,984 intersections**:

| Country | Route links read | Kept | Convert |
|---------|-----------------:|-----:|--------:|
| deu | 16,209,628 | 11,652,448 | 124 s |
| nld | 2,625,345 | 2,400,680 | 19 s |
| bel | 1,604,194 | 1,467,379 | 13 s |
| lux | 120,064 | 99,603 | 1 s |
| and | 7,414 | 6,822 | 0 s |
| mco | 1,899 | 1,895 | 0 s |

`meta` carries the MN-R `FEAT_ID`, so a decoded path hands you the identifiers your
source network uses, and `/api/v1/encode` accepts the same values back.

### Orbis / OSM

Conversion is two stages. Deriving OpenLR FRC and FOW from Orbis and OSM tags is a
substantial body of tested logic that lives in
**[`osm_tag_mapper`](https://github.com/tomtom-international/osm_tag_mapper)** and is
not duplicated here:

```bash
# Stage 1 - tags to OpenLR attributes (separate repository)
uv run python -m osm_tag_mapper --input nld-orbis.pbf --output /tmp/nld.csv

# Stage 2 - attributes to this repository's schema
uv run tools/orbis_to_pg_csv.py -i /tmp/nld.csv -o ~/orbis-nld

cp tools/dbsetup.sh ~/orbis-nld/dbsetup.sh
cd docker && ./dc setup ~/orbis-nld
```

Stage 1 must be recent enough to emit a `way_id` column; the converter refuses a CSV
without one rather than falling back to a synthetic key.

`-m, --meta` chooses what `roads.meta` carries. The default `way` — the Orbis way id
— is the only value guaranteed unique, which the API requires:

| `--meta` | Content | Unique? |
|----------|---------|---------|
| `way` (default) | Orbis way id | **Yes** |
| `gers` | GERS entity reference, falling back to the OSM id | No |
| `osm` | Original OSM way id | No |
| `both` | `gers_id\|osm_id` | No |

**Orbis input only.** The converter assumes each row is one junction-to-junction
link. Orbis satisfies that; a plain OSM or Geofabrik extract does not — about a
quarter of its highway ways run *through* a junction mid-way, and since
`osm_tag_mapper` emits one row per way without splitting, those junctions would
simply be absent. Supporting Geofabrik means adding node-degree splitting upstream
first.

### Conversion notes

The details that are easy to get wrong, and the reasoning behind each mapping, are in
[`tools/README.md`](tools/README.md). The architectural decisions — why `meta` is the
public identifier, why `flowdir` reserves unrecognised values, why the converters are
DuckDB rather than Python — are recorded in [`docs/adr/`](docs/adr/README.md).

### Verifying a load

```bash
uv run tools/verify_api.py --sample 500
```

Exercises what unit tests cannot: decoding a corpus against the real network,
response shapes, the error contract, cache bounds, and the decode/encode round trip.
Add `--disruptive` to confirm a database failure answers 503 rather than 400.

## Database Schema

The application uses a minimal two-table schema in PostgreSQL:

### `local.roads` Table

Road segments representing the map network.

| Column | Type | Description |
|--------|------|-------------|
| `id` | bigint | Unique road segment identifier (positive/negative for direction) |
| `meta` | text | Optional metadata/UUID |
| `flowdir` | smallint | Traversability, `1`/`2`/`3` only (enforced by a CHECK constraint): `1`=two-way, `2`=one-way against digitisation (`to_int`→`from_int`), `3`=one-way with digitisation (`from_int`→`to_int`) |
| `fow` | smallint | Form of way classification |
| `frc` | smallint | Functional road class (0-7, 0=motorway, 7=other) |
| `geom` | geometry(LineString,4326) | Line geometry in WGS84 |
| `len` | double precision | Length in meters |
| `from_int` | bigint | Starting intersection ID |
| `to_int` | bigint | Ending intersection ID |

### `local.intersections` Table

Junction points where road segments connect.

| Column | Type | Description |
|--------|------|-------------|
| `id` | bigint | Unique intersection identifier |
| `meta` | text | Optional metadata/UUID |
| `geom` | geometry(Point,4326) | Point geometry in WGS84 |

Connection string: `postgresql://openlr:openlrpwd@postgres:5432/openlr_db`

## API Documentation

### Decode OpenLR Location Reference

Decode an OpenLR code to map geometry.

**Endpoint:** `POST /api/v1/decode`

**Three Supported Formats:**

1. **JSON POST:**
```bash
curl -X POST http://localhost:8081/api/v1/decode \
  -H "Content-Type: application/json" \
  -d '{"openLrCode": "CwV/mSIeQA4kBgFxAJ8OEA==", "props": "default"}'
```

2. **Form Data POST:**
```bash
curl -X POST http://localhost:8081/api/v1/decode \
  -H "Content-Type: application/x-www-form-urlencoded" \
  --data-urlencode "openLrCode=CwV/mSIeQA4kBgFxAJ8OEA==" \
  --data-urlencode "props=default"
```

3. **GET with Query Parameters:**
```bash
curl "http://localhost:8081/api/v1/decode?openLrCode=CwV/mSIeQA4kBgFxAJ8OEA==&props=default"
```

**Parameters:**
- `openLrCode` (required): Base64-encoded OpenLR location reference
- `props` (optional, default: "default"): Decoding profile(s) to try in order. Can be a single profile or comma-separated list. The decoder attempts each profile sequentially until one succeeds.

**Multiple Profiles (Sequential Fallback):**

Provide comma-separated profiles to try in order until one succeeds:

```bash
# JSON POST format
curl -X POST http://localhost:8081/api/v1/decode \
  -H "Content-Type: application/json" \
  -d '{"openLrCode": "CwV/mSIeQA4kBgFxAJ8OEA==", "props": "strict,relaxed,default"}'

# Form data format
curl -X POST http://localhost:8081/api/v1/decode \
  -d "openLrCode=CwV/mSIeQA4kBgFxAJ8OEA==" \
  -d "props=strict,relaxed,default"

# HTTPie format
http --ignore-stdin -f http://localhost:8081/api/v1/decode \
  openLrCode=CwV/mSIeQA4kBgFxAJ8OEA== \
  props=strict,relaxed,default
```

**Response:** GeoJSON FeatureCollection with decoded road segments.

Each feature carries `properties.id` — the signed road ID, negative where the segment is
traversed against its digitised direction. Collecting those in feature order gives a
`path` that can be posted straight back to `/api/v1/encode`. `properties.meta` carries
the reference into the source network (for Orbis-derived maps, see
[tools/README.md](tools/README.md)). The `meta.propertySet` field indicates which
decoding profile succeeded.

### Encode Map Path to OpenLR

Encode a path of road segments as an OpenLR location reference.

**Endpoint:** `POST /api/v1/encode`

```bash
curl -X POST http://localhost:8081/api/v1/encode \
  -d "path=390249024&path=459554506&path=-580286743" \
  -d "props=default"
```

**Parameters:**
- `path` (required, multiple): Ordered list of `meta` values — the same segment references `/decode` returns, so a decoded path can be re-encoded directly. Prefix a value with `-` to traverse that segment against its digitised direction (e.g., `path=390249024&path=-459554506` runs forward along the first segment, then backward along the second). Only a *leading* `-` is a direction marker; hyphens inside a value, as in a UUID, belong to the identifier. A value must identify exactly one segment — one matching several is rejected rather than resolved arbitrarily
- `positiveOffset` (optional, default: 0): Offset in meters from the start of the path
- `negativeOffset` (optional, default: 0): Offset in meters from the end of the path
- `props` (optional, default: "default"): Encoding profile, resolved against `config/encoding_properties/`. An unknown or malformed name is rejected with 400 rather than silently ignored

### Other Endpoints

```bash
# Purge caches
curl -X POST http://localhost:8081/api/v1/cache/clear

# Cache occupancy, bounds, hit rates and evictions
curl http://localhost:8081/api/v1/cache/stats

# Reload configuration
curl -X POST http://localhost:8081/api/v1/properties/reload

# Get count of roads and intersections
curl http://localhost:8081/api/v1/stats

# Find roads within a given radius of lat/lon
curl "http://localhost:8081/api/v1/roads/near?lon=-0.1276&lat=51.5074&distance=100"

# Get roads matching a metadata value
curl "http://localhost:8081/api/v1/roads?meta=road_12345"

# Find details of a road with a given id
curl http://localhost:8081/api/v1/roads/123

# Health check (returns status, road count, and intersection count)
curl http://localhost:8081/api/v1/health
```

## Configuration

### Decoding Profiles

The `props` parameter selects which decoder configuration to use:

- **`default`** - Balanced parameters for general use
- **`relaxed`** - More lenient matching (lower rating requirements)
- **`strict`** - Strict matching (higher rating requirements)

Property files are located in `config/decoding_properties/`.

### Key Decoder Parameters

From `config/decoding_properties/default.properties`:

- `BearingDistance=20` - Distance for bearing calculation
- `MaxNodeDistance=100` - Maximum distance to search for nodes
- `MinimumAcceptedRating=600` - Minimum rating to accept a match
- `FRC_Variance=2` - Allowed functional road class variance
- `MaxNumberRetries=3` - Maximum retry attempts

### Customizing Configuration

1. Edit property files in `config/decoding_properties/` or `config/encoding_properties/`
2. Create new profiles by adding new `.properties` files
3. Restart the application to apply changes

See [docker/README.md](docker/README.md) for detailed configuration documentation.

## Docker Management

The `dc` script in the `docker/` directory provides convenient commands:

```bash
cd docker

# Build
./dc build

# Start/stop
./dc up
./dc down
./dc restart app

# Logs and monitoring
./dc logs app
./dc ps

# Database setup
./dc setup /path/to/data

# Database operations
./dc exec postgres psql -U openlr -d openlr_db

# Cleanup
./dc clean
```

See [docker/README.md](docker/README.md) for complete documentation.

## Development

### Project Structure

```
.
├── main/               # Spring Boot application (single Gradle module)
│   └── src/main/kotlin/…/tool/
│       ├── WebtoolApplication.kt
│       ├── controller/  # REST API controllers
│       ├── model/       # Request/response data models
│       └── service/     # OpenLR and map database services
├── database/           # SQL schema (schema.sql)
├── config/             # Application configuration
│   ├── application.properties
│   ├── decoding_properties/
│   └── encoding_properties/
├── webapp/             # Web visualization tool
│   ├── Dockerfile
│   ├── package.json
│   ├── server.js       # Node.js/Express server
│   └── public/         # Static frontend files
│       ├── index.html
│       ├── css/
│       └── js/
└── docker/             # Docker Compose deployment
    ├── docker-compose.yml
    ├── dc              # Management script
    ├── db-setup/       # Database setup sidecar
    └── README.md       # Complete deployment docs
```

### Build Commands

```bash
# Build all modules
./gradlew build

# Create executable JAR
./gradlew bootJar

# Run tests
./gradlew test

# Build Docker image
cd docker && ./dc build
```

### Testing

```bash
# Run all tests
./gradlew test

# Run specific module tests
./gradlew :main:test
```

## Production Deployment

### Recommended Setup

```bash
# 1. Build containers
cd docker
./dc build

# 2. Start services
PORT=8080 JAVA_OPTS="-XX:MaxRAMPercentage=75" ./dc up

# 3. Load map data
./dc setup /path/to/production-data

# 4. Verify health
./dc ps
curl -f http://localhost:8080/api/v1/health
```

### Health Checks

Both PostgreSQL and the application have health checks:

- **PostgreSQL**: Every 10s, 5s timeout, 5 retries
- **Application**: Every 30s, 3s timeout, 3 retries

### Resource Requirements

- **Minimum RAM**: 40GB recommended (PostgreSQL defaults to ~16GB tuning + 24GB JVM heap)
- **PostgreSQL**: 4GB shared_buffers, 12GB effective_cache_size
- **Application**: heap sized by `-XX:MaxRAMPercentage=75`, so it tracks the container limit. Pin it with `JAVA_OPTS` only where the envelope is known — a fixed `-Xmx` larger than the container gets the process OOM-killed instead of collecting

### Backup and Recovery

```bash
# Backup PostgreSQL data
docker exec openlr-postgres pg_dump -U openlr openlr_db > backup.sql

# Restore PostgreSQL data
cat backup.sql | docker exec -i openlr-postgres psql -U openlr -d openlr_db
```

## Documentation

- **[docker/README.md](docker/README.md)** - Complete Docker deployment guide
- **[docker/db-setup/README.md](docker/db-setup/README.md)** - Database setup sidecar documentation
- **[config/README.md](config/README.md)** - Configuration file documentation
- **[SPEC.md](SPEC.md)** - What the tool guarantees, as numbered requirements
- **[tools/README.md](tools/README.md)** - Map converters and the verification harness
- **[docs/adr/](docs/adr/README.md)** - Architecture decision records
- **[docs/api/openapi.yml](docs/api/openapi.yml)** - OpenAPI 3.0 specification
