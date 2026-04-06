package com.tomtom.openlr.tool.service

import com.tomtom.openlr.tool.model.FlowDirection
import com.tomtom.openlr.tool.model.Intersection
import com.tomtom.openlr.tool.model.Road
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import openlr.map.FormOfWay
import openlr.map.FunctionalRoadClass
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper

/**
 * Unit tests for MapDatabaseService with mocked JDBC operations.
 * Tests all database query methods with various scenarios.
 */
class MapDatabaseServiceTest {

    private lateinit var jdbcTemplate: JdbcTemplate
    private lateinit var mapDatabaseService: MapDatabaseService
    private val geometryFactory = GeometryFactory()

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

    @Test
    fun `getRoad should return road when found`() {
        // Given
        val roadId = 123L
        val expectedRoad = createTestRoad(roadId)
        every {
            jdbcTemplate.query(
                any<String>(),
                any<RowMapper<Road>>(),
                roadId
            )
        } returns listOf(expectedRoad)

        // When
        val result = mapDatabaseService.getRoad(roadId)

        // Then
        assertNotNull(result)
        assertEquals(roadId, result!!.id)
        assertEquals("test-road-123", result.meta)
        verify { jdbcTemplate.query(any<String>(), any<RowMapper<Road>>(), roadId) }
    }

    @Test
    fun `getRoad should return null when not found`() {
        // Given
        val roadId = 999L
        every {
            jdbcTemplate.query(
                any<String>(),
                any<RowMapper<Road>>(),
                roadId
            )
        } returns emptyList()

        // When
        val result = mapDatabaseService.getRoad(roadId)

        // Then
        assertNull(result)
        verify { jdbcTemplate.query(any<String>(), any<RowMapper<Road>>(), roadId) }
    }

    @Test
    fun `getRoadsByMeta should return matching roads`() {
        // Given
        val metaValue = "road-123"
        val roads = listOf(
            createTestRoad(1L, metaValue),
            createTestRoad(2L, metaValue)
        )
        every {
            jdbcTemplate.query(
                any<String>(),
                any<RowMapper<Road>>(),
                metaValue
            )
        } returns roads

        // When
        val result = mapDatabaseService.getRoadsByMeta(metaValue)

        // Then
        assertEquals(2, result.size)
        assertTrue(result.all { it.meta == metaValue })
        verify { jdbcTemplate.query(any<String>(), any<RowMapper<Road>>(), metaValue) }
    }

    @Test
    fun `getRoadsByMeta should return empty list when no matches`() {
        // Given
        val metaValue = "nonexistent"
        every {
            jdbcTemplate.query(
                any<String>(),
                any<RowMapper<Road>>(),
                metaValue
            )
        } returns emptyList()

        // When
        val result = mapDatabaseService.getRoadsByMeta(metaValue)

        // Then
        assertTrue(result.isEmpty())
        verify { jdbcTemplate.query(any<String>(), any<RowMapper<Road>>(), metaValue) }
    }

    @Test
    fun `getIntersection should return intersection when found`() {
        // Given
        val intersectionId = 456L
        val expectedIntersection = Intersection(
            id = intersectionId,
            meta = "intersection-456",
            longitude = -122.4194,
            latitude = 37.7749
        )
        every {
            jdbcTemplate.query(
                any<String>(),
                any<RowMapper<Intersection>>(),
                intersectionId
            )
        } returns listOf(expectedIntersection)

        // When
        val result = mapDatabaseService.getIntersection(intersectionId)

        // Then
        assertNotNull(result)
        assertEquals(intersectionId, result!!.id)
        assertEquals("intersection-456", result.meta)
        verify { jdbcTemplate.query(any<String>(), any<RowMapper<Intersection>>(), intersectionId) }
    }

    @Test
    fun `getIntersection should return null when not found`() {
        // Given
        val intersectionId = 999L
        every {
            jdbcTemplate.query(
                any<String>(),
                any<RowMapper<Intersection>>(),
                intersectionId
            )
        } returns emptyList()

        // When
        val result = mapDatabaseService.getIntersection(intersectionId)

        // Then
        assertNull(result)
        verify { jdbcTemplate.query(any<String>(), any<RowMapper<Intersection>>(), intersectionId) }
    }

