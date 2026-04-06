# OpenLR Visualization Web Application

A single-page web application for visualizing OpenLR (Open Location Reference) encoding and decoding results on an interactive map.

## Features

### Core Functionality
- **OpenLR Decoding** - Enter Base64-encoded OpenLR codes and visualize the decoded location
- **Interactive Map** - Leaflet-based slippy map with OpenStreetMap tiles
- **Directional Visualization** - Black and yellow dashed path with arrow markers showing direction
- **Location Reference Points (LRPs)** - Color-coded markers:
  - 🟢 Green: Start point
  - 🔴 Red: End point
  - 🔵 Blue: Intermediate points
- **Segment Details** - Interactive table showing all road segments in the decoded path
- **Offset Display** - Shows positive and negative offsets from the location reference

### Interactive Features
- **Click LRP** - Popup with detailed LRP attributes (coordinates, FRC, FOW, bearing)
- **Click Segment** - Popup with road segment attributes (ID, FRC, FOW, length, nodes)
- **Click Table Row** - Zoom to segment extent and highlight it on the map
- **Distance/Angle Tool** - Leaflet measure plugin for spatial measurements
- **Clear/Reset** - Clear all visualizations and start over

### Map Metadata Integration
- If the PostgreSQL database has the optional `local.map_metadata` table populated:
  - Map auto-zooms to data extent on load
  - Header displays map name, description, and load date

## Technology Stack

- **Backend**: Node.js + Express (proxies API calls to Spring Boot)
- **Frontend**: Vanilla JavaScript (no framework dependencies)
- **Map Library**: Leaflet 1.9.4
- **Map Tiles**: OpenStreetMap
- **Plugins**:
  - leaflet-polylinedecorator - Directional arrow markers
  - leaflet-measure - Distance and angle measurements

## Architecture

```
┌──────────────────────────┐
│  Browser                 │
│  (index.html + app.js)   │
└────────┬─────────────────┘
         │ HTTP :3000
         ▼
┌──────────────────────────┐
│  Express Server          │
│  (server.js)             │
│  - Serves static files   │
│  - Proxies /api to :8081 │
└────────┬─────────────────┘
         │ HTTP :8081
         ▼
┌──────────────────────────┐
│  Spring Boot API         │
│  (OpenLrController)      │
│  - Decode OpenLR         │
│  - Encode OpenLR         │
│  - Query metadata        │
└────────┬─────────────────┘
         │ JDBC
         ▼
┌──────────────────────────┐
│  PostgreSQL + PostGIS    │
│  - Road network          │
│  - Map metadata          │
└──────────────────────────┘
```

## Development

### Local Development

```bash
# Install dependencies
npm install

# Start development server with auto-reload
npm run dev

# Or start normally
npm start
```

The app will be available at http://localhost:3000

**Prerequisites:**
- Node.js 18 or higher
- Running Spring Boot API on port 8081
- PostgreSQL database with road network data

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `PORT` | 3000 | HTTP port for the webapp |
| `API_URL` | http://app:8081 | Backend API URL |

### Project Structure

```
webapp/
├── Dockerfile              # Container image definition
├── package.json           # Node.js dependencies
├── server.js              # Express server (API proxy)
└── public/                # Static files served to browser
    ├── index.html         # Main HTML page
    ├── css/
    │   └── style.css      # Application styles
    └── js/
        └── app.js         # Frontend application logic
```

## Docker Deployment

### Build the Image

```bash
# From the webapp directory
docker build -t openlr-webapp:latest .

# Or using the dc script from project root
cd ../docker
./dc build webapp
```

### Run with Docker Compose

```bash
# Start all services (postgres, app, webapp)
./docker/dc up

# Or start only webapp (requires app and postgres running)
./docker/dc up webapp

# View logs
./docker/dc logs webapp
```

The webapp will be available at http://localhost:3000

### Configuration

Customize the webapp port using the `WEBAPP_PORT` environment variable:

```bash
WEBAPP_PORT=8080 ./docker/dc up
```

## Using the Application

### 1. Enter OpenLR Code

Enter a Base64-encoded OpenLR binary code in the input field, for example:
```
CwV/mSIeQA4kBgFxAJ8OEA==
```

### 2. Select Decoder Properties

Choose decoder properties from the dropdown or enter custom values:
- `default` - Balanced decoding parameters
- `strict` - Stricter matching requirements
- `relaxed` - More lenient matching
- `strict,relaxed,default` - Try profiles in sequence until one succeeds

### 3. Decode

Click the "Decode" button. The map will display:
- Decoded path with directional arrows
- LRP markers (green/red/blue)
- Offset information
- Segments table

### 4. Interact with Results

- **Click LRPs** to view detailed attributes
- **Click segments on map** to view road details
- **Click table rows** to zoom to specific segments
- **Use measure tool** (📏 icon) to measure distances and angles

### 5. Clear

Click "Clear" to reset the map and start over.

## API Endpoints Used

The webapp communicates with these Spring Boot API endpoints:

- `GET /api/v1/metadata` - Fetch map metadata (optional)
- `POST /api/v1/decode` - Decode OpenLR code to location
  - Request: `{"openLrCode": "...", "props": "default"}`
  - Response: GeoJSON FeatureCollection with segments and metadata

## Troubleshooting

### Webapp Won't Start

**Error:** Cannot connect to API backend

**Solution:**
```bash
# Verify API is running
curl http://localhost:8081/api/v1/health

# Check webapp logs
./docker/dc logs webapp
```

### Decode Fails

**Error:** "No features returned from decode operation"

**Possible causes:**
1. Invalid OpenLR code
2. No map data loaded in database
3. Decoder properties mismatch

**Solution:**
```bash
# Verify map data exists
./docker/dc exec postgres psql -U openlr -d openlr_db \
  -c "SELECT COUNT(*) FROM local.roads;"

# Try different decoder properties
# Use: default,relaxed (fallback chain)
```

### Map Doesn't Display

**Problem:** White screen or map tiles not loading

**Solution:**
1. Check browser console for JavaScript errors
2. Verify internet connection (OSM tiles load from external server)
3. Check browser compatibility (requires modern browser with ES6 support)

### No Metadata Displayed

**Problem:** Header doesn't show map information

**Explanation:** This is normal if the `local.map_metadata` table is empty or doesn't exist. The metadata feature is optional.

**To enable:**
```sql
-- Insert metadata into PostgreSQL
INSERT INTO local.map_metadata (name, description, bbox)
VALUES (
    'My Map Dataset',
    'Road network for Basel, Switzerland',
    ST_MakeEnvelope(7.5, 47.5, 7.7, 47.6, 4326)
);
```

## Browser Compatibility

- Chrome/Edge 90+
- Firefox 88+
- Safari 14+

Requires:
- ES6 JavaScript support
- Fetch API
- CSS Grid and Flexbox

## License

This project is licensed under the Apache License 2.0. See the repository `LICENSE` file for details.
