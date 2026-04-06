package com.tomtom.openlr.tool.controller

import com.ninjasquad.springmockk.MockkBean
import com.tomtom.openlr.tool.model.*
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
        val lineIds = listOf(123L, 456L, 789L)
        val encodedResponse = EncodeResponse(
            success = true,
            openLrCode = testOpenLrCode
        )
        every {
            openLrService.encode(lineIds, 0, 0, testProps)
        } returns encodedResponse

        // When & Then
        mockMvc.perform(
            post("/api/v1/encode")
                .param("path", "123")
                .param("path", "456")
                .param("path", "789")
                .param("props", testProps)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.openLrCode").value(testOpenLrCode))

        verify(exactly = 1) { openLrService.encode(lineIds, 0, 0, testProps) }
    }

    @Test
    fun `encode with invalid path should return 400 BAD REQUEST`() {
        // When & Then
        mockMvc.perform(
            post("/api/v1/encode")
                .param("path", "not-a-number")
                .param("props", testProps)
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error").value("Invalid path: all elements must be numeric line IDs"))

        verify(exactly = 0) { openLrService.encode(any(), any(), any(), any()) }
    }

    @Test
    fun `encode with offsets should pass offsets to service`() {
        // Given
        val lineIds = listOf(123L)
        val posOffset = 10
        val negOffset = 5
        val encodedResponse = EncodeResponse(success = true, openLrCode = testOpenLrCode)
        every {
            openLrService.encode(lineIds, posOffset, negOffset, testProps)
        } returns encodedResponse

        // When & Then
        mockMvc.perform(
            post("/api/v1/encode")
                .param("path", "123")
                .param("positiveOffset", posOffset.toString())
                .param("negativeOffset", negOffset.toString())
                .param("props", testProps)
        )
            .andExpect(status().isOk)

        verify(exactly = 1) { openLrService.encode(lineIds, posOffset, negOffset, testProps) }
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