    @Test
    fun `findRoadsNear should return roads within distance`() {
        // Given
        val lon = -122.4194
        val lat = 37.7749
        val distance = 100
        val nearbyRoads = listOf(
            createTestRoad(1L),
            createTestRoad(2L)
        )
        every {
            jdbcTemplate.query(
                any<String>(),
                any<RowMapper<Road>>()
            )
        } returns nearbyRoads

        // When
        val result = mapDatabaseService.findRoadsNear(lon, lat, distance)

        // Then
        assertEquals(2, result.size)
        verify { jdbcTemplate.query(any<String>(), any<RowMapper<Road>>()) }
    }

    @Test
    fun `findRoadsNear should return empty list when no roads nearby`() {
        // Given
        val lon = -122.4194
        val lat = 37.7749
        val distance = 10
        every {
            jdbcTemplate.query(
                any<String>(),
                any<RowMapper<Road>>()
            )
        } returns emptyList()

        // When
        val result = mapDatabaseService.findRoadsNear(lon, lat, distance)

        // Then
        assertTrue(result.isEmpty())
        verify { jdbcTemplate.query(any<String>(), any<RowMapper<Road>>()) }
    }

    @Test
    fun `getRoadCount should return total road count`() {
        // Given
        val expectedCount = 5000
        every {
            jdbcTemplate.queryForObject(any<String>(), eq(Int::class.java))
        } returns expectedCount

        // When
        val result = mapDatabaseService.getRoadCount()

        // Then
        assertEquals(expectedCount, result)
        verify { jdbcTemplate.queryForObject(any<String>(), eq(Int::class.java)) }
    }

    @Test
    fun `getRoadCount should return 0 when table is empty`() {
        // Given
        every {
            jdbcTemplate.queryForObject(any<String>(), eq(Int::class.java))
        } returns 0

        // When
        val result = mapDatabaseService.getRoadCount()

        // Then
        assertEquals(0, result)
        verify { jdbcTemplate.queryForObject(any<String>(), eq(Int::class.java)) }
    }

    @Test
    fun `getIntersectionCount should return total intersection count`() {
        // Given
        val expectedCount = 2500
        every {
            jdbcTemplate.queryForObject(any<String>(), eq(Int::class.java))
        } returns expectedCount

        // When
        val result = mapDatabaseService.getIntersectionCount()

        // Then
        assertEquals(expectedCount, result)
        verify { jdbcTemplate.queryForObject(any<String>(), eq(Int::class.java)) }
    }

    @Test
    fun `getIntersectionCount should return 0 when table is empty`() {
        // Given
        every {
            jdbcTemplate.queryForObject(any<String>(), eq(Int::class.java))
        } returns 0

        // When
        val result = mapDatabaseService.getIntersectionCount()

        // Then
        assertEquals(0, result)
        verify { jdbcTemplate.queryForObject(any<String>(), eq(Int::class.java)) }
    }

    @Test
    fun `findRoadsNear with zero distance should return roads at exact location`() {
        // Given
        val lon = -122.4194
        val lat = 37.7749
        val distance = 0
        val exactRoads = listOf(createTestRoad(1L))
        every {
            jdbcTemplate.query(
                any<String>(),
                any<RowMapper<Road>>()
            )
        } returns exactRoads

        // When
        val result = mapDatabaseService.findRoadsNear(lon, lat, distance)

        // Then
        assertEquals(1, result.size)
        verify { jdbcTemplate.query(any<String>(), any<RowMapper<Road>>()) }
    }

    // Helper methods

    private fun createTestRoad(id: Long, meta: String = "test-road-$id"): Road {
        val coords = arrayOf(
            Coordinate(-122.4194, 37.7749),
            Coordinate(-122.4184, 37.7759)
        )
        val lineString = geometryFactory.createLineString(coords)
        lineString.srid = 4326

        return Road(
            id = id,
            meta = meta,
            frc = FunctionalRoadClass.FRC_2,
            fow = FormOfWay.SINGLE_CARRIAGEWAY,
            flowDirection = FlowDirection.BOTH_WAYS,
            startNodeId = 1L,
            endNodeId = 2L,
            lengthMeters = 150.0,
            geometry = lineString
        )
    }
}
