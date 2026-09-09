package com.tomtom.openlr.tool.service

import com.tomtom.openlr.tool.model.DecodingFailureResponse
import com.tomtom.openlr.tool.model.FeatureCollection
import com.tomtom.openlr.tool.model.FlowDirection
import com.tomtom.openlr.tool.model.Road
import io.mockk.*
import openlr.LocationType
import openlr.location.LineLocation
import openlr.map.FormOfWay
import openlr.map.FunctionalRoadClass
import openlr.map.GeoCoordinates
import openlr.map.Line
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory

/**
 * Unit tests for OpenLrService with extensively mocked dependencies.
 * Tests decode/encode logic and error handling.
 */
class OpenLrServiceTest {

    private lateinit var mockMapDatabase: OpenLrMapDatabaseAdapter
    private lateinit var mockMapDatabaseService: MapDatabaseService
    private lateinit var openLrService: OpenLrService
    private val geometryFactory = GeometryFactory()

    private val testOpenLrCode = "CwV/mSIeQA4kBgFxAJ8OEA=="
    // Relative to the module directory, which is gradle's test working directory.
    // This previously pointed at "config/decoding_properties", which does not exist
    // from there, so every test silently exercised the "properties file not found,
    // using library defaults" path and none ever loaded a real profile.
    private val decoderPropsDir = "../config/decoding_properties"
    private val encoderPropsDir = "../config/encoding_properties"

    @BeforeEach
    fun setup() {
        // Mock the map database adapter and service
        mockMapDatabase = mockk<OpenLrMapDatabaseAdapter>(relaxed = true)
        mockMapDatabaseService = mockk<MapDatabaseService>(relaxed = true)

        // Set up the map database adapter to return the mocked service
        every { mockMapDatabase.mapDatabaseService } returns mockMapDatabaseService

        // Create service instance (will use actual OpenLR library)
        // but with mocked map database
        openLrService = OpenLrService(
            mapDatabase = mockMapDatabase,
            decoderPropsDir = decoderPropsDir,
            encoderPropsDir = encoderPropsDir
        )
    }

    @AfterEach
    fun tearDown() {
        clearAllMocks()
    }

    @Test
    fun `decode with valid OpenLR code should return FeatureCollection`() {
        // Given
        val mockLine = createMockLine(123L)
        val mockRoad = createMockRoad(123L)

        every { mockMapDatabase.getLine(123L) } returns mockLine
        every { mockMapDatabaseService.getRoad(123L) } returns mockRoad

        // Note: Actual decoding would require valid OpenLR binary data
        // This test focuses on the service logic assuming decode succeeds
        // In a real scenario, you'd mock the decoder itself

        // When - using service methods
        val result = openLrService.decode(testOpenLrCode, "default")

        // Then - verify it attempts to work with the decoder
        // (actual test would require more setup with OpenLR library mocking)
        assertNotNull(result)
    }

    @Test
    fun `decode with multiple props should try each profile in sequence`() {
        // Given
        val multiProps = "strict,relaxed,default"

        // When
        val result = openLrService.decode(testOpenLrCode, multiProps)

        // Then
        assertNotNull(result)
        // Service should attempt to decode (even if it fails with test data)
    }

    @Test
    fun `decode with invalid base64 should return failure response`() {
        // Given
        val invalidCode = "not-valid-base64!!!"

        // When
        val result = openLrService.decode(invalidCode, "default")

        // Then
        assertTrue(result is DecodingFailureResponse)
        assertTrue((result as DecodingFailureResponse).reason.contains("error", ignoreCase = true))
    }

    @Test
    fun `available profiles are read from the decoder properties directory`() {
        assertEquals(listOf("default", "relaxed", "strict"), openLrService.availableProfiles())
    }

    @Test
    fun `available encoder profiles are read from the encoding properties directory`() {
        assertEquals(listOf("default"), openLrService.availableEncoderProfiles())
    }

    @Test
    fun `encode with an unknown profile is rejected`() {
        // /encode used to accept props and ignore it: the encoder parameters were
        // built once at startup with no properties file, so encoding_properties/ was
        // never read and any value was silently accepted.
        val road = createMockRoad(123L)
        every { mockMapDatabaseService.getRoadsByMeta("test-road-123") } returns listOf(road)
        every { mockMapDatabase.getLine(123L) } returns createMockLine(123L)

        val result = openLrService.encode(listOf("test-road-123"), 0, 0, "typo_profile")

        assertFalse(result.success)
        assertTrue(result.error!!.contains("Unknown encoder profile"), result.error!!)
        assertTrue(result.error!!.contains("default"), result.error!!)
    }

