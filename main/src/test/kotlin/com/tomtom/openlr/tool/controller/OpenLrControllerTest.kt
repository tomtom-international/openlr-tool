package com.tomtom.openlr.tool.controller

import com.ninjasquad.springmockk.MockkBean
import com.tomtom.openlr.tool.model.*
import com.tomtom.openlr.tool.service.LruCache
import com.tomtom.openlr.tool.service.MapDatabaseService
import com.tomtom.openlr.tool.service.OpenLrService
import io.mockk.every
import io.mockk.verify
import openlr.map.FormOfWay
import openlr.map.FunctionalRoadClass
import org.junit.jupiter.api.Test
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*

/**
 * Unit tests for OpenLrController using MockMvc and mocked services.
 * Tests all API endpoints with various request formats and error scenarios.
 */
@WebMvcTest(OpenLrController::class)
class OpenLrControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockkBean
    private lateinit var openLrService: OpenLrService

    @MockkBean
    private lateinit var mapDatabaseService: MapDatabaseService

    private val geometryFactory = GeometryFactory()
    private val testOpenLrCode = "CwV/mSIeQA4kBgFxAJ8OEA=="
    private val testProps = "default"

    /**
     * Creates a mock successful GeoJSON FeatureCollection response
     */
    private fun createSuccessfulDecodeResponse(): FeatureCollection {
        val geometry = LineStringFeatureGeometry(
            coordinates = listOf(
                listOf(-122.4194, 37.7749),
                listOf(-122.4184, 37.7759)
            )
        )
        val properties = LineLocationFeatureProperties(
            meta = "test-road-123",
            direction = true,
            fow = "MOTORWAY",
            frc = "FRC0",
            lengthMeters = 150
        )
        val feature = Feature(properties = properties, geometry = geometry)
        val metadata = LineLocationMetadata(
            posOff = 0,
            negOff = 0,
            openLR = testOpenLrCode,
            propertySet = testProps,
            decodingElapsedNanos = 1000000L,
            wkt = "LINESTRING(-122.4194 37.7749, -122.4184 37.7759)"
        )
        return FeatureCollection(features = listOf(feature), meta = metadata)
    }

    @Test
    fun `decode with JSON POST should return 200 OK with GeoJSON response`() {
        // Given
        val expectedResponse = createSuccessfulDecodeResponse()
        every { openLrService.decode(testOpenLrCode, testProps) } returns expectedResponse

        // When & Then
        mockMvc.perform(
            post("/api/v1/decode")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"openLrCode": "$testOpenLrCode", "props": "$testProps"}""")
        )
            .andExpect(status().isOk)
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.type").value("FeatureCollection"))
            .andExpect(jsonPath("$.features").isArray)
            .andExpect(jsonPath("$.features[0].type").value("Feature"))
            .andExpect(jsonPath("$.features[0].geometry.type").value("LineString"))
            .andExpect(jsonPath("$.meta.propertySet").value(testProps))
            .andExpect(jsonPath("$.meta.openLR").value(testOpenLrCode))

        verify(exactly = 1) { openLrService.decode(testOpenLrCode, testProps) }
    }

    @Test
    fun `decode with form data POST should return 200 OK`() {
        // Given
        val expectedResponse = createSuccessfulDecodeResponse()
        every { openLrService.decode(testOpenLrCode, testProps) } returns expectedResponse

        // When & Then
        mockMvc.perform(
            post("/api/v1/decode")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("openLrCode", testOpenLrCode)
                .param("props", testProps)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.type").value("FeatureCollection"))
            .andExpect(jsonPath("$.meta.propertySet").value(testProps))

        verify(exactly = 1) { openLrService.decode(testOpenLrCode, testProps) }
    }

    @Test
    fun `decode with GET request should return 200 OK`() {
        // Given
        val expectedResponse = createSuccessfulDecodeResponse()
        every { openLrService.decode(testOpenLrCode, testProps) } returns expectedResponse

        // When & Then
        mockMvc.perform(
            get("/api/v1/decode")
                .param("openLrCode", testOpenLrCode)
                .param("props", testProps)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.type").value("FeatureCollection"))

        verify(exactly = 1) { openLrService.decode(testOpenLrCode, testProps) }
    }

    @Test
    fun `decode with default props should use default value`() {
        // Given
        val expectedResponse = createSuccessfulDecodeResponse()
        every { openLrService.decode(testOpenLrCode, "default") } returns expectedResponse

        // When & Then
        mockMvc.perform(
            get("/api/v1/decode")
                .param("openLrCode", testOpenLrCode)
        )
            .andExpect(status().isOk)

        verify(exactly = 1) { openLrService.decode(testOpenLrCode, "default") }
    }

    @Test
    fun `decode with multiple props should try fallback profiles`() {
        // Given
        val multiProps = "strict,relaxed,default"
        val expectedResponse = createSuccessfulDecodeResponse()
        every { openLrService.decode(testOpenLrCode, multiProps) } returns expectedResponse

        // When & Then
        mockMvc.perform(
            post("/api/v1/decode")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"openLrCode": "$testOpenLrCode", "props": "$multiProps"}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.type").value("FeatureCollection"))

        verify(exactly = 1) { openLrService.decode(testOpenLrCode, multiProps) }
    }

    @Test
    fun `decode failure should return 400 BAD REQUEST`() {
        // Given
        val failureResponse = DecodingFailureResponse(
            testOpenLrCode,
            "No valid path found for location reference"
        )
        every { openLrService.decode(testOpenLrCode, testProps) } returns failureResponse

        // When & Then
        mockMvc.perform(
            post("/api/v1/decode")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"openLrCode": "$testOpenLrCode", "props": "$testProps"}""")
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.msg").exists())
            .andExpect(jsonPath("$.reason").value("No valid path found for location reference"))

        verify(exactly = 1) { openLrService.decode(testOpenLrCode, testProps) }
    }

    @Test
    fun `encode with valid path should return 200 OK`() {
        // Given
        val path = listOf("test-road-123", "test-road-456", "-test-road-789")
        val encodedResponse = EncodeResponse(
            success = true,
            openLrCode = testOpenLrCode
        )
        every {
            openLrService.encode(path, 0, 0, testProps)
        } returns encodedResponse

        // When & Then
        mockMvc.perform(
            post("/api/v1/encode")
                .param("path", "test-road-123")
                .param("path", "test-road-456")
                .param("path", "-test-road-789")
                .param("props", testProps)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.openLrCode").value(testOpenLrCode))

        verify(exactly = 1) { openLrService.encode(path, 0, 0, testProps) }
    }

    @Test
    fun `encode passes non-numeric meta values through to the service`() {
        // meta is an opaque source reference -- a UUID is as valid as a number, so
        // the controller no longer rejects anything on shape.
        val path = listOf("00004435-3500-0400-0000-000003b24606")
        every { openLrService.encode(path, 0, 0, testProps) } returns
            EncodeResponse(success = true, openLrCode = testOpenLrCode)

        mockMvc.perform(
            post("/api/v1/encode")
                .param("path", "00004435-3500-0400-0000-000003b24606")
                .param("props", testProps)
        )
            .andExpect(status().isOk)

        verify(exactly = 1) { openLrService.encode(path, 0, 0, testProps) }
    }

    @Test
    fun `encode surfaces an unresolvable meta as 400 BAD REQUEST`() {
        val path = listOf("no-such-segment")
        every { openLrService.encode(path, 0, 0, testProps) } returns EncodeResponse(
            success = false,
            openLrCode = null,
            error = "No segment found with meta 'no-such-segment'"
        )

        mockMvc.perform(
            post("/api/v1/encode")
                .param("path", "no-such-segment")
                .param("props", testProps)
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error").value("No segment found with meta 'no-such-segment'"))
    }

    @Test
    fun `encode with offsets should pass offsets to service`() {
        // Given
        val path = listOf("test-road-123")
        val posOffset = 10
        val negOffset = 5
        val encodedResponse = EncodeResponse(success = true, openLrCode = testOpenLrCode)
        every {
            openLrService.encode(path, posOffset, negOffset, testProps)
        } returns encodedResponse

        // When & Then
        mockMvc.perform(
            post("/api/v1/encode")
                .param("path", "test-road-123")
                .param("positiveOffset", posOffset.toString())
                .param("negativeOffset", negOffset.toString())
                .param("props", testProps)
        )
            .andExpect(status().isOk)

        verify(exactly = 1) { openLrService.encode(path, posOffset, negOffset, testProps) }
    }

    @Test
    fun `getRoad should return 404 when not found`() {
        // Given
        val roadId = 999L
        every { mapDatabaseService.getRoad(roadId) } returns null

        // When & Then
        mockMvc.perform(get("/api/v1/roads/$roadId"))
            .andExpect(status().isNotFound)

        verify(exactly = 1) { mapDatabaseService.getRoad(roadId) }
    }

    private fun sampleRoad(id: Long = 359856848L) = Road(
        id = id,
        meta = "359856848",
        frc = FunctionalRoadClass.FRC_2,
        fow = FormOfWay.ROUNDABOUT,
        flowDirection = FlowDirection.START_TO_END,
        startNodeId = 3301084488246929518L,
        endNodeId = 3301089575046931412L,
        lengthMeters = 24.794,
        geometry = GeometryFactory().createLineString(
            arrayOf(Coordinate(3.3935825, 51.2993693), Coordinate(3.3938651, 51.2992761))
        )
    )

    @Test
    fun `getRoad returns GeoJSON with coordinates and no internal id`() {
        // Regression guard for the raw-JTS serialisation: returning the Road model
        // directly emitted 48 KB of nested `envelope` objects per segment and no
        // coordinates at all.
        every { mapDatabaseService.getRoad(359856848L) } returns sampleRoad()

        mockMvc.perform(get("/api/v1/roads/359856848"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.type").value("Feature"))
            .andExpect(jsonPath("$.geometry.type").value("LineString"))
            .andExpect(jsonPath("$.geometry.coordinates.length()").value(2))
            .andExpect(jsonPath("$.geometry.coordinates[0][0]").value(3.3935825))
            .andExpect(jsonPath("$.geometry.coordinates[0][1]").value(51.2993693))
            .andExpect(jsonPath("$.properties.meta").value("359856848"))
            .andExpect(jsonPath("$.properties.frc").value("FRC_2"))
            .andExpect(jsonPath("$.properties.fow").value("ROUNDABOUT"))
            .andExpect(jsonPath("$.properties.flowDirection").value("START_TO_END"))
            .andExpect(jsonPath("$.properties.lengthMeters").value(24.794))
            .andExpect(jsonPath("$.geometry.envelope").doesNotExist())
            .andExpect(jsonPath("$.properties.id").doesNotExist())
            .andExpect(jsonPath("$.id").doesNotExist())
    }

    @Test
    fun `getRoad payload stays small`() {
        // The old payload was 48,154 bytes for this two-point, 25-metre segment.
        every { mapDatabaseService.getRoad(359856848L) } returns sampleRoad()

        val body = mockMvc.perform(get("/api/v1/roads/359856848"))
            .andExpect(status().isOk)
            .andReturn().response.contentAsString

        assert(body.length < 1000) { "payload is ${body.length} bytes: $body" }
    }

    @Test
    fun `getRoadsByMeta returns a FeatureCollection with a count`() {
        every { mapDatabaseService.getRoadsByMeta("359856848") } returns listOf(sampleRoad())

        mockMvc.perform(get("/api/v1/roads").param("meta", "359856848"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.type").value("FeatureCollection"))
            .andExpect(jsonPath("$.meta.count").value(1))
            .andExpect(jsonPath("$.features.length()").value(1))
            .andExpect(jsonPath("$.features[0].geometry.coordinates.length()").value(2))
            .andExpect(jsonPath("$.features[0].properties.meta").value("359856848"))
    }

    @Test
    fun `getRoadsByMeta returns an empty collection when nothing matches`() {
        every { mapDatabaseService.getRoadsByMeta("nope") } returns emptyList()

        mockMvc.perform(get("/api/v1/roads").param("meta", "nope"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.meta.count").value(0))
            .andExpect(jsonPath("$.features.length()").value(0))
    }

    @Test
    fun `getStats should return database statistics`() {
        // Given
        every { mapDatabaseService.getRoadCount() } returns 1000
        every { mapDatabaseService.getIntersectionCount() } returns 500

        // When & Then
        mockMvc.perform(get("/api/v1/stats"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.roadCount").value(1000))
            .andExpect(jsonPath("$.intersectionCount").value(500))

        verify(exactly = 1) { mapDatabaseService.getRoadCount() }
        verify(exactly = 1) { mapDatabaseService.getIntersectionCount() }
    }

    @Test
    fun `clearCache should return success message`() {
        // Given
        every { openLrService.clearCaches() } returns Unit

        // When & Then
        mockMvc.perform(post("/api/v1/cache/clear"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.message").value("Caches cleared successfully"))

        verify(exactly = 1) { openLrService.clearCaches() }
    }

    @Test
    fun `cacheStats exposes occupancy and the bound for every cache`() {
        every { openLrService.cacheStats() } returns mapOf(
            "lines" to LruCache.Stats(size = 12, maxSize = 100, hits = 8, misses = 4, evictions = 0),
            "roads" to LruCache.Stats(size = 100, maxSize = 100, hits = 50, misses = 60, evictions = 20)
        )

        mockMvc.perform(get("/api/v1/cache/stats"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.lines.size").value(12))
            .andExpect(jsonPath("$.lines.maxSize").value(100))
            .andExpect(jsonPath("$.lines.hitRate").value(8.0 / 12.0))
            .andExpect(jsonPath("$.roads.evictions").value(20))
            .andExpect(jsonPath("$.roads.size").value(100))
    }

    @Test
    fun `reloadProperties should return success message`() {
        // Given
        every { openLrService.reloadProperties() } returns Unit

        // When & Then
        mockMvc.perform(post("/api/v1/properties/reload"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.message").value("Properties reloaded successfully"))

        verify(exactly = 1) { openLrService.reloadProperties() }
    }

    @Test
    fun `health check should return UP status`() {
        // Given
        every { mapDatabaseService.getRoadCount() } returns 1000
        every { mapDatabaseService.getIntersectionCount() } returns 500

        // When & Then
        mockMvc.perform(get("/api/v1/health"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("UP"))
            .andExpect(jsonPath("$.roadCount").value(1000))
            .andExpect(jsonPath("$.intersectionCount").value(500))
    }
}
