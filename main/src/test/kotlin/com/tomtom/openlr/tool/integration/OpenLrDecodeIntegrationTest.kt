package com.tomtom.openlr.tool.integration

import com.tomtom.openlr.tool.model.Road
import com.tomtom.openlr.tool.service.MapDatabaseService
import io.mockk.*
import openlr.map.FormOfWay
import openlr.map.FunctionalRoadClass
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.io.WKTReader
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import com.tomtom.openlr.tool.model.FlowDirection

/**
 * Integration tests based on recorded PostgreSQL queries from actual decode operations.
 *
 * These tests replay actual database interactions captured from decoding the OpenLR code:
 * "CwV/mSIeQA4kBgFxAJ8OEA==" which decodes to a path of 6 road segments in Basel, Switzerland.
 *
 * Recorded queries:
 * 1. findRoadsNear(7.732154, 47.978657, 100m) - finds candidate roads near start point
 * 2. findRoadsNear(7.735844, 47.980247, 100m) - finds candidate roads near end point
 * 3. getRoadsConnectedToIntersection(13253605) - finds connected roads for path search
 * 4. Multiple getRoadsConnectedToIntersection queries for path exploration
 */
class OpenLrDecodeIntegrationTest {

    private lateinit var jdbcTemplate: JdbcTemplate
    private lateinit var mapDatabaseService: MapDatabaseService
    private val geometryFactory = GeometryFactory()
    private val wktReader = WKTReader(geometryFactory)

    @BeforeEach
    fun setup() {
        jdbcTemplate = mockk(relaxed = true)
        mapDatabaseService = MapDatabaseService(
            jdbcTemplate = jdbcTemplate,
            schema = "local",
            roadsTable = "roads",
            intersectionsTable = "intersections",
            cacheSize = 1000
        )
    }

    @AfterEach
    fun tearDown() {
        clearAllMocks()
    }

    @Test
    fun `findRoadsNear should return roads matching actual decode start point query`() {
        // Given - real coordinates from OpenLR decode start point
        val startLon = 7.732154130898799
        val startLat = 47.978657483825785
        val distance = 100

        // Real roads returned by PostgreSQL for this query
        val realRoads = listOf(
            createRoadFromDb(
                id = 2043028L,
                meta = "00004435-3500-0400-0000-000003b24606",
                frc = 0, // FRC_0
                fow = 1, // ROUNDABOUT
                flowdir = 3, // BOTH_WAYS
                fromInt = 2236769L,
                toInt = 2232145L,
                len = 78,
                wkt = "LINESTRING(7.7314455 47.9781425,7.7316803 47.978311,7.7319286 47.9784858,7.732167 47.9786525)"
            ),
            createRoadFromDb(
                id = 2043037L,
                meta = "00004435-3500-0400-0000-000003b24617",
                frc = 0,
                fow = 1,
                flowdir = 3,
                fromInt = 2232145L,
                toInt = 2232138L,
                len = 225,
                wkt = "LINESTRING(7.732167 47.9786525,7.7324133 47.9788294,7.732755 47.9790717,7.7333172 47.979465,7.7337642 47.9797808,7.7342528 47.9801224)"
            ),
            createRoadFromDb(
                id = 15294178L,
                meta = "f3f3234a-2922-43bd-b86f-eab9f8545cb6",
                frc = 1, // FRC_1
                fow = 6, // SLIPROAD
                flowdir = 3,
                fromInt = 2232145L,
                toInt = 13253605L,
                len = 218,
                wkt = "LINESTRING(7.732167 47.9786525,7.7324312 47.9787797,7.7326965 47.9789108,7.7328772 47.9789822,7.7331602 47.9790617,7.7334021 47.979104,7.733633 47.9791187,7.7341327 47.9791376,7.7344159 47.9791689,7.7345334 47.9791899,7.7346678 47.9792214,7.7347812 47.9792613,7.7348638 47.9793004)"
            )
        )

        // Mock the findRoadsNear query (with 2-arg jdbcTemplate.query)
        every {
            jdbcTemplate.query(
                match<String> { sql ->
                    sql.contains("ST_DistanceSpheroid") &&
                    sql.contains("ST_Buffer") &&
                    sql.contains(startLon.toString())
                },
                any<RowMapper<Road>>()
            )
        } returns realRoads

        // When
        val result = mapDatabaseService.findRoadsNear(startLon, startLat, distance)

        // Then
        assertEquals(3, result.size)

        // Verify first road matches actual database record
        val firstRoad = result[0]
        assertEquals(2043028L, firstRoad.id)
        assertEquals("00004435-3500-0400-0000-000003b24606", firstRoad.meta)
        assertEquals(FunctionalRoadClass.FRC_0, firstRoad.frc)
        assertEquals(78.0, firstRoad.lengthMeters)

        // Verify the critical road that starts the decoded path
        val decodedStartRoad = result.find { it.id == 15294178L }
        assertNotNull(decodedStartRoad)
        assertEquals("f3f3234a-2922-43bd-b86f-eab9f8545cb6", decodedStartRoad?.meta)
        assertEquals(FunctionalRoadClass.FRC_1, decodedStartRoad?.frc)
        assertEquals(FormOfWay.SLIPROAD, decodedStartRoad?.fow)
        assertEquals(218.0, decodedStartRoad?.lengthMeters)
        assertEquals(2232145L, decodedStartRoad?.startNodeId)
        assertEquals(13253605L, decodedStartRoad?.endNodeId)
    }