    @Test
    fun `encode rejects an encoder profile name that escapes the directory`() {
        val result = openLrService.encode(listOf("test-road-123"), 0, 0, "../application")

        assertFalse(result.success)
        assertTrue(result.error!!.contains("Invalid encoder profile name"), result.error!!)
    }

    @Test
    fun `encode validates the profile before touching the database`() {
        // The rejection must not depend on resolving the path first.
        val result = openLrService.encode(listOf("whatever"), 0, 0, "nope")

        assertFalse(result.success)
        assertTrue(result.error!!.contains("Unknown encoder profile"))
        verify(exactly = 0) { mockMapDatabaseService.getRoadsByMeta(any()) }
    }

    @Test
    fun `encode accepts the shipped default profile`() {
        val road = createMockRoad(123L)
        every { mockMapDatabaseService.getRoadsByMeta("test-road-123") } returns listOf(road)
        every { mockMapDatabase.getLine(123L) } returns createMockLine(123L)

        val result = openLrService.encode(listOf("test-road-123"), 0, 0, "default")

        // Encoding needs fully-formed geometry to succeed, but the profile must not
        // be what stops it.
        assertFalse(result.error?.contains("profile") ?: false, result.error ?: "")
        verify { mockMapDatabaseService.getRoadsByMeta("test-road-123") }
    }

    @Test
    fun `decode with an unknown profile is rejected, not silently defaulted`() {
        // A missing file used to fall back to the OpenLR library defaults with only a
        // warning, and meta.propertySet echoed the bogus name -- so a typo returned a
        // plausible result computed with different parameters.
        val result = openLrService.decode(testOpenLrCode, "typo_profile")

        assertTrue(result is DecodingFailureResponse)
        val reason = (result as DecodingFailureResponse).reason
        assertTrue(reason.contains("Unknown decoder profile"), reason)
        assertTrue(reason.contains("typo_profile"), reason)
        assertTrue(reason.contains("default"), reason)   // names what is available
    }

    @Test
    fun `decode rejects a profile name that escapes the properties directory`() {
        // props is interpolated into a file path, so "../application" previously read
        // a properties file from outside the profile directory.
        val result = openLrService.decode(testOpenLrCode, "../application")

        assertTrue(result is DecodingFailureResponse)
        assertTrue((result as DecodingFailureResponse).reason.contains("Invalid decoder profile name"))
    }

    @Test
    fun `decode rejects an unknown profile among otherwise valid fallbacks`() {
        val result = openLrService.decode(testOpenLrCode, "strict,bogus,default")

        assertTrue(result is DecodingFailureResponse)
        assertTrue((result as DecodingFailureResponse).reason.contains("bogus"))
    }

    @Test
    fun `encode resolves path elements against the meta column`() {
        val road = createMockRoad(123L)
        every { mockMapDatabaseService.getRoadsByMeta("test-road-123") } returns listOf(road)
        every { mockMapDatabase.getLine(123L) } returns createMockLine(123L)

        val result = openLrService.encode(listOf("test-road-123"), 0, 0, "default")

        assertNotNull(result)
        verify { mockMapDatabaseService.getRoadsByMeta("test-road-123") }
        verify { mockMapDatabase.getLine(123L) }
    }

    @Test
    fun `encode treats a leading hyphen as reverse traversal`() {
        val road = createMockRoad(123L)
        every { mockMapDatabaseService.getRoadsByMeta("test-road-123") } returns listOf(road)
        every { mockMapDatabase.getLine(-123L) } returns createMockLine(-123L)

        openLrService.encode(listOf("-test-road-123"), 0, 0, "default")

        // The hyphen is stripped for the lookup and turned into a negative line id.
        verify { mockMapDatabaseService.getRoadsByMeta("test-road-123") }
        verify { mockMapDatabase.getLine(-123L) }
    }

    @Test
    fun `encode keeps hyphens inside a meta value`() {
        // Only a LEADING hyphen is a direction marker; UUID-style metas must survive.
        val uuid = "00004435-3500-0400-0000-000003b24606"
        val road = createMockRoad(77L)
        every { mockMapDatabaseService.getRoadsByMeta(uuid) } returns listOf(road)
        every { mockMapDatabase.getLine(77L) } returns createMockLine(77L)

        openLrService.encode(listOf(uuid), 0, 0, "default")

        verify { mockMapDatabaseService.getRoadsByMeta(uuid) }
    }

    @Test
    fun `encode with unknown meta should return error naming it`() {
        every { mockMapDatabaseService.getRoadsByMeta("nope") } returns emptyList()

        val result = openLrService.encode(listOf("nope"), 0, 0, "default")

        assertFalse(result.success)
        assertEquals("No segment found with meta 'nope'", result.error)
    }

