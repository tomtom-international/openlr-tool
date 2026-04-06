package com.tomtom.openlr.tool.controller

import com.tomtom.openlr.tool.model.*
import com.tomtom.openlr.tool.service.MapDatabaseService
import com.tomtom.openlr.tool.service.OpenLrService
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/**
 * REST API controller for OpenLR encoding and decoding operations.
 *
 * Provides endpoints to:
 * - Decode OpenLR location references to map locations
 * - Encode map paths as OpenLR location references
 * - Query road network data
 * - Manage caches
 */
@RestController
@RequestMapping("/api/v1")
class OpenLrController(
    private val openLrService: OpenLrService,
    private val mapDatabaseService: MapDatabaseService
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Decode an OpenLR location reference (form data or query parameters).
     *
     * POST /api/v1/decode (accepts form-urlencoded)
     * GET /api/v1/decode?openLrCode=...&props=default
     * Returns GeoJSON FeatureCollection
     */
    @RequestMapping(
        value = ["/decode"],
        method = [RequestMethod.POST, RequestMethod.GET],
        consumes = ["application/x-www-form-urlencoded", "*/*"]
    )
    fun decode(
        @RequestParam openLrCode: String,
        @RequestParam(defaultValue = "default") props: String
    ): ResponseEntity<DecodingResponse> {
        logger.info("Decoding OpenLR code with properties: $props (form/query)")
        val response = openLrService.decode(openLrCode, props)
        val status = if (response is FeatureCollection) HttpStatus.OK else HttpStatus.BAD_REQUEST
        return ResponseEntity(response, status)
    }

    /**
     * Decode an OpenLR location reference (JSON request body).
     *
     * POST /api/v1/decode
     * Content-Type: application/json
     * Body: {"openLrCode": "...", "props": "default"}
     * Returns GeoJSON FeatureCollection
     */
    @PostMapping("/decode", consumes = ["application/json"])
    fun decodeJson(@RequestBody request: DecodeRequest): ResponseEntity<DecodingResponse> {
        logger.info("Decoding OpenLR code with properties: ${request.props} (JSON)")
        val response = openLrService.decode(request.openLrCode, request.props)
        val status = if (response is FeatureCollection) HttpStatus.OK else HttpStatus.BAD_REQUEST
        return ResponseEntity(response, status)
    }

    /**
     * Encode a path as an OpenLR location reference.
     *
     * POST /api/v1/encode
     * Params: path=123&path=456&path=789&positiveOffset=0&negativeOffset=0&props=default
     */
    @PostMapping("/encode")
    fun encode(
        @RequestParam path: List<String>,
        @RequestParam(defaultValue = "0") positiveOffset: Int,
        @RequestParam(defaultValue = "0") negativeOffset: Int,
        @RequestParam(defaultValue = "default") props: String
    ): ResponseEntity<EncodeResponse> {
        logger.info("Encoding path with {} segments", path.size)

        // Parse path - each element can be a line ID (positive or negative)
        val lineIds = try {
            path.map { it.toLong() }
        } catch (e: NumberFormatException) {
            return ResponseEntity(
                EncodeResponse(
                    success = false,
                    openLrCode = null,
                    error = "Invalid path: all elements must be numeric line IDs"
                ),
                HttpStatus.BAD_REQUEST
            )
        }

        val response = openLrService.encode(lineIds, positiveOffset, negativeOffset, props)

        val status = if (response.success) HttpStatus.OK else HttpStatus.BAD_REQUEST
        return ResponseEntity(response, status)
    }

    /**
     * Find roads near a coordinate.
     *
     * GET /api/v1/roads/near?lon=-0.1276&lat=51.5074&distance=100
     */
    @GetMapping("/roads/near")
    fun findRoadsNear(
        @RequestParam lon: Double,
        @RequestParam lat: Double,
        @RequestParam(defaultValue = "100") distance: Int
    ): ResponseEntity<RoadsNearResponse> {
        logger.debug("Finding roads within {}m of ({}, {})", distance, lon, lat)
        val roads = mapDatabaseService.findRoadsNear(lon, lat, distance)

        return ResponseEntity.ok(RoadsNearResponse(
            count = roads.size,
            roads = roads.map { road ->
                RoadSummary(
                    id = road.id,
                    meta = road.meta,
                    frc = road.frc.name,
                    fow = road.fow.name,
                    lengthMeters = road.lengthMeters,
                    startNodeId = road.startNodeId,
                    endNodeId = road.endNodeId
                )
            }
        ))
    }

    /**
     * Get a specific road by ID.
     *
     * GET /api/v1/roads/123
     */
    @GetMapping("/roads/{id}")
    fun getRoad(@PathVariable id: Long): ResponseEntity<Road> {
        val road = mapDatabaseService.getRoad(id)
            ?: return ResponseEntity.notFound().build()

        return ResponseEntity.ok(road)
    }

    /**
     * Get roads by metadata value.
     *
     * GET /api/v1/roads?meta=road_12345
     */
    @GetMapping("/roads")
    fun getRoadsByMeta(@RequestParam meta: String): ResponseEntity<List<Road>> {
        val roads = mapDatabaseService.getRoadsByMeta(meta)
        return ResponseEntity.ok(roads)
    }

    /**
     * Get database statistics.
     *
     * GET /api/v1/stats
     */
    @GetMapping("/stats")
    fun getStats(): ResponseEntity<StatsResponse> {
        return ResponseEntity.ok(StatsResponse(
            roadCount = mapDatabaseService.getRoadCount(),
            intersectionCount = mapDatabaseService.getIntersectionCount()
        ))
    }

    /**
     * Clear all caches.
     *
     * POST /api/v1/cache/clear
     */
    @PostMapping("/cache/clear")
    fun clearCache(): ResponseEntity<Map<String, String>> {
        logger.info("Clearing caches")
        openLrService.clearCaches()
        return ResponseEntity.ok(mapOf("message" to "Caches cleared successfully"))
    }

    /**
     * Reload OpenLR properties.
     *
     * POST /api/v1/properties/reload
     */
    @PostMapping("/properties/reload")
    fun reloadProperties(): ResponseEntity<Map<String, String>> {
        logger.info("Reloading properties")
        openLrService.reloadProperties()
        return ResponseEntity.ok(mapOf("message" to "Properties reloaded successfully"))
    }

    /**
     * Health check endpoint.
     *
     * GET /api/v1/health
     */
    @GetMapping("/health")
    fun health(): ResponseEntity<HealthResponse> {
        return ResponseEntity.ok(HealthResponse(
            status = "UP",
            roadCount = mapDatabaseService.getRoadCount(),
            intersectionCount = mapDatabaseService.getIntersectionCount()
        ))
    }
}

// Response DTOs
data class RoadsNearResponse(
    val count: Int,
    val roads: List<RoadSummary>
)

data class RoadSummary(
    val id: Long,
    val meta: String?,
    val frc: String,
    val fow: String,
    val lengthMeters: Double,
    val startNodeId: Long,
    val endNodeId: Long
)

data class StatsResponse(
    val roadCount: Int,
    val intersectionCount: Int
)

data class HealthResponse(
    val status: String,
    val roadCount: Int,
    val intersectionCount: Int
)
