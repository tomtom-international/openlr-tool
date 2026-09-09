# OpenLR Tool — Specification

**Status:** Descriptive (documents the system as built at commit `5a0cb66`)
**Version:** 0.0.1-SNAPSHOT (app), v0.1.0 (config `application.version`)
**License:** Apache-2.0
**Owner:** TomTom / `com.tomtom.openlr`

## About this document

This is a *specification*: it states what the system must do, in numbered, checkable
requirements, separately from how it is built. The `README.md` tells a user how to run
the tool; this document tells a reviewer, integrator, or reimplementer what the tool
guarantees, what it deliberately does not do, and where the current implementation and
its documentation disagree.

Every requirement below is either implemented and verifiable today, or explicitly
listed in [§10 Known gaps](#10-known-gaps-and-drift). Nothing here is aspirational
without being marked as such.

---

## 1. Purpose and scope

### 1.1 Purpose

OpenLR Tool is a self-hosted service for **decoding and encoding OpenLR location
references against a user-supplied road network**, plus an interactive web front end
for inspecting the results on a map.

OpenLR is a map-agnostic standard for referencing a stretch of road as a compact
binary code, so that a location described on one map can be recovered on a different
map. Recovering the location ("decoding") is a map-matching problem whose outcome
depends on both the map and the matching parameters. This tool exists to make that
process observable: to run a decode against a specific network with a specific
parameter profile, and to see exactly what came back and why.

### 1.2 Primary use cases

| # | Use case |
|---|----------|
| UC-1 | Decode an OpenLR code against a loaded network and obtain the matched geometry |
| UC-2 | Compare decoder parameter profiles by re-decoding the same code under each |
| UC-3 | Diagnose a failing decode visually — see the reference points even when matching fails |
| UC-4 | Encode a known path of network segments into an OpenLR code (round-trip testing) |
| UC-5 | Inspect the loaded network: segment attributes, segments near a coordinate, counts |

### 1.3 In scope

- Binary (base64) OpenLR physical format.
- OpenLR **line locations** only.
- A single road network held in PostgreSQL + PostGIS under a fixed two-table schema.
- Multiple named decoder parameter profiles, selectable per request, with ordered fallback.
- A REST API and a browser front end over that API.
- Containerised deployment of database, API, front end, and a data-loading sidecar.

### 1.4 Out of scope (non-goals)

- **Other OpenLR location types** — point-along-line, POI-with-access-point, circle,
  polygon, rectangle, grid, closed-line. The decoder rejects them (FR-1.7).
- **XML physical format.** The `org.openlr:xml` artifact is declared in the version
  catalog but is not a dependency of the application and no XML endpoint exists.
- **Map data ingestion in general.** The tool defines the target schema and ships a
  sidecar with `psql`/`ogr2ogr`; converting an arbitrary source map into that schema
  is the operator's script. The one exception is Orbis: `tools/` ships a converter
  from an `osm_tag_mapper` CSV to this schema (see `tools/README.md`). It is
  deliberately Orbis-only — Orbis ways are already split at junctions, which a plain
  OSM extract does not guarantee.
- **Multi-map / multi-tenant serving.** One process serves one network.
- **Authentication, authorisation, rate limiting, quotas.** None. See NFR-5.
- **Persistence of decode history.** Every request is stateless; nothing is recorded
  beyond application logs.
- **Routing.** Path finding between arbitrary points is not offered; `/encode` takes an
  explicit, ordered, already-connected list of segment IDs.

### 1.5 Definitions

| Term | Meaning |
|------|---------|
| **LRP** | Location Reference Point — a coordinate + bearing + FRC + FOW + distance-to-next, encoded in the OpenLR code |
| **FRC** | Functional Road Class, `FRC_0` (motorway) … `FRC_7` (other) |
| **FOW** | Form of Way — motorway, roundabout, sliproad, etc. |
| **Line / segment / road** | One directed traversal of one row of `local.roads`; a positive ID is the forward traversal, a negative ID the reverse |
| **Intersection / node** | One row of `local.intersections`; endpoints of segments |
| **Profile / property set** | A named `.properties` file of OpenLR decoder or encoder parameters |
| **Offset** | Distance in metres from the start (positive) or end (negative) of the matched path to the actual location |

---

## 2. System context

```
  Browser
     │  HTTP  (static assets, /api/* proxied)
     ▼
  webapp        Node 18 / Express        :3000
     │  HTTP  /api/v1/*
     ▼
  app           Kotlin / Spring Boot 3   :8081
     │  JDBC
     ▼
  postgres      PostGIS 3.4 / PG 16      :5432
     ▲
     │  psql / ogr2ogr  (one-shot, `setup` profile)
  db-setup      operator's dbsetup.sh
```

The four components are the four Compose services. `app` is the only component that
talks to the database; `webapp` holds no state and reaches the API only by proxy.

### 2.1 Technology constraints

| Component | Constraint |
|-----------|-----------|
| API | Kotlin 1.9.25, JVM target 17, Spring Boot 3.2.7 (built on JDK 17, run on Temurin 21 JRE) |
| OpenLR | `org.openlr:{map,binary,decoder,encoder}:1.4.3` (open-source reference implementation) |
| Geometry | JTS 1.19.0, WKB read from PostGIS |
| Database | PostgreSQL 16 with PostGIS 3.4, geometry SRID 4326 |
| Front end | Node ≥ 18, Express 4, Leaflet 1.9.4, `openlr-js` (CDN), Leaflet TextPath 1.2.3 |

---

## 3. Functional requirements — Decode

### FR-1 Decode an OpenLR code

- **FR-1.1** The service SHALL accept a decode request in three equivalent forms:
  `POST /api/v1/decode` with `application/json`, `POST /api/v1/decode` with
  `application/x-www-form-urlencoded`, and `GET /api/v1/decode` with query parameters.
- **FR-1.2** Parameters SHALL be `openLrCode` (required, base64 binary OpenLR) and
  `props` (optional, default `default`).
- **FR-1.3** `props` MAY be a comma-separated list of profile names. The service SHALL
  attempt each profile **in the given order** and stop at the first that yields a valid
  location. Whitespace around names SHALL be trimmed.
- **FR-1.4** On success the response SHALL be `200 OK` with a GeoJSON
  `FeatureCollection`, one `Feature` per matched segment, in path order.
- **FR-1.5** Each feature SHALL carry `geometry` of type `LineString` (WGS84
  `[lon, lat]` pairs) and `properties`:

  | Field | Type | Meaning |
  |-------|------|---------|
  | `meta` | string | The segment's `meta` column, `""` if absent. The only segment identifier the API exposes |
  | `direction` | boolean | `true` if traversed forward (positive line ID) |
  | `frc` | string | e.g. `FRC_3` |
  | `fow` | string | e.g. `SINGLE_CARRIAGEWAY` |
  | `lengthMeters` | number | Segment length as reported by the OpenLR map layer |

- **FR-1.6** The collection SHALL carry a `meta` object:

  | Field | Type | Meaning |
  |-------|------|---------|
  | `posOff` | int | Positive offset in metres |
  | `negOff` | int | Negative offset in metres |
  | `openLR` | string | The submitted code, echoed |
  | `propertySet` | string | **The profile that actually succeeded** — the key output when fallback is used |
  | `decodingElapsedNanos` | long | Wall-clock decode time, nanoseconds |
  | `wkt` | string | `LINESTRING(...)` of the concatenated path |

- **FR-1.7** If the code decodes to a location that is not a line location, the request
  SHALL fail with reason `Unsupported location type: <type>`.
- **FR-1.8** If every profile fails, or the code cannot be parsed, the response SHALL be
  `400 Bad Request` with `{"msg": "Failed to decode <code>", "reason": "<detail>"}`.
  A malformed code SHALL NOT propagate an exception to the client.
- **FR-1.9** Offsets SHALL be reported, not applied: the returned geometry is the full
  matched path, and consumers trim it using `posOff` / `negOff`.
- **FR-1.10** A segment that the decoder matched but that cannot be re-read from the map
  layer SHALL be omitted from `features` rather than failing the request.
- **FR-1.11** `local.roads.id` SHALL NOT appear in any response. It is an opaque
  internal key that exists because the OpenLR library requires a `Long` per line;
  `meta` is the identifier the API publishes.
- **FR-1.12** The `meta` values of the features, taken in feature order, SHALL be a
  valid `path` for `/api/v1/encode` (FR-2.2), so a decoded path can be re-encoded
  without translation.
- **FR-1.13** A `props` name SHALL match `[A-Za-z0-9_-]{1,64}` and SHALL have a
  corresponding `.properties` file. A name failing either check SHALL return `400`
  naming the available profiles. A profile SHALL NOT silently fall back to the OpenLR
  library defaults: `meta.propertySet` must always name parameters that were used.
- **FR-1.14** `400` SHALL mean the request was at fault. A failure of the database or
  another dependency SHALL return `503`, never `400`.

---

## 4. Functional requirements — Encode

### FR-2 Encode a path

- **FR-2.1** `POST /api/v1/encode` SHALL accept `path` (repeated, required), and
  optional `positiveOffset`, `negativeOffset` (metres, default `0`) and `props`
  (default `default`).
- **FR-2.2** Each `path` element SHALL be a `local.roads.meta` value — the same
  reference decoding returns (FR-1.12) — optionally prefixed with `-` to traverse that
  segment against its digitised direction. Order is the traversal order. Only a
  *leading* `-` is significant; hyphens within a value (UUIDs, for instance) are part
  of the identifier.
- **FR-2.3** `meta` values are opaque strings, so no element SHALL be rejected on
  shape. An element that is empty after removing the direction prefix SHALL return
  `400` with `Empty path element`.
- **FR-2.4** An element matching no segment SHALL return `400` with
  `No segment found with meta '<value>'`. An element matching more than one SHALL
  return `400` naming the count, rather than choosing arbitrarily — encoding requires
  `meta` to identify exactly one segment (DI-5).
- **FR-2.9** `props` SHALL select an encoder profile from `encoding_properties_dir`,
  validated as decoder profiles are (FR-1.13). It SHALL NOT be accepted and ignored.
- **FR-2.8** A direction the segment does not permit — a reverse prefix on a one-way
  segment, say — SHALL return `400` naming the segment and its `flowdir`, since no
  OpenLR line exists for that traversal (§7.5).
- **FR-2.5** On success the response SHALL be `200 OK` with
  `{"success": true, "openLrCode": "<base64>", "error": null}`.
- **FR-2.6** On encoder rejection the response SHALL be `400` with `success: false` and
  an `error` naming the OpenLR return code.
- **FR-2.7** The caller is responsible for supplying a connected, correctly ordered
  path; the service does not verify connectivity before encoding.

---

## 5. Functional requirements — Network inspection and administration

### FR-3 Network queries

- **FR-3.1** `GET /api/v1/roads/near?lon=&lat=&distance=` SHALL return segments whose
  geometry lies within `distance` metres (default `100`) of the point, **ordered by
  spheroidal distance ascending**, as `{"count": n, "roads": [...]}` where each entry
  carries `id`, `meta`, `frc`, `fow`, `lengthMeters`, `startNodeId`, `endNodeId`.
- **FR-3.2** `GET /api/v1/roads/{id}` SHALL return the segment as a GeoJSON `Feature`
  with `LineString` geometry, or `404` if no such segment exists. The path parameter is
  the opaque internal ID and the body SHALL NOT repeat it (FR-1.11); callers normally
  identify a segment by `meta` via FR-3.3.
- **FR-3.3** `GET /api/v1/roads?meta=<value>` SHALL return a GeoJSON
  `FeatureCollection` of every segment whose `meta` equals the value exactly (no
  pattern matching), with `meta.count` giving the number matched.
- **FR-3.5** Neither endpoint SHALL serialise the JTS geometry object directly.
- **FR-3.6** The map size reported to the OpenLR library SHALL be a count of lines,
  not of rows: a two-way road is two lines (§7.5).
- **FR-3.4** `GET /api/v1/stats` SHALL return `{"roadCount": n, "intersectionCount": n}`.

### FR-4 Health

- **FR-4.1** `GET /api/v1/health` SHALL return `{"status": "UP", "roadCount": n,
  "intersectionCount": n}`. It touches the database, so it is a dependency check, not a
  liveness-only probe.
- **FR-4.3** A container health check SHALL NOT mutate server state. Probing with
  `POST /api/v1/cache/clear` emptied every cache on each interval (KG-15).
- **FR-4.2** `GET /health` on the front end SHALL return `{"status": "ok", "service":
  "openlr-webapp"}` without contacting the API.

### FR-5 Caches and configuration reload

- **FR-5.1** `POST /api/v1/cache/clear` SHALL empty the line, node, road, and
  intersection caches and return a confirmation message.
- **FR-5.2** `POST /api/v1/properties/reload` SHALL evict the cached decoder parameter
  sets so that each profile's `.properties` file is re-read on its next use. Editing a
  profile and calling this endpoint SHALL change decode behaviour **without a restart**.
- **FR-5.3** Both endpoints SHALL be idempotent and safe to call at any time.

### FR-6 Caching behaviour

- **FR-6.1** Segments, intersections, OpenLR lines, and OpenLR nodes SHALL be cached in
  memory by ID for the process lifetime, or until FR-5.1.
- **FR-6.2** Decoder parameter sets SHALL be cached by profile name, built lazily on
  first use, until FR-5.2.
- **FR-6.3** A profile whose `.properties` file is missing SHALL fall back to library
  defaults with a logged warning, rather than failing the request.
- **FR-6.4** Every cache SHALL be bounded, evicting least-recently-used entries at
  `cache_size` (the line cache at twice that, since a two-way road is two lines).
  Eviction is safe because each cache is a pure lookup on an immutable ID. Cache
  correctness assumes the network is **immutable while the app runs** — reloading map
  data requires FR-5.1 or a restart.
- **FR-6.5** `GET /api/v1/cache/stats` SHALL report size, bound, hits, misses and
  evictions per cache. Without it there was no way to observe that occupancy was
  growing without limit.

---

## 6. Functional requirements — Web front end

### FR-7 Decode and visualise

- **FR-7.1** The page SHALL accept an OpenLR code and a profile selection
  (`default` / `strict` / `relaxed`) and display the decoded path as a polyline on a
  Leaflet map.
- **FR-7.2** The code SHALL **also be parsed client-side** (`openlr-js`) so that the
  LRPs — coordinate, bearing, FRC, FOW, distance to next — can be listed and marked on
  the map.
- **FR-7.3** **When backend decoding fails, the client-side LRPs SHALL still be shown.**
  This is the tool's central diagnostic behaviour: it separates "the code is malformed"
  from "the code is fine but this map/profile cannot match it".
- **FR-7.4** A failed decode SHALL surface an expandable diagnostic panel reporting
  error type and category, HTTP status, backend `reason`, the request that was sent,
  whether client-side parsing succeeded, and a troubleshooting hint.
- **FR-7.5** The sidebar SHALL present offsets (absolute and relative), path metadata,
  the LRP table, and the segment table in collapsible sections.
- **FR-7.6** Selecting a segment or LRP row SHALL highlight and zoom to it on the map.
- **FR-7.7** The sidebar SHALL be resizable, collapsible, and its tables
  column-resizable.

### FR-8 Measurement tools

- **FR-8.1** A distance tool SHALL support multi-point measurement, reporting per-segment
  and cumulative distance in metres.
- **FR-8.2** A bearing tool SHALL report forward bearing, reverse bearing, and distance
  between two clicked points, with a direction arrow drawn on the map. Bearings are
  degrees clockwise from north.
- **FR-8.3** Re-activating either tool SHALL clear the previous measurement.

### FR-9 Proxying

- **FR-9.1** The front end SHALL proxy `/api/*` to the backend named by `API_URL`, so
  the browser makes same-origin requests and no CORS configuration is needed.
- **FR-9.2** A proxy failure SHALL return `500` with a JSON body naming the cause, not
  an HTML error page.
- **FR-9.3** Any unmatched path SHALL serve `index.html`.

---

## 7. Data model

The tool requires exactly two tables in schema `local` (names configurable via
`db_schema`, `roads_table`, `intersections_table`). Geometry is SRID 4326.

### 7.1 `local.roads`

| Column | Type | Required | Notes |
|--------|------|----------|-------|
| `id` | `bigint` PK | yes | Positive. Negation at the API expresses reverse traversal |
| `meta` | `text` | no | Free-form external identifier, returned in decode output |
| `frc` | `integer` | yes | `0`–`6` map to `FRC_0`–`FRC_6`; **anything else becomes `FRC_7`** |
| `fow` | `integer` | yes | `1`=motorway, `2`=multiple carriageway, `3`=single carriageway, `4`=roundabout, `5`=traffic square, `6`=sliproad; **anything else becomes `UNDEFINED`** |
| `flowdir` | `integer` `NOT NULL` | yes | Traversability, constrained to `1`/`2`/`3`: `1` = two-way, `2` = one-way **against** digitisation, `3` = one-way **with** digitisation. See §7.5 |
| `from_int` | `bigint` | yes | Start intersection; must exist for `roads/near` (inner join) |
| `to_int` | `bigint` | yes | End intersection; same |
| `len` | `double precision` | yes | Length in metres. Authoritative — not recomputed from geometry |
| `geom` | `geometry(LineString,4326)` | yes | Digitised from `from_int` to `to_int`. A `MultiLineString` is tolerated by taking its first part |

### 7.2 `local.intersections`

| Column | Type | Required | Notes |
|--------|------|----------|-------|
| `id` | `bigint` PK | yes | |
| `meta` | `text` | no | |
| `geom` | `geometry(Point,4326)` | yes | |

### 7.3 Required indexes

GIST on both `geom` columns; B-tree on `roads.from_int`, `roads.to_int`, and
`intersections.meta`. Decoding is dominated by radius search and node expansion, so the
GIST and `from_int`/`to_int` indexes are load-bearing, not optional.

### 7.4 Data invariants (operator's responsibility)

- **DI-1** Every `roads.from_int` / `to_int` references an existing intersection.
  (Foreign keys are commented out in `schema.sql`; violations surface as segments
  silently missing from `roads/near` results.)
- **DI-2** A segment's geometry starts at `from_int`'s point and ends at `to_int`'s.
  Reversed digitisation corrupts bearings and therefore matching.
- **DI-3** `len` agrees with `geom` to within loading tolerance.
- **DI-4** Segment IDs are positive, so that negation is unambiguous.
- **DI-5** `roads.meta` is unique and non-empty. `/encode` resolves `path` against it
  (FR-2.2), so a repeated value makes those segments unencodable. Enforced by a
  `UNIQUE` index in `database/schema.sql`; a database created before that index was
  added needs the migration in `docker/POSTGRES.md`.
- **DI-6** `roads.meta` contains no leading `-`, which would be read as a direction
  prefix (FR-2.2), and no whitespace that would complicate passing it as a query
  parameter.


### 7.5 Flow direction semantics

`flowdir` is a **traversability** flag, not a bearing. It states which of the two
possible traversals of a segment exist as OpenLR lines. Exactly three values are
defined:

| `flowdir` | Lines generated | Line IDs | Meaning |
|-----------|-----------------|----------|---------|
| `1` | two | `+id` and `-id` | Two-way |
| `2` | one | `-id` only, geometry reversed, `to_int`→`from_int` | One-way **against** the digitised direction |
| `3` | one | `+id` only, `from_int`→`to_int` | One-way **with** the digitised direction |

- **FD-1** `1` is the **only** encoding of two-way. Every other value is undefined and
  reserved, so that assigning one a meaning later cannot silently change how
  already-loaded data is read.
- **FD-2** The column SHALL be `NOT NULL CHECK (flowdir IN (1, 2, 3))`, so undefined
  values cannot be stored. `database/schema.sql` and `docker/init-db/01-init-schema.sql`
  carry the constraint; a database initialised before it was added needs the migration
  in `docker/POSTGRES.md` (§Flow Direction).
- **FD-3** `FlowDirection.fromDbValue` (`Road.kt`) is the single source of truth for
  the mapping and returns `null` for an undefined value. Nothing else may re-implement
  it.
- **FD-4** Reading an undefined value — possible only in a database predating FD-2 —
  SHALL log a warning naming the road ID and the value, and fall back to two-way, so
  that legacy data stays usable and the query does not fail. This is a tolerance, not a
  meaning: the affected segment may be traversed in both directions during decoding
  even if it is truly one-way, so warnings SHOULD be treated as data defects to fix.
- **FD-5** A one-way segment MUST be coded `2` or `3`. Because the FD-4 fallback and a
  mistaken `1` both yield two-way, mis-coding **over-permits** traversal rather than
  blocking it — the decoder may match a path travelling the wrong way up a one-way
  street, and no decode fails for a missing line.

The reading is fixed by three code sites, which agree with each other:

1. **`FlowDirection.fromDbValue`** (`Road.kt`) — `1`→`BOTH_WAYS`, `2`→`END_TO_START`,
   `3`→`START_TO_END`, anything else →`null`.
2. **`FlowDirection`** (`Road.kt`) defines those names as *direction of travel*:
   `START_TO_END` is "traffic flows from start to end node only", `END_TO_START`
   "from end to start node only". Start and end are `from_int` and `to_int`.
3. **`OpenLrMapDatabaseAdapter.convertRoadToLines`** (`OpenLrMapDatabaseAdapter.kt:104`)
   turns each state into lines: `START_TO_END` yields only `+id` with the stored
   geometry; `END_TO_START` yields only `-id` with `reverse()`d geometry and the node
   pair swapped; `BOTH_WAYS` yields both. `getNextLines`, `getPrevLines`,
   `getOutgoingLines`, and `getIncomingLines` branch identically, so graph expansion
   during decoding honours the same rule.

`MapDatabaseService.mapFlowDirection` is the only reader of the column and implements
FD-4 around `fromDbValue`. `README.md`, `docker/README.md`, `docker/POSTGRES.md` and
`docker/init-db/02-sample-data.sql.example` all state this convention, and
`FlowDirectionTest` pins it.

---

## 8. Configuration

### 8.1 Application properties

Read by the app (`config/application.properties`, mounted read-only at
`/app/config`):

| Key | Default | Purpose |
|-----|---------|---------|
| `server.port` | `8081` | HTTP port |
| `server.max-http-header-size` | `256KB` | Raised for long GET decode URLs |
| `spring.profiles.active` | `generic_pg_mapdb` | |
| `spring.datasource.{url,username,password}` | `postgres:5432/openlr_db`, `openlr`, `openlrpwd` | |
| `spring.datasource.hikari.maximum-pool-size` | `20` | |
| `decoder_properties_dir` | `/app/config/decoding_properties` | |
| `encoding_properties_dir` | `/app/config/encoding_properties` | |
| `db_schema`, `roads_table`, `intersections_table` | `local`, `roads`, `intersections` | |
| `cache_size` | `1000000` | Initial map capacity only |

### 8.2 Deployment environment variables

| Variable | Default | Applies to |
|----------|---------|-----------|
| `PORT` | `8081` | Host port for the API |
| `WEBAPP_PORT` | `3000` | Host port for the front end |
| `POSTGRES_PORT` | `5432` | Host port for the database |
| `JAVA_OPTS` | `-Xmx24g` | JVM options for the API |
| `API_URL` | `http://app:8081` | Backend the front end proxies to |
| `DATA_DIR` | `./data` | Directory mounted into the setup sidecar |

### 8.3 Decoder profiles

Profiles are `.properties` files in `decoder_properties_dir`; the file's base name is
the value passed in `props`. Three ship by default:

| Profile | Intent |
|---------|--------|
| `default` | Balanced |
| `relaxed` | Lower rating threshold, wider tolerances — matches more, less precisely |
| `strict` | Higher rating threshold — matches less, more confidently |

- **CFG-1** Adding a file to the directory SHALL make a new profile available; it
  becomes usable after FR-5.2 or a restart, with no code change.
- **CFG-2** Parameters governing acceptance include `MinimumAcceptedRating`,
  `FRC_Variance`, `MaxNodeDistance`, `BearingDistance`, `maxBearingDiff`,
  `DNPVariance`, and `MaxNumberRetries`. Some keys present in the shipped files
  (`Real_Junction_Factor`, `Scale_Bearing_Rating_Within_Category`, `GlobalROuting`,
  `Calc_Affected_Lines`, `Lines_Directly_Factor`, `CompTime4Cache`) are TomTom
  `openlr-tt` extensions and are **ignored by the open-source 1.4.3 decoder** in use
  here. They are retained for cross-comparison with `openlr-tt` and are commented as
  such.

---

## 9. Non-functional requirements

- **NFR-1 Statelessness.** The API keeps no per-client state. Any instance can serve
  any request; caches are a performance detail, not a session.
- **NFR-2 Resource envelope.** The documented production shape assumes ~40 GB RAM:
  4 GB `shared_buffers` / 12 GB `effective_cache_size` for PostgreSQL, and a JVM heap
  sized by `-XX:MaxRAMPercentage=75` so it adapts to whatever the container is given.
  A fixed `-Xmx` MUST NOT exceed the container limit: `-Xmx24g` in a 7.7 GiB container
  meant the JVM would be OOM-killed rather than collect as it approached the real
  ceiling. Pin a size with `JAVA_OPTS` only where the envelope is known.
- **NFR-3 Startup ordering.** `app` SHALL start only once `postgres` is healthy, and
  `webapp` only once `app` is healthy; each service SHALL define a health check
  (postgres 10 s/5 s/5, app 30 s/3 s/3, webapp 30 s/3 s/3).
- **NFR-4 Restartability.** All services SHALL use `restart: unless-stopped`, and
  database contents SHALL survive container replacement via the named volume
  `openlr_postgres_data`.
- **NFR-5 Security posture.** There is no authentication, authorisation, TLS, or rate
  limiting, and default credentials are committed. The tool is specified for a trusted
  network — a workstation or an internal host — and MUST NOT be exposed to the public
  internet without a fronting proxy that supplies those controls. The `props` parameter
  is used as a file base name inside a fixed directory, so profile names SHOULD be
  treated as untrusted input if the API is ever exposed.
- **NFR-6 Observability.** Behaviour is observable through application logs (per-request
  decode profile and outcome, per-profile fallback attempts at DEBUG) and through
  `decodingElapsedNanos` on every successful decode.
- **NFR-7 External runtime dependencies of the front end.** Map tiles, Leaflet,
  Leaflet TextPath, and `openlr-js` load from public CDNs. **The front end therefore
  requires outbound internet access**; the API does not. An air-gapped deployment would
  need these assets vendored.
- **NFR-9 Metric accuracy.** Distances and along-line measures exposed to the OpenLR
  library SHALL be in metres, computed in a local tangent plane rather than in degree
  space, and point-to-line distance SHALL be perpendicular distance rather than
  distance to the nearest vertex. `GeoMath` implements this and `GeoMathTest` checks it
  against reference values from `pyproj.Geod(ellps="WGS84")` — an independent
  implementation — with physically justified tolerances. Along-line measures SHALL be
  scaled by `roads.len`, which is the authoritative ellipsoidal length.
- **NFR-8 Offline data loading.** Loading map data SHALL be possible without the API
  running — the sidecar talks only to PostgreSQL.

---

## 10. Known gaps and drift

Discrepancies between code, docs, and front end. Each was a defect candidate, not a
design decision.

**All 26 gaps identified in this specification's first draft are now closed.** That
does not mean the code is defect-free — it means this list is exhausted and the next
one has to come from fresh review, from `tools/verify_api.py` failing, or from
production. Two areas are known to be thin rather than wrong:

- **No automated front-end tests.** FR-7 to FR-9 are verified by hand in a browser.
- **`hasTurnRestrictions()` returns false** because the schema cannot record them
  (ADR 0001), so a matched path may traverse a prohibited turn. A deliberate trade,
  but it bounds achievable accuracy.

Numbers below are retired, not reused, so earlier references stay valid.
Architecture decisions behind several are recorded in [docs/adr](docs/adr/README.md).

- **KG-15** — the Compose health check probed `POST /api/v1/cache/clear`, purging every
  cache on each 30 s interval (30 purges in 15 minutes of uptime, measured). Now
  `GET /api/v1/health`, which also verifies database connectivity for `depends_on`.
  **KG-5**, the image's own `HEALTHCHECK` pointing at the nonexistent `/purgeCache`,
  is fixed with it.
- **KG-17** — a database outage answered `400` with `"reason": "Decoding error: Failed
  to obtain JDBC Connection"`, blaming the caller's code. `DataAccessException` now
  propagates to `ApiExceptionHandler` and both `/decode` and `/encode` answer `503`.
- **KG-18** — an unknown profile decoded with the OpenLR library defaults and echoed
  the bogus name in `meta.propertySet`. Now `400`, naming what is available.
- **KG-19** — `props` was interpolated into a file path unvalidated (`../application`
  read a file outside the profile directory and returned `200`). Now restricted to
  `[A-Za-z0-9_-]{1,64}` and required to exist, which also bounds the parameter cache.
- **KG-2** — `/api/v1/metadata` returned `404` on every page load. The endpoint has no
  backing table and `index.html` has no elements for it to populate, so the dead
  client code was removed rather than an endpoint invented.
- **KG-21** — `getNumberOfLines()` returned the row count, under-reporting the
  network by up to half. New `getLineCount()` counts a two-way road twice.
- **KG-23** — `meta.wkt` repeated the endpoint shared by consecutive segments, and
  produced the invalid `LINESTRING()` when nothing resolved. Duplicates are dropped
  and the empty case is `LINESTRING EMPTY`.
- **KG-24** — the intersection cache stored `meta` as null from the `roads/near`
  join, and because the cache is consulted first, `getIntersection` then returned a
  null `meta` for the rest of the process lifetime. The join now selects it.
- **KG-25** / **KG-9** — `getRoadsConnectedToRoad` and five unused DTOs
  (`EncodeRequest`, `DecodeResponse`, `LocationData`, `Coordinate`, `RoadInfo`)
  deleted after confirming nothing referenced them.
- **KG-4** — `docs/api/openapi.yml` documented three endpoints that had never
  existed (`/purgeCache`, `/reloadProps`, `/getLine`) and omitted most of the real
  surface. Rewritten against the controller: 10 paths, 11 operations, an exact match.
  `tools/verify_api.py`'s `openapi` check now probes every documented path so it
  cannot drift again unnoticed.
- **KG-6** — three overlapping configuration sources. `application.yml` declared an
  `openlr.*` tree, a `localhost` datasource and port 8080, **none of which was read**
  — the code binds six flat keys — so it was deleted. The two remaining files now
  state their roles: the classpath copy is the local-development default, and
  `config/application.properties` is what a container reads.
- **KG-7** — the image built on JDK 17 and ran on 21. Both are 17 now.
- **KG-8** — `openlr-js` was loaded from the CDN at `@latest`, so the front end could
  change behaviour without a commit. Pinned to 3.0.1.
- **KG-12** — the front end needed `app.js?v=N` incremented by hand on every edit,
  and served stale JavaScript when it was forgotten. Removed in favour of ETag
  revalidation, which `express.static` already provides.
- **KG-11** — `docs/adr/` was empty. Five records now cover the decisions that are
  not reconstructable from the code: the minimal schema, the `flowdir` encoding,
  `meta` as the public identifier, DuckDB for conversion, and bounded caches.
- **KG-26** — closed as documented rather than fixed: `flowdir NOT NULL` is a
  deliberate breaking change for loaders that omitted the column, and the migration
  is in `docker/POSTGRES.md`.
- **KG-10** — the four map caches were unbounded `ConcurrentHashMap`s and
  `cache_size` was their initial capacity, not a limit. Measured: 2,173 references
  decoded against a 4.7M-road map retained 213 MiB, and re-decoding the same
  references added 1 MiB — growth tracked distinct geography and never came back.
  Made worse by the KG-15 fix, since the 30-second purge had been the only thing
  bounding memory. Now `LruCache`, bounded by `cache_size`. Demonstrated with a
  deliberately small bound: occupancy pinned at the limit, 740k evictions, memory
  flat across four full passes (385 → 398 MiB) and the decode rate unchanged at
  94.8%. `GET /api/v1/cache/stats` makes occupancy observable, which it never was.
  Fixed alongside it: `JAVA_OPTS=-Xmx24g` inside a 7.7 GiB container, which meant
  the JVM would be OOM-killed rather than collect; now `-XX:MaxRAMPercentage=75`.
- **KG-3** — `/encode` accepted `props` and ignored it: the encoder parameters were
  built once at startup with no properties file, so `config/encoding_properties/`
  had never been read. Now cached per profile and validated exactly as the decoder's
  are. The log line `Loaded encoder properties: …/default.properties` appears for the
  first time.
- **KG-16** — `/roads/{id}` and `/roads?meta=` serialised the JTS geometry by
  reflection, returning 48,154 bytes per segment as an `envelope` chain 3,424 levels
  deep with no coordinates. Both now return GeoJSON (`RoadFeatureProperties`), and the
  same request measures **446 bytes**.
- **KG-20** — metric geometry was computed in degree space: `distanceToPoint`
  returned the distance to the nearest *vertex* scaled by a flat `× 111000`, and the
  along-line measures were anisotropic by `cos(latitude)` (0.63 at Dutch latitudes).
  Replaced by `GeoMath`, which works in a local tangent plane; verified against
  `pyproj.Geod` (NFR-9). Measured effect end to end was modest — profile fallback
  recovered 11 of 44 failures where it had recovered proportionally fewer before —
  so this was a correctness fix rather than the main cause of decode failures.
- **KG-22** — `NodeAdapter.getConnectedLines()` threw `UnsupportedOperationException`.
  The OpenLR *encoder* calls it, so most encodes failed; it now returns outgoing plus
  incoming lines. Measured effect: encode success went from 1/8 to 15/15.

Also: KG-1, KG-13 and KG-14 —
the `flowdir` convention of §7.5. All four schema docs now state it correctly;
`1` is the sole two-way encoding with the rest of the range reserved and a CHECK
constraint enforcing it; `FlowDirection.fromDbValue` holds the one copy of the mapping;
`OpenLrDecodeIntegrationTest` calls that instead of its own divergent copy; and
`FlowDirectionTest` covers it.

---

## 11. Verification

| Requirement group | How verified |
|-------------------|--------------|
| FR-1.11 (decode/encode round trip) | `OpenLrControllerTest` |
| FR-1, FR-2, FR-3, FR-4, FR-5 | `OpenLrControllerTest` — all three decode forms, `props` defaulting, ordered fallback, decode failure → 400, encode success/invalid-path/offsets, `roads/{id}` 404, stats, cache clear, properties reload, health |
| FR-1 end-to-end | `OpenLrDecodeIntegrationTest` |
| FR-1.5, FR-1.6 | `GeoJsonModelsTest` |
| FR-3, FR-6, §7 mappings | `MapDatabaseServiceTest` |
| §7.5, FD-1, FD-3 | `FlowDirectionTest` |
| FD-2 | `CHECK` constraint in `database/schema.sql`; not covered by an automated test |
| FD-4 | Not covered by an automated test |
| FR-2, FR-6.2, FR-6.3 | `OpenLrServiceTest` |
| NFR-9, geometry maths | `GeoMathTest` — 15 cases against `pyproj.Geod` references |
| FR-6.4, FR-6.5 | `LruCacheTest` (9 cases, including eviction order and concurrency) and `MapDatabaseServiceTest`; end to end by `tools/verify_api.py`'s `cache` checks |
| FR-2.9 | `OpenLrServiceTest` — unknown and directory-escaping encoder profiles |
| FR-3.6 | `MapDatabaseServiceTest` — `getLineCount` counts two-way roads twice |
| §7.5 WKT shape, openapi accuracy | `tools/verify_api.py` `wkt` and `openapi` checks |
| FR-3.2, FR-3.3, FR-3.5 | `OpenLrControllerTest`, including a payload-size guard |
| End-to-end against a loaded map | `tools/verify_api.py` — 20 checks: health, the error contract, response shapes, a decode corpus, profile fallback, the decode/encode round trip, that the health probe mutates nothing, and (with `--disruptive`) the 503 contract under a paused database |
| FR-7, FR-8, FR-9 | Manual, in-browser. No automated front-end tests exist |
| NFR-3, NFR-4 | Compose health checks and `depends_on: service_healthy` |

Run unit tests with `./gradlew test` (or `./gradlew :main:test`). Run the live
harness against a deployment with `python3 tools/verify_api.py` — add `--disruptive`
to include the database-outage checks, which pause and unpause the container. It runs
25 checks.

Baseline on the Netherlands Orbis map (4,717,149 roads), 1,000 codes: **95.6%**
decode with `default`, **96.7%** with `strict,relaxed,default`; 25/25 decoded paths
re-encode and 84% reproduce identical geometry.

Acceptance for a deployment:

1. `./dc up` brings all three services to healthy.
2. `curl localhost:8081/api/v1/health` returns `UP` with non-zero counts after data load.
3. A known code decodes to the expected geometry under `default`.
4. The same code under `strict,relaxed,default` reports in `meta.propertySet` which
   profile won.
5. A deliberately unmatchable code returns `400` and the front end still plots its LRPs.

---

## 12. Open questions

1. Which already-loaded networks were built against the old README's convention?
   The docs, code and schema are now correct (§7.5), but existing databases predate
   the FD-2 constraint: segments coded `1` that were meant to be one-way forward, and
   any `NULL`/`0` rows, are still traversable both ways. `docker/POSTGRES.md` has the
   audit query and migration; recoding needs the source network, so it cannot be
   automated.
2. **KG-2** — should map metadata become a real endpoint (a third table populated at
   load time), or should the client code be removed?
3. **KG-3** — should encoding honour `props`, i.e. should encoder profiles be a
   supported feature at all?
4. Should the OpenLR location types listed in §1.4 be supported, or is line-location-only
   a permanent boundary?
5. Is `docs/api/openapi.yml` worth regenerating from the controller, or should it be
   deleted in favour of this document plus the README?