    @Test
    fun `getRoadsConnectedToIntersection should match actual path search queries`() {
        // Given - real intersection ID from decode operation
        val intersectionId = 13253605L

        // Real roads connected to this intersection (captured from logs)
        val connectedRoads = listOf(
            createRoadFromDb(
                id = 15294179L,
                meta = "8d261fc1-95a8-4c5f-b98c-242d1ad23eae",
                frc = 1,
                fow = 6,
                flowdir = 3,
                fromInt = 13253605L,
                toInt = 2232146L,
                len = 14,
                wkt = "LINESTRING(7.7348638 47.9793004,7.7350248 47.9793768)"
            ),
            createRoadFromDb(
                id = 1986653L,
                meta = "00004435-3500-0400-0000-000003bf455c",
                frc = 1,
                fow = 6,
                flowdir = 3,
                fromInt = 2232146L,
                toInt = 13253605L,
                len = 11,
                wkt = "LINESTRING(7.7350248 47.9793768,7.7350646 47.9793915,7.7351528 47.9794313)"
            )
        )

        // Mock the getRoadsConnectedToIntersection query
        every {
            jdbcTemplate.query(
                match<String> { sql ->
                    sql.contains("WHERE r.from_int = ?") ||
                    sql.contains("WHERE r.from_int = \$1")
                },
                any<RowMapper<Road>>(),
                intersectionId,
                intersectionId
            )
        } returns connectedRoads

        // When
        val result = mapDatabaseService.getRoadsConnectedToIntersection(intersectionId)

        // Then
        assertEquals(2, result.size)

        // Verify actual roads that continue the path
        val secondSegment = result.find { it.id == 15294179L }
        assertNotNull(secondSegment)
        assertEquals("8d261fc1-95a8-4c5f-b98c-242d1ad23eae", secondSegment?.meta)
        assertEquals(14.0, secondSegment?.lengthMeters)

        verify {
            jdbcTemplate.query(
                any<String>(),
                any<RowMapper<Road>>(),
                intersectionId,
                intersectionId
            )
        }
    }

    @Test
    fun `decode operation should use multiple spatial queries in sequence`() {
        // This test verifies the query pattern observed during actual decoding:
        // 1. findRoadsNear(startPoint, 100m) - 2 queries
        // 2. Multiple getRoadsConnectedToIntersection - for path exploration

        val queryLog = mutableListOf<String>()

        // Mock to capture all query patterns
        every {
            jdbcTemplate.query(
                capture(queryLog),
                any<RowMapper<Road>>()
            )
        } returns emptyList()

        // Simulate the decode pattern
        mapDatabaseService.findRoadsNear(7.732154, 47.978657, 100)
        mapDatabaseService.findRoadsNear(7.735844, 47.980247, 100)

        // Verify we captured spatial queries
        assertTrue(queryLog.any { it.contains("ST_DistanceSpheroid") })
        assertTrue(queryLog.any { it.contains("ST_Buffer") })
        assertEquals(2, queryLog.count { it.contains("findRoadsNear") || it.contains("ST_Buffer") })
    }

    // Helper to create Road from database values matching actual PostgreSQL schema
    private fun createRoadFromDb(
        id: Long,
        meta: String,
        frc: Int,
        fow: Int,
        flowdir: Int,
        fromInt: Long,
        toInt: Long,
        len: Int,
        wkt: String
    ): Road {
        val geometry = wktReader.read(wkt) as org.locationtech.jts.geom.LineString
        geometry.srid = 4326

        return Road(
            id = id,
            meta = meta,
            frc = FunctionalRoadClass.getFRCs()[frc],
            fow = FormOfWay.getFOWs()[fow],
            flowDirection = when(flowdir) {
                0 -> FlowDirection.BOTH_WAYS
                1 -> FlowDirection.START_TO_END
                2 -> FlowDirection.END_TO_START
                else -> FlowDirection.BOTH_WAYS
            },
            startNodeId = fromInt,
            endNodeId = toInt,
            lengthMeters = len.toDouble(),
            geometry = geometry
        )
    }
}
