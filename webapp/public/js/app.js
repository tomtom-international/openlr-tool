// OpenLR Visualization Application with Client-Side Decoding
(function() {
    'use strict';

    // Application state
    const state = {
        map: null,
        pathLayerGroup: null,
        segmentsLayerGroup: null,
        lrpLayerGroup: null,
        highlightLayer: null,
        layerControl: null,
        segmentLayers: {},
        lrpMarkers: {},
        currentHighlightedSegment: undefined,
        decodedData: null,
        binaryData: null,
        lrpsVisible: true,
        // Distance measurement tool
        measureActive: false,
        measurePoints: [],
        measureLayer: null,
        measureMarkers: [],
        // Bearing measurement tool
        bearingActive: false,
        bearingPoints: [],
        bearingLayer: null
    };

    // Initialize application when DOM is loaded
    document.addEventListener('DOMContentLoaded', init);

    function init() {
        initMap();
        initEventListeners();
        initCollapsibleSections();
        initSidebarResize();
        loadMapMetadata();
    }

    /**
     * Initialize Leaflet map with OSM tiles
     */
    function initMap() {
        // Create map centered on world view
        state.map = L.map('map', {
            center: [20, 0],
            zoom: 2,
            zoomControl: true
        });

        // Add OpenStreetMap tiles
        L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
            attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors',
            maxZoom: 19
        }).addTo(state.map);

        // Add custom distance measurement tool
        addDistanceMeasureTool();

        // Add bearing measurement tool
        addBearingMeasureTool();

        // Create custom pane for LRP markers with high z-index
        state.map.createPane('lrpPane');
        state.map.getPane('lrpPane').style.zIndex = 650;

        // Initialize layer groups
        state.pathLayerGroup = L.featureGroup().addTo(state.map);
        state.segmentsLayerGroup = L.featureGroup().addTo(state.map);
        state.lrpLayerGroup = L.featureGroup().addTo(state.map);

        // Add layer control
        const overlays = {
            "Route Path": state.pathLayerGroup,
            "Segments": state.segmentsLayerGroup,
            "Location Points (LRPs)": state.lrpLayerGroup
        };
        state.layerControl = L.control.layers(null, overlays, { position: 'topright' }).addTo(state.map);
    }

    /**
     * Initialize event listeners for UI controls
     */
    function initEventListeners() {
        document.getElementById('decode-btn').addEventListener('click', handleDecode);
        document.getElementById('toggle-sidebar-btn').addEventListener('click', toggleSidebar);

        // Reset parameter dropdown when new OpenLR code is entered
        document.getElementById('openlr-code').addEventListener('input', () => {
            document.getElementById('decoder-props').value = 'default';
        });

        // Allow Enter key to trigger decode
        document.getElementById('openlr-code').addEventListener('keypress', (e) => {
            if (e.key === 'Enter') {
                handleDecode();
            }
        });
    }

    /**
     * Initialize collapsible sections in sidebar
     */
    function initCollapsibleSections() {
        const headers = document.querySelectorAll('.section-header');
        headers.forEach(header => {
            header.addEventListener('click', function() {
                this.classList.toggle('collapsed');
                const content = this.nextElementSibling;
                content.classList.toggle('collapsed');
            });
        });
    }

    /**
     * Initialize sidebar resizing
     */
    function initSidebarResize() {
        const sidebar = document.getElementById('sidebar');
        const resizeHandle = document.querySelector('.sidebar-resize-handle');

        if (!sidebar || !resizeHandle) {
            return;
        }

        let startX, startWidth;

        resizeHandle.addEventListener('mousedown', (e) => {
            startX = e.pageX;
            startWidth = sidebar.offsetWidth;

            document.addEventListener('mousemove', onMouseMove);
            document.addEventListener('mouseup', onMouseUp);

            e.preventDefault();
        });

        function onMouseMove(e) {
            const width = Math.max(400, Math.min(800, startWidth + (e.pageX - startX)));
            sidebar.style.width = width + 'px';
        }

        function onMouseUp() {
            document.removeEventListener('mousemove', onMouseMove);
            document.removeEventListener('mouseup', onMouseUp);

            // Resize map after sidebar resize completes
            if (state.map) {
                state.map.invalidateSize();
            }
        }
    }

    /**
     * Toggle sidebar visibility
     */
    function toggleSidebar() {
        const sidebar = document.getElementById('sidebar');
        const btn = document.getElementById('toggle-sidebar-btn');

        // Toggle visibility
        if (sidebar.classList.contains('visible')) {
            sidebar.classList.remove('visible');
            btn.classList.remove('with-sidebar');
            btn.textContent = '▶';
        } else {
            sidebar.classList.add('visible');
            btn.classList.add('with-sidebar');
            btn.textContent = '◀';
        }

        // Resize map after sidebar animation completes
        setTimeout(() => {
            state.map.invalidateSize();
        }, 300);
    }

    /**
     * Load map metadata from the database
     */
    async function loadMapMetadata() {
        try {
            const response = await fetch('/api/v1/metadata');

            if (response.ok) {
                const data = await response.json();

                if (data && data.features && data.features.length > 0) {
                    const metadata = data.features[0].properties;
                    displayMapMetadata(metadata);

                    // Zoom to data extent if bbox available
                    if (metadata.bbox) {
                        const coords = metadata.bbox.coordinates[0];
                        const bounds = coords.map(coord => [coord[1], coord[0]]);
                        state.map.fitBounds(bounds, { padding: [50, 50] });
                    }
                }
            }
        } catch (error) {
            // Map metadata not available - silently ignore
        }
    }

    /**
     * Display map metadata in the header
     */
    function displayMapMetadata(metadata) {
        document.getElementById('metadata-name').textContent = metadata.name || 'Unknown';
        document.getElementById('metadata-description').textContent = metadata.description || '';

        if (metadata.load_date) {
            const date = new Date(metadata.load_date);
            document.getElementById('metadata-date').textContent = date.toLocaleDateString();
        }

        document.getElementById('map-metadata').style.display = 'block';
    }

    /**
     * Decode OpenLR binary using client-side library
     */
    function decodeOpenLRBinary(openLrString) {
        try {
            // Check if OpenLR library is loaded
            if (typeof OpenLR === 'undefined') {
                return null;
            }

            // Access the default export (v3.x uses ES6 modules)
            const OpenLRLib = OpenLR.default || OpenLR;

            // Create BinaryDecoder instance
            const binaryDecoder = new OpenLRLib.BinaryDecoder();

            // Decode the OpenLR binary string
            const openLrBinary = OpenLRLib.Buffer.from(openLrString, 'base64');
            const locationReference = OpenLRLib.LocationReference.fromIdAndBuffer('binary', openLrBinary);
            const rawLocationReference = binaryDecoder.decodeData(locationReference);
            const jsonObject = OpenLRLib.Serializer.serialize(rawLocationReference);

            return jsonObject;
        } catch (err) {
            return null;
        }
    }

    /**
     * Display binary code information from client-side decoding
     */
    function displayBinaryInfo(binaryData) {
        const props = binaryData.properties;

        // Offset information - access properties correctly
        const offsets = props._offsets?.properties || props.offsets || {};

        document.getElementById('positive-offset-absolute').textContent =
            offsets._pOffset !== undefined ? `${offsets._pOffset} m` : (offsets.pOffset !== undefined ? `${offsets.pOffset} m` : 'N/A');
        document.getElementById('positive-offset-relative').textContent =
            offsets._pOffRelative !== undefined ? offsets._pOffRelative.toFixed(2) + '%' :
            (offsets.pOffRelative !== undefined ? offsets.pOffRelative.toFixed(2) + '%' : 'N/A');
        document.getElementById('negative-offset-absolute').textContent =
            offsets._nOffset !== undefined ? `${offsets._nOffset} m` : (offsets.nOffset !== undefined ? `${offsets.nOffset} m` : 'N/A');
        document.getElementById('negative-offset-relative').textContent =
            offsets._nOffRelative !== undefined ? offsets._nOffRelative.toFixed(2) + '%' :
            (offsets.nOffRelative !== undefined ? offsets.nOffRelative.toFixed(2) + '%' : 'N/A');

        // LRP information - access the points array correctly
        const lrps = props._points?.properties || props.points || [];
        displayLRPInfo(lrps);
    }

    /**
     * Display detailed LRP information in table format
     */
    function displayLRPInfo(lrps) {
        const tbody = document.getElementById('lrp-table-body');
        tbody.innerHTML = '';

        lrps.forEach((lrpWrapper, index) => {
            const lrp = lrpWrapper.properties;
            const isFirst = index === 0;
            const isLast = index === lrps.length - 1;
            const lrpType = isFirst ? 'Start' : (isLast ? 'End' : 'Inter');

            const row = document.createElement('tr');
            row.dataset.lrpIndex = index;
            row.style.cursor = 'pointer';
            row.innerHTML = `
                <td>${index + 1}</td>
                <td>${lrpType}</td>
                <td>${lrp._latitude?.toFixed(6) || '-'}</td>
                <td>${lrp._longitude?.toFixed(6) || '-'}</td>
                <td>${lrp._bearing !== undefined ? lrp._bearing + '°' : '-'}</td>
                <td>${lrp._distanceToNext !== undefined ? lrp._distanceToNext + 'm' : '-'}</td>
                <td>${lrp._frc !== undefined ? 'FRC_' + lrp._frc : '-'}</td>
                <td>${lrp._fow || '-'}</td>
            `;

            // Add click handler to zoom to LRP
            row.addEventListener('click', () => handleLRPRowClick(index));

            tbody.appendChild(row);
        });

        // Initialize column resizing for LRP table
        initLRPColumnResizing();
    }

    /**
     * Handle click on LRP table row - zoom to LRP marker and open popup
     */
    function handleLRPRowClick(index) {
        const marker = state.lrpMarkers[index];
        if (marker) {
            const latLng = marker.getLatLng();
            state.map.setView(latLng, 16);
            // Open popup after a short delay to ensure map has zoomed
            setTimeout(() => {
                marker.openPopup();
            }, 300);
        }
    }

    /**
     * Handle decode button click
     */
    async function handleDecode() {
        const openlrCode = document.getElementById('openlr-code').value.trim();
        const decoderProps = document.getElementById('decoder-props').value.trim() || 'default';

        if (!openlrCode) {
            showStatus('Please enter an OpenLR code', 'error');
            return;
        }

        // Clear previous results first
        clearMap();

        // Step 1: Client-side binary decoding to extract LRP info
        showStatus('Decoding OpenLR binary...', 'info');
        const binaryData = decodeOpenLRBinary(openlrCode);

        if (binaryData) {
            state.binaryData = binaryData;
            // Display binary information in sidebar
            displayBinaryInfo(binaryData);
        }

        // Step 2: Server-side decoding to get actual map path
        const decodeBtn = document.getElementById('decode-btn');
        decodeBtn.disabled = true;
        showStatus('Fetching map-matched path...', 'info');

        try {
            const response = await fetch('/api/v1/decode', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json'
                },
                body: JSON.stringify({
                    openLrCode: openlrCode,
                    props: decoderProps
                })
            });

            if (!response.ok) {
                // Try to parse error response
                let errorData;
                try {
                    errorData = await response.json();
                } catch (parseError) {
                    // Response wasn't JSON, create basic error
                    errorData = {
                        msg: `HTTP ${response.status}: ${response.statusText}`,
                        reason: 'Server returned non-JSON response'
                    };
                }

                // Create error object with additional details
                const error = new Error('Backend decoding failed');
                error.apiResponse = errorData;
                error.statusCode = response.status;
                error.statusText = response.statusText;
                throw error;
            }

            const data = await response.json();

            if (!data.features || data.features.length === 0) {
                throw new Error('No features returned from decode operation');
            }

            state.decodedData = data;
            displayDecodedLocation(data);

            // Now add LRP markers AFTER the path is drawn
            if (state.binaryData && state.binaryData.properties._points?.properties) {
                addLRPMarkers(state.binaryData.properties._points.properties);
            }

            showStatus('Decode successful', 'success');

            // Make toggle button available (sidebar can be shown manually)
            document.getElementById('toggle-sidebar-btn').style.display = 'flex';

        } catch (error) {
            // Even if backend decode failed, display LRPs if we have valid binary data
            if (state.binaryData && state.binaryData.properties._points?.properties) {
                const lrps = state.binaryData.properties._points.properties;
                addLRPMarkers(lrps);

                // Zoom map to fit all LRPs
                if (Object.keys(state.lrpMarkers).length > 0) {
                    const bounds = state.lrpLayerGroup.getBounds();
                    if (bounds.isValid()) {
                        state.map.fitBounds(bounds, { padding: [50, 50] });
                    }
                }

                // Make toggle button available so user can view LRP details
                document.getElementById('toggle-sidebar-btn').style.display = 'flex';
            }

            // Collect comprehensive diagnostic information
            let diagnosticInfo = '=== DECODE ERROR DETAILS ===\n\n';

            // Error basics
            diagnosticInfo += `Error Type: ${error.name}\n`;
            diagnosticInfo += `Error Message: ${error.message}\n\n`;

            // Determine error category
            if (error.name === 'TypeError' && error.message.includes('fetch')) {
                diagnosticInfo += `Category: Network/Connection Error\n`;
                diagnosticInfo += `Likely Cause: Backend service unavailable or network issue\n\n`;
            } else if (error.statusCode) {
                diagnosticInfo += `Category: HTTP Error\n`;
                diagnosticInfo += `HTTP Status: ${error.statusCode} ${error.statusText || ''}\n\n`;
            } else {
                diagnosticInfo += `Category: ${error.message}\n\n`;
            }

            // API response details (if backend was reached)
            if (error.apiResponse) {
                diagnosticInfo += `Backend Response:\n`;
                if (error.apiResponse.msg) {
                    diagnosticInfo += `  Message: ${error.apiResponse.msg}\n`;
                }
                if (error.apiResponse.reason) {
                    diagnosticInfo += `  Reason: ${error.apiResponse.reason}\n`;
                }
                diagnosticInfo += `\nFull Response:\n${JSON.stringify(error.apiResponse, null, 2)}\n\n`;
            } else if (error.statusCode) {
                diagnosticInfo += `Backend Response: No JSON response received\n\n`;
            } else {
                diagnosticInfo += `Backend Response: Unable to reach backend\n\n`;
            }

            // Request details
            diagnosticInfo += `Request Details:\n`;
            diagnosticInfo += `  OpenLR Code: ${openlrCode}\n`;
            diagnosticInfo += `  Decoder Properties: ${decoderProps}\n`;
            diagnosticInfo += `  API Endpoint: /api/v1/decode\n\n`;

            // Client-side parsing info
            if (state.binaryData) {
                diagnosticInfo += `Client-Side Parsing: SUCCESS\n`;
                const props = state.binaryData.properties;
                if (props._points?.properties) {
                    diagnosticInfo += `  LRP Count: ${props._points.properties.length}\n`;
                    diagnosticInfo += `  Note: LRP markers displayed on map\n`;
                }
            } else {
                diagnosticInfo += `Client-Side Parsing: FAILED\n`;
                diagnosticInfo += `  Note: OpenLR binary may be invalid\n`;
            }

            // Stack trace
            if (error.stack) {
                diagnosticInfo += `\n=== STACK TRACE ===\n${error.stack}\n`;
            }

            // Troubleshooting hints
            diagnosticInfo += `\n=== TROUBLESHOOTING ===\n`;
            if (error.name === 'TypeError' && error.message.includes('fetch')) {
                diagnosticInfo += `- Check if backend container is running: docker ps | grep openlr-tool\n`;
                diagnosticInfo += `- Check backend health: curl http://localhost:8081/api/v1/health\n`;
                diagnosticInfo += `- Check webapp can reach backend: docker logs openlr-webapp\n`;
            } else if (error.statusCode === 400) {
                diagnosticInfo += `- Backend rejected the request (invalid parameters or data)\n`;
                diagnosticInfo += `- Check OpenLR code is valid Base64-encoded binary\n`;
                diagnosticInfo += `- Verify decoder properties are valid\n`;
            } else if (error.statusCode === 500) {
                diagnosticInfo += `- Backend internal error\n`;
                diagnosticInfo += `- Check backend logs: docker logs openlr-tool\n`;
                diagnosticInfo += `- Verify map database is loaded and accessible\n`;
            }

            // Show simplified error message in status bar
            showStatus('Decoding error: click for details', 'error', diagnosticInfo);
        } finally {
            decodeBtn.disabled = false;
        }
    }

    /**
     * Calculate length of a LineString segment in meters
     */
    function calculateSegmentLength(coordinates) {
        let totalLength = 0;
        for (let i = 0; i < coordinates.length - 1; i++) {
            const point1 = L.latLng(coordinates[i][1], coordinates[i][0]);
            const point2 = L.latLng(coordinates[i + 1][1], coordinates[i + 1][0]);
            totalLength += point1.distanceTo(point2);
        }
        return totalLength;
    }

    /**
     * Display decoded location on map
     */
    function displayDecodedLocation(geojson) {
        const features = geojson.features;
        const meta = geojson.meta || {};

        // Extract segments from features
        const segments = features.filter(f => f.geometry.type === 'LineString');

        // Calculate segment lengths and assign IDs if missing
        let totalPathLength = 0;
        segments.forEach((segment, index) => {
            const length = calculateSegmentLength(segment.geometry.coordinates);
            segment.properties.calculatedLength = length;
            totalPathLength += length;

            // Use index + 1 as ID if not present
            if (segment.properties.id === undefined || segment.properties.id === null) {
                segment.properties.id = index + 1;
            }
        });

        // Update meta with calculated path length if not present
        if (!meta.pathLength) {
            meta.pathLength = totalPathLength;
        }

        // Display path metadata
        displayPathMetadata(meta);

        // Draw path with directional arrows using leaflet-textpath
        drawPath(segments);

        // Create segments table
        createSegmentsTable(segments);

        // Zoom to fit all LRPs and segments
        zoomToAllFeatures();
    }

    /**
     * Zoom map to fit all LRPs and segments
     */
    function zoomToAllFeatures() {
        const bounds = L.latLngBounds([]);
        let hasFeatures = false;

        // Add path layer bounds
        if (state.pathLayerGroup && state.pathLayerGroup.getLayers().length > 0) {
            bounds.extend(state.pathLayerGroup.getBounds());
            hasFeatures = true;
        }

        // Add LRP layer bounds
        if (state.lrpLayerGroup && state.lrpLayerGroup.getLayers().length > 0) {
            bounds.extend(state.lrpLayerGroup.getBounds());
            hasFeatures = true;
        }

        // Fit bounds if we have features
        if (hasFeatures) {
            state.map.fitBounds(bounds, { padding: [50, 50] });
        }
    }

    /**
     * Display path metadata
     */
    function displayPathMetadata(meta) {
        document.getElementById('path-length').textContent =
            meta.pathLength ? `${meta.pathLength.toFixed(2)} m` : 'N/A';
        document.getElementById('applied-positive-offset').textContent =
            meta.positiveOffset ? `${meta.positiveOffset.toFixed(2)} m` : '0 m';
        document.getElementById('applied-negative-offset').textContent =
            meta.negativeOffset ? `${meta.negativeOffset.toFixed(2)} m` : '0 m';
    }

    /**
     * Draw path with leaflet-textpath directional arrows
     */
    function drawPath(segments) {
        // Collect all coordinates for the path
        const allCoords = [];

        segments.forEach((segment, index) => {
            const coords = segment.geometry.coordinates.map(c => [c[1], c[0]]);
            allCoords.push(...coords);

            // Create individual segment layer for click handling (invisible for clicks only)
            const segmentLayer = L.polyline(coords, {
                color: 'blue',
                weight: 2,
                opacity: 0
            });

            segmentLayer.on('click', () => handleSegmentClick(segment, index));
            segmentLayer.addTo(state.segmentsLayerGroup);

            // Use index as key since IDs might be undefined
            state.segmentLayers[index] = segmentLayer;
        });

        // Create main path blue line matching original
        const pathLayer = L.polyline(allCoords, {
            color: 'blue',
            weight: 2,
            opacity: 0.3,
            fillOpacity: 0
        }).addTo(state.pathLayerGroup);

        // Add directional arrows using leaflet-textpath
        pathLayer.setText('➤', {
            repeat: true,
            below: true,
            center: false,
            offset: 0,
            attributes: {
                fill: 'blue'
            }
        });
    }

    /**
     * Add LRP markers to the map
     */
    function addLRPMarkers(lrps) {
        state.lrpLayerGroup.clearLayers();
        state.lrpMarkers = {};

        if (!lrps || lrps.length === 0) {
            return;
        }

        lrps.forEach((lrpWrapper, index) => {
            const lrp = lrpWrapper.properties;
            const latLng = [lrp._latitude, lrp._longitude];

            // Determine marker color matching original
            let color;
            if (index === 0) {
                color = 'green'; // Start
            } else if (index === lrps.length - 1) {
                color = 'red'; // End
            } else {
                color = 'blue'; // Intermediate
            }

            const marker = L.circleMarker(latLng, {
                radius: 5,
                fill: true,
                color: color,
                opacity: 0.9,
                fillOpacity: 0.9,
                pane: 'lrpPane'
            });

            // Add popup with LRP attributes
            const popup = createLRPPopup(lrp, index, lrps.length);
            marker.bindPopup(popup);

            marker.addTo(state.lrpLayerGroup);

            // Store marker reference for table row clicks
            state.lrpMarkers[index] = marker;
        });

        // Bring LRP markers to front so they're above the path
        state.lrpLayerGroup.bringToFront();
    }

    /**
     * Create popup content for LRP
     */
    function createLRPPopup(lrp, index, total) {
        let type = 'Intermediate';
        if (index === 0) type = 'Start';
        else if (index === total - 1) type = 'End';

        return `
            <div class="popup-title">LRP ${index + 1} (${type})</div>
            <div class="popup-content">
                <table>
                    <tr><td>Latitude:</td><td>${lrp._latitude?.toFixed(6)}</td></tr>
                    <tr><td>Longitude:</td><td>${lrp._longitude?.toFixed(6)}</td></tr>
                    <tr><td>Bearing:</td><td>${lrp._bearing}°</td></tr>
                    <tr><td>FRC:</td><td>${lrp._frc || 'N/A'}</td></tr>
                    <tr><td>FOW:</td><td>${lrp._fow || 'N/A'}</td></tr>
                    <tr><td>Distance to Next:</td><td>${lrp._distanceToNext ? lrp._distanceToNext + ' m' : 'N/A'}</td></tr>
                </table>
            </div>
        `;
    }

    /**
     * Handle click on segment in map
     */
    function handleSegmentClick(segment, index) {
        const props = segment.properties;

        // Use calculated length, fallback to backend length
        const length = props.calculatedLength || props.len || props.length || 0;

        const popup = `
            <div class="popup-title">Segment ${index + 1}</div>
            <div class="popup-content">
                <table>
                    <tr><td>Meta:</td><td>${props.meta || 'N/A'}</td></tr>
                    <tr><td>FRC:</td><td>${props.frc !== undefined ? 'FRC_' + props.frc : 'N/A'}</td></tr>
                    <tr><td>FOW:</td><td>${props.fow || 'N/A'}</td></tr>
                    <tr><td>Length:</td><td>${length > 0 ? length.toFixed(2) + ' m' : 'N/A'}</td></tr>
                    <tr><td>From Node:</td><td>${props.from_int || 'N/A'}</td></tr>
                    <tr><td>To Node:</td><td>${props.to_int || 'N/A'}</td></tr>
                </table>
            </div>
        `;

        L.popup()
            .setLatLng(segment.geometry.coordinates.map(c => [c[1], c[0]])[0])
            .setContent(popup)
            .openOn(state.map);
    }

    /**
     * Create segments table
     */
    function createSegmentsTable(segments) {
        const tbody = document.getElementById('segments-table-body');
        tbody.innerHTML = '';

        segments.forEach((segment, index) => {
            const props = segment.properties;

            const row = document.createElement('tr');
            row.dataset.segmentIndex = index;

            // Use calculated length, fallback to backend length if available
            const length = props.calculatedLength || props.len || props.length || 0;

            row.innerHTML = `
                <td>${index + 1}</td>
                <td>${props.meta || '-'}</td>
                <td>${props.frc !== undefined ? 'FRC_' + props.frc : '-'}</td>
                <td>${props.fow !== undefined ? props.fow : '-'}</td>
                <td>${length > 0 ? length.toFixed(1) + 'm' : '-'}</td>
            `;

            row.addEventListener('click', () => handleTableRowClick(segment, index));
            tbody.appendChild(row);
        });

        // Initialize column resizing
        initColumnResizing();
    }

    /**
     * Initialize draggable column resizing for the segments table
     */
    function initColumnResizing() {
        const table = document.getElementById('segments-table');
        const thead = table.querySelector('thead');
        const ths = thead.querySelectorAll('th');

        // Set initial widths: #, Meta, FRC, FOW, Length
        const initialWidths = ['10%', '40%', '20%', '15%', '15%'];

        ths.forEach((th, index) => {
            // Set initial width
            if (index < initialWidths.length) {
                th.style.width = initialWidths[index];
            }

            // Remove any existing resizer
            const existingResizer = th.querySelector('.resizer');
            if (existingResizer) {
                existingResizer.remove();
            }

            // Create resizer handle
            const resizer = document.createElement('div');
            resizer.className = 'resizer';
            th.appendChild(resizer);

            let startX, startWidth;

            resizer.addEventListener('mousedown', (e) => {
                startX = e.pageX;
                startWidth = th.offsetWidth;

                document.addEventListener('mousemove', onMouseMove);
                document.addEventListener('mouseup', onMouseUp);

                e.preventDefault();
            });

            function onMouseMove(e) {
                const width = Math.max(30, startWidth + (e.pageX - startX));
                th.style.width = width + 'px';
            }

            function onMouseUp() {
                document.removeEventListener('mousemove', onMouseMove);
                document.removeEventListener('mouseup', onMouseUp);
            }
        });
    }

    /**
     * Initialize draggable column resizing for the LRP table
     */
    function initLRPColumnResizing() {
        const table = document.getElementById('lrp-table');
        if (!table) return;

        const thead = table.querySelector('thead');
        const ths = thead.querySelectorAll('th');

        // Set initial widths: #, Type, Lat, Lon, Bearing, Dist, FRC, FOW
        const initialWidths = ['8%', '12%', '20%', '20%', '12%', '12%', '8%', '8%'];

        ths.forEach((th, index) => {
            // Set initial width
            if (index < initialWidths.length) {
                th.style.width = initialWidths[index];
            }

            // Remove any existing resizer
            const existingResizer = th.querySelector('.resizer');
            if (existingResizer) {
                existingResizer.remove();
            }

            // Create resizer handle
            const resizer = document.createElement('div');
            resizer.className = 'resizer';
            th.appendChild(resizer);

            let startX, startWidth;

            resizer.addEventListener('mousedown', (e) => {
                startX = e.pageX;
                startWidth = th.offsetWidth;

                document.addEventListener('mousemove', onMouseMove);
                document.addEventListener('mouseup', onMouseUp);

                e.preventDefault();
            });

            function onMouseMove(e) {
                const width = Math.max(30, startWidth + (e.pageX - startX));
                th.style.width = width + 'px';
            }

            function onMouseUp() {
                document.removeEventListener('mousemove', onMouseMove);
                document.removeEventListener('mouseup', onMouseUp);
            }
        });
    }

    /**
     * Handle click on table row
     */
    function handleTableRowClick(segment, index) {
        // Remove previous highlight
        if (state.currentHighlightedSegment !== undefined) {
            const prevRow = document.querySelector(`tr[data-segment-index="${state.currentHighlightedSegment}"]`);
            if (prevRow) prevRow.classList.remove('highlighted');
        }

        // Remove previous highlight layer
        if (state.highlightLayer) {
            state.segmentsLayerGroup.removeLayer(state.highlightLayer);
            state.highlightLayer = null;
        }

        // Highlight new row
        const row = document.querySelector(`tr[data-segment-index="${index}"]`);
        if (row) row.classList.add('highlighted');

        state.currentHighlightedSegment = index;

        // Zoom to segment using index
        const layer = state.segmentLayers[index];

        if (layer) {
            state.map.fitBounds(layer.getBounds(), {
                padding: [50, 50],
                maxZoom: 18
            });

            // Create persistent highlight overlay matching original
            const coords = segment.geometry.coordinates.map(coord => [coord[1], coord[0]]);
            state.highlightLayer = L.polyline(coords, {
                color: 'red',
                weight: 10,
                opacity: 0.6
            }).addTo(state.segmentsLayerGroup);

            // Add popup to the highlight layer
            const props = segment.properties;
            const length = props.calculatedLength || props.len || props.length || 0;
            const popupContent = `
                <div class="popup-title">Segment ${index + 1}</div>
                <div class="popup-content">
                    <table>
                        <tr><td>Meta:</td><td>${props.meta || 'N/A'}</td></tr>
                        <tr><td>FRC:</td><td>${props.frc !== undefined ? 'FRC_' + props.frc : 'N/A'}</td></tr>
                        <tr><td>FOW:</td><td>${props.fow || 'N/A'}</td></tr>
                        <tr><td>Length:</td><td>${length > 0 ? length.toFixed(2) + ' m' : 'N/A'}</td></tr>
                        <tr><td>From Node:</td><td>${props.from_int || 'N/A'}</td></tr>
                        <tr><td>To Node:</td><td>${props.to_int || 'N/A'}</td></tr>
                    </table>
                </div>
            `;
            state.highlightLayer.bindPopup(popupContent);

            // Automatically open the popup after a short delay
            setTimeout(() => {
                state.highlightLayer.openPopup();
            }, 300);
        } else {
            // No layer found for segment index
        }
    }

    /**
     * Clear map and UI
     */
    function handleClear() {
        clearMap();
        document.getElementById('openlr-code').value = '';
        document.getElementById('status-message').textContent = '';
        document.getElementById('status-message').className = 'status-message';

        // Hide sidebar
        const sidebar = document.getElementById('sidebar');
        sidebar.classList.remove('visible');
        sidebar.classList.remove('collapsed');
        document.getElementById('toggle-sidebar-btn').style.display = 'none';
    }

    /**
     * Clear all layers and UI elements
     */
    function clearMap() {
        // Clear all layer groups
        if (state.pathLayerGroup) {
            state.pathLayerGroup.clearLayers();
        }

        if (state.segmentsLayerGroup) {
            state.segmentsLayerGroup.clearLayers();
        }

        if (state.lrpLayerGroup) {
            state.lrpLayerGroup.clearLayers();
        }

        // Clear highlight layer reference (already removed by segmentsLayerGroup.clearLayers())
        state.highlightLayer = null;

        // Clear segment layer references
        state.segmentLayers = {};

        // Clear UI
        document.getElementById('segments-table-body').innerHTML = '';
        document.getElementById('lrp-table-body').innerHTML = '';

        state.currentHighlightedSegment = undefined;
        state.decodedData = null;
        state.binaryData = null;
    }

    /**
     * Add custom distance measurement tool
     */
    function addDistanceMeasureTool() {
        // Create custom control button
        const MeasureControl = L.Control.extend({
            options: {
                position: 'topleft'
            },
            onAdd: function(map) {
                const container = L.DomUtil.create('div', 'leaflet-bar leaflet-control');
                const button = L.DomUtil.create('a', 'leaflet-control-measure-button', container);
                button.href = '#';
                button.title = 'Measure distance (click points, double-click to finish)';
                button.innerHTML = '📏';
                button.style.fontSize = '20px';
                button.style.lineHeight = '30px';
                button.style.width = '30px';
                button.style.height = '30px';
                button.style.textAlign = 'center';

                L.DomEvent.on(button, 'click', function(e) {
                    L.DomEvent.stopPropagation(e);
                    L.DomEvent.preventDefault(e);
                    toggleMeasureTool();
                });

                return container;
            }
        });

        state.map.addControl(new MeasureControl());

        // Initialize measure layer
        state.measureLayer = L.featureGroup().addTo(state.map);
    }

    /**
     * Toggle distance measurement tool
     */
    function toggleMeasureTool() {
        if (state.measureActive) {
            // Deactivate
            state.measureActive = false;
            state.map.getContainer().style.cursor = '';
            clearMeasurements();
            showStatus('Distance tool deactivated', 'info');
        } else {
            // Activate
            state.measureActive = true;
            state.map.getContainer().style.cursor = 'crosshair';
            state.measurePoints = [];
            clearMeasurements();
            showStatus('Click on map to measure distances. Double-click to finish.', 'info');

            // Add click handler
            state.map.on('click', handleMeasureClick);
            state.map.on('dblclick', finishMeasurement);
        }
    }

    /**
     * Handle click when measuring distance
     */
    function handleMeasureClick(e) {
        if (!state.measureActive) return;

        const point = e.latlng;
        state.measurePoints.push(point);

        // Add marker for this point
        const marker = L.circleMarker(point, {
            radius: 5,
            fillColor: '#ff0000',
            color: '#ffffff',
            weight: 2,
            opacity: 1,
            fillOpacity: 0.8
        }).addTo(state.measureLayer);

        state.measureMarkers.push(marker);

        // If we have at least 2 points, draw line and show distance
        if (state.measurePoints.length >= 2) {
            const prevPoint = state.measurePoints[state.measurePoints.length - 2];
            const line = L.polyline([prevPoint, point], {
                color: '#ff0000',
                weight: 2,
                dashArray: '5, 5'
            }).addTo(state.measureLayer);

            // Calculate segment distance
            const segmentDist = prevPoint.distanceTo(point);

            // Calculate total distance
            let totalDist = 0;
            for (let i = 1; i < state.measurePoints.length; i++) {
                totalDist += state.measurePoints[i - 1].distanceTo(state.measurePoints[i]);
            }

            // Add tooltip to the line with distances
            const midpoint = L.latLng(
                (prevPoint.lat + point.lat) / 2,
                (prevPoint.lng + point.lng) / 2
            );

            let distText = formatDistance(segmentDist);
            if (state.measurePoints.length > 2) {
                distText += `\nTotal: ${formatDistance(totalDist)}`;
            }

            const tooltip = L.tooltip({
                permanent: true,
                direction: 'center',
                className: 'measure-tooltip'
            })
            .setContent(distText)
            .setLatLng(midpoint);

            line.bindTooltip(tooltip).openTooltip();
        }
    }

    /**
     * Finish measurement (double-click)
     */
    function finishMeasurement(e) {
        if (!state.measureActive) return;

        L.DomEvent.stopPropagation(e);
        L.DomEvent.preventDefault(e);

        // Remove event listeners
        state.map.off('click', handleMeasureClick);
        state.map.off('dblclick', finishMeasurement);

        state.measureActive = false;
        state.map.getContainer().style.cursor = '';

        if (state.measurePoints.length >= 2) {
            // Calculate total distance
            let totalDist = 0;
            for (let i = 1; i < state.measurePoints.length; i++) {
                totalDist += state.measurePoints[i - 1].distanceTo(state.measurePoints[i]);
            }
            showStatus(`Measurement complete: ${formatDistance(totalDist)}. Click 📏 to measure again.`, 'success');
        } else {
            clearMeasurements();
            showStatus('Measurement cancelled', 'info');
        }
    }

    /**
     * Clear all measurements
     */
    function clearMeasurements() {
        if (state.measureLayer) {
            state.measureLayer.clearLayers();
        }
        state.measurePoints = [];
        state.measureMarkers = [];
    }

    /**
     * Format distance for display
     */
    function formatDistance(meters) {
        if (meters < 1000) {
            return `${meters.toFixed(1)} m`;
        } else {
            return `${(meters / 1000).toFixed(2)} km`;
        }
    }

    /**
     * Add bearing measurement tool
     */
    function addBearingMeasureTool() {
        // Create custom control button
        const BearingControl = L.Control.extend({
            options: {
                position: 'topleft'
            },
            onAdd: function(map) {
                const container = L.DomUtil.create('div', 'leaflet-bar leaflet-control');
                const button = L.DomUtil.create('a', 'leaflet-control-bearing-button', container);
                button.href = '#';
                button.title = 'Measure bearing (click 2 points)';
                button.innerHTML = '🧭';
                button.style.fontSize = '20px';
                button.style.lineHeight = '30px';
                button.style.width = '30px';
                button.style.height = '30px';
                button.style.textAlign = 'center';

                L.DomEvent.on(button, 'click', function(e) {
                    L.DomEvent.stopPropagation(e);
                    L.DomEvent.preventDefault(e);
                    toggleBearingTool();
                });

                return container;
            }
        });

        state.map.addControl(new BearingControl());

        // Initialize bearing layer
        state.bearingLayer = L.featureGroup().addTo(state.map);
    }

    /**
     * Toggle bearing measurement tool
     */
    function toggleBearingTool() {
        if (state.bearingActive) {
            // Deactivate
            state.bearingActive = false;
            state.map.getContainer().style.cursor = '';
            clearBearingMeasurement();
            showStatus('Bearing tool deactivated', 'info');
        } else {
            // Activate
            state.bearingActive = true;
            state.map.getContainer().style.cursor = 'crosshair';
            state.bearingPoints = [];
            clearBearingMeasurement();
            showStatus('Click first point, then second point to measure bearing', 'info');

            // Add click handler
            state.map.on('click', handleBearingClick);
        }
    }

    /**
     * Handle click when measuring bearing
     */
    function handleBearingClick(e) {
        if (!state.bearingActive) return;

        const point = e.latlng;
        state.bearingPoints.push(point);

        // Add marker for this point
        const markerColor = state.bearingPoints.length === 1 ? '#00aa00' : '#0000ff';
        const marker = L.circleMarker(point, {
            radius: 6,
            fillColor: markerColor,
            color: '#ffffff',
            weight: 2,
            opacity: 1,
            fillOpacity: 0.8
        }).addTo(state.bearingLayer);

        // Add label
        const label = state.bearingPoints.length === 1 ? 'A (Start)' : 'B (End)';
        marker.bindTooltip(label, {
            permanent: true,
            direction: 'top',
            offset: [0, -10],
            className: 'bearing-label-tooltip'
        }).openTooltip();

        // If we have 2 points, calculate and display bearing
        if (state.bearingPoints.length === 2) {
            const pointA = state.bearingPoints[0];
            const pointB = state.bearingPoints[1];

            // Calculate bearing (A to B)
            const bearing = calculateBearing(pointA, pointB);
            // Calculate reverse bearing (B to A)
            const reverseBearing = (bearing + 180) % 360;
            // Calculate distance
            const distance = pointA.distanceTo(pointB);

            // Draw line with arrow
            const line = L.polyline([pointA, pointB], {
                color: '#0000ff',
                weight: 3,
                opacity: 0.7
            }).addTo(state.bearingLayer);

            // Add arrow decorator using textPath
            const arrowText = '    ►    ►    ►';
            line.setText(arrowText, {
                repeat: true,
                offset: 6,
                attributes: {
                    fill: '#0000ff',
                    'font-size': '16',
                    'font-weight': 'bold'
                }
            });

            // Add tooltip with bearing information
            const midpoint = L.latLng(
                (pointA.lat + pointB.lat) / 2,
                (pointA.lng + pointB.lng) / 2
            );

            const infoText = `Bearing (A→B): ${bearing.toFixed(1)}°\nReverse (B→A): ${reverseBearing.toFixed(1)}°\nDistance: ${formatDistance(distance)}`;

            L.tooltip({
                permanent: true,
                direction: 'center',
                className: 'bearing-tooltip'
            })
            .setContent(infoText)
            .setLatLng(midpoint)
            .addTo(state.bearingLayer);

            // Deactivate tool
            state.map.off('click', handleBearingClick);
            state.bearingActive = false;
            state.map.getContainer().style.cursor = '';

            showStatus(`Bearing: ${bearing.toFixed(1)}°, Distance: ${formatDistance(distance)}. Click 🧭 to measure again.`, 'success');
        }
    }

    /**
     * Calculate bearing from point A to point B
     * Returns bearing in degrees (0-360, where 0 is North)
     */
    function calculateBearing(pointA, pointB) {
        const lat1 = pointA.lat * Math.PI / 180;
        const lat2 = pointB.lat * Math.PI / 180;
        const dLon = (pointB.lng - pointA.lng) * Math.PI / 180;

        const y = Math.sin(dLon) * Math.cos(lat2);
        const x = Math.cos(lat1) * Math.sin(lat2) -
                  Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon);

        let bearing = Math.atan2(y, x) * 180 / Math.PI;
        bearing = (bearing + 360) % 360; // Normalize to 0-360

        return bearing;
    }

    /**
     * Clear bearing measurement
     */
    function clearBearingMeasurement() {
        if (state.bearingLayer) {
            state.bearingLayer.clearLayers();
        }
        state.bearingPoints = [];
    }

    /**
     * Show status message
     */
    function showStatus(message, type, diagnosticInfo = null) {
        const statusEl = document.getElementById('status-message');
        statusEl.textContent = message;
        statusEl.className = `status-message ${type}`;

        // Remove any existing click handlers
        statusEl.onclick = null;

        // If success, fade out after 3 seconds
        if (type === 'success') {
            setTimeout(() => {
                statusEl.classList.add('fade-out');
                setTimeout(() => {
                    statusEl.textContent = '';
                    statusEl.className = 'status-message';
                }, 1000); // Wait for fade animation
            }, 3000);
        }

        // If error, make clickable to show diagnostics
        if (type === 'error' && diagnosticInfo) {
            statusEl.onclick = () => {
                alert(`Error Details:\n\n${diagnosticInfo}`);
            };
            statusEl.title = 'Click for details';
        }
    }

    // Make main functions available globally for debugging
    window.main = {
        handleDecode,
        handleClear,
        toggleSidebar
    };

})();