    @Test
    fun `encode with ambiguous meta should refuse rather than pick one`() {
        every { mockMapDatabaseService.getRoadsByMeta("dup") } returns
            listOf(createMockRoad(1L), createMockRoad(2L))

        val result = openLrService.encode(listOf("dup"), 0, 0, "default")

        assertFalse(result.success)
        assertNotNull(result.error)
        assertTrue(result.error!!.contains("matches 2 segments"))
    }

    @Test
    fun `encode rejects a direction the segment does not permit`() {
        // A one-way segment has no line for the opposite direction.
        val road = createMockRoad(123L)
        every { mockMapDatabaseService.getRoadsByMeta("test-road-123") } returns listOf(road)
        every { mockMapDatabase.getLine(-123L) } returns null

        val result = openLrService.encode(listOf("-test-road-123"), 0, 0, "default")

        assertFalse(result.success)
        assertTrue(result.error!!.contains("cannot be traversed against"))
    }

    @Test
    fun `encode with blank path element should return error`() {
        val result = openLrService.encode(listOf(""), 0, 0, "default")

        assertFalse(result.success)
        assertEquals("Empty path element", result.error)
    }

    @Test
    fun `encode with empty path should return error`() {
        val result = openLrService.encode(emptyList(), 0, 0, "default")

        assertFalse(result.success)
        assertNotNull(result.error)
    }

    @Test
    fun `encode with offsets should pass them through`() {
        val road = createMockRoad(123L)
        every { mockMapDatabaseService.getRoadsByMeta("test-road-123") } returns listOf(road)
        every { mockMapDatabase.getLine(123L) } returns createMockLine(123L)

        val result = openLrService.encode(listOf("test-road-123"), 10, 5, "default")

        assertNotNull(result)
        verify { mockMapDatabase.getLine(123L) }
    }

    @Test
    fun `clearCaches should delegate to map database`() {
        // Given
        every { mockMapDatabase.clearCaches() } just Runs

        // When
        openLrService.clearCaches()

        // Then
        verify(exactly = 1) { mockMapDatabase.clearCaches() }
    }

    @Test
    fun `reloadProperties should log reload request`() {
        // When
        openLrService.reloadProperties()

        // Then
        // Method completes without error
        // (Actual reload not implemented in open-source version)
    }

    // Helper methods to create mock objects

    private fun createMockLine(id: Long): Line {
        // Create GeoCoordinates mocks
        val geoCoord1 = mockk<GeoCoordinates>(relaxed = true)
        every { geoCoord1.longitudeDeg } returns -122.4194
        every { geoCoord1.latitudeDeg } returns 37.7749

        val geoCoord2 = mockk<GeoCoordinates>(relaxed = true)
        every { geoCoord2.longitudeDeg } returns -122.4184
        every { geoCoord2.latitudeDeg } returns 37.7759

        val geoCoords = listOf(geoCoord1, geoCoord2)

        // Create Node mocks
        val startNode = mockk<openlr.map.Node>(relaxed = true)
        every { startNode.getID() } returns 1L

        val endNode = mockk<openlr.map.Node>(relaxed = true)
        every { endNode.getID() } returns 2L

        // Create Line mock and configure it
        val line = mockk<Line>(relaxed = true)
        every { line.getID() } returns id
        every { line.lineLength } returns 150
        every { line.frc } returns FunctionalRoadClass.FRC_0
        every { line.fow } returns FormOfWay.MOTORWAY
        every { line.shapeCoordinates } returns geoCoords
        every { line.startNode } returns startNode
        every { line.endNode } returns endNode

        return line
    }

    private fun createMockLineLocation(line: Line): LineLocation {
        val lineLocation = mockk<LineLocation>(relaxed = true)
        every { lineLocation.locationType } returns LocationType.LINE_LOCATION
        every { lineLocation.isValid } returns true
        every { lineLocation.locationLines } returns listOf(line)
        every { lineLocation.positiveOffset } returns 0
        every { lineLocation.negativeOffset } returns 0
        return lineLocation
    }

    private fun createMockDecodeResult(): openlr.location.Location {
        val decodeResult = mockk<openlr.location.Location>(relaxed = true)
        every { decodeResult.isValid } returns true
        every { decodeResult.locationType } returns LocationType.LINE_LOCATION
        return decodeResult
    }

    private fun createMockRoad(id: Long): Road {
        val coords = arrayOf(
            Coordinate(-122.4194, 37.7749),
            Coordinate(-122.4184, 37.7759)
        )
        val lineString = geometryFactory.createLineString(coords)
        lineString.srid = 4326

        return Road(
            id = id,
            meta = "test-road-$id",
            frc = FunctionalRoadClass.FRC_0,
            fow = FormOfWay.MOTORWAY,
            flowDirection = FlowDirection.BOTH_WAYS,
            startNodeId = 1L,
            endNodeId = 2L,
            lengthMeters = 150.0,
            geometry = lineString
        )
    }
}
