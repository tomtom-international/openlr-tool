package com.tomtom.openlr.tool.model

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Unit tests for GeoJSON model classes.
 * Tests serialization/deserialization and structure validation.
 */
class GeoJsonModelsTest {

    private val objectMapper: ObjectMapper = jacksonObjectMapper()

    @Test
    fun `FeatureCollection should serialize to valid GeoJSON`() {
        // Given
        val featureCollection = createTestFeatureCollection()

        // When
        val json = objectMapper.writeValueAsString(featureCollection)
        val parsed = objectMapper.readTree(json)

        // Then
        assertEquals("FeatureCollection", parsed.get("type").asText())
        assertTrue(parsed.has("features"))
        assertTrue(parsed.has("meta"))
        assertTrue(parsed.get("features").isArray)
        assertEquals(2, parsed.get("features").size())
    }

    @Test
    fun `Feature should have correct GeoJSON structure`() {
        // Given
        val feature = createTestFeature()

        // When
        val json = objectMapper.writeValueAsString(feature)
        val parsed = objectMapper.readTree(json)

        // Then
        assertEquals("Feature", parsed.get("type").asText())
        assertTrue(parsed.has("geometry"))
        assertTrue(parsed.has("properties"))
    }

    @Test
    fun `LineStringFeatureGeometry should serialize coordinates correctly`() {
        // Given
        val geometry = LineStringFeatureGeometry(
            coordinates = listOf(
                listOf(-122.4194, 37.7749),
                listOf(-122.4184, 37.7759),
                listOf(-122.4174, 37.7769)
            )
        )

        // When
        val json = objectMapper.writeValueAsString(geometry)
        val parsed = objectMapper.readTree(json)

        // Then
        assertEquals("LineString", parsed.get("type").asText())
        assertTrue(parsed.has("coordinates"))
        assertEquals(3, parsed.get("coordinates").size())
        assertEquals(-122.4194, parsed.get("coordinates")[0][0].asDouble(), 0.0001)
        assertEquals(37.7749, parsed.get("coordinates")[0][1].asDouble(), 0.0001)
    }

    @Test
    fun `LineLocationFeatureProperties should include all required fields`() {
        // Given
        val properties = LineLocationFeatureProperties(
            meta = "test-road-123",
            direction = true,
            fow = "MOTORWAY",
            frc = "FRC0",
            lengthMeters = 150
        )

        // When
        val json = objectMapper.writeValueAsString(properties)
        val parsed = objectMapper.readTree(json)

        // Then
        assertEquals("test-road-123", parsed.get("meta").asText())
        assertTrue(parsed.get("direction").asBoolean())
        assertEquals("MOTORWAY", parsed.get("fow").asText())
        assertEquals("FRC0", parsed.get("frc").asText())
        assertEquals(150, parsed.get("lengthMeters").asInt())
    }

    @Test
    fun `LineLocationMetadata should include all metadata fields`() {
        // Given
        val metadata = LineLocationMetadata(
            posOff = 10,
            negOff = 5,
            openLR = "CwV/mSIeQA4kBgFxAJ8OEA==",
            propertySet = "default",
            decodingElapsedNanos = 1234567L,
            wkt = "LINESTRING(-122.4194 37.7749, -122.4184 37.7759)"
        )

        // When
        val json = objectMapper.writeValueAsString(metadata)
        val parsed = objectMapper.readTree(json)

        // Then
        assertEquals(10, parsed.get("posOff").asInt())
        assertEquals(5, parsed.get("negOff").asInt())
        assertEquals("CwV/mSIeQA4kBgFxAJ8OEA==", parsed.get("openLR").asText())
        assertEquals("default", parsed.get("propertySet").asText())
        assertEquals(1234567L, parsed.get("decodingElapsedNanos").asLong())
        assertTrue(parsed.has("wkt"))
    }

    @Test
    fun `DecodingFailureResponse should include error details`() {
        // Given
        val failureResponse = DecodingFailureResponse(
            code = "CwV/mSIeQA4kBgFxAJ8OEA==",
            failureReason = "No valid path found"
        )

        // When
        val json = objectMapper.writeValueAsString(failureResponse)
        val parsed = objectMapper.readTree(json)

        // Then
        assertTrue(parsed.has("msg"))
        assertTrue(parsed.get("msg").asText().contains("Failed to decode"))
        assertEquals("No valid path found", parsed.get("reason").asText())
    }

    @Test
    fun `empty FeatureCollection should serialize correctly`() {
        // Given
        val metadata = LineLocationMetadata(
            posOff = 0,
            negOff = 0,
            openLR = "test",
            propertySet = "default",
            decodingElapsedNanos = 0L,
            wkt = ""
        )
        val emptyCollection = FeatureCollection(features = emptyList(), meta = metadata)

        // When
        val json = objectMapper.writeValueAsString(emptyCollection)
        val parsed = objectMapper.readTree(json)

        // Then
        assertEquals("FeatureCollection", parsed.get("type").asText())
        assertTrue(parsed.get("features").isArray)
        assertEquals(0, parsed.get("features").size())
    }

    @Test
    fun `Feature with negative direction should serialize correctly`() {
        // Given
        val properties = LineLocationFeatureProperties(
            meta = "road-456",
            direction = false,
            fow = "SINGLE_CARRIAGEWAY",
            frc = "FRC3",
            lengthMeters = 75
        )
        val geometry = LineStringFeatureGeometry(
            coordinates = listOf(listOf(-122.0, 37.0), listOf(-122.1, 37.1))
        )
        val feature = Feature(properties = properties, geometry = geometry)

        // When
        val json = objectMapper.writeValueAsString(feature)
        val parsed = objectMapper.readTree(json)

        // Then
        assertEquals("Feature", parsed.get("type").asText())
        assertFalse(parsed.get("properties").get("direction").asBoolean())
    }

    @Test
    fun `metadata with zero offsets should serialize correctly`() {
        // Given
        val metadata = LineLocationMetadata(
            posOff = 0,
            negOff = 0,
            openLR = "ABC123",
            propertySet = "strict",
            decodingElapsedNanos = 500000L,
            wkt = "LINESTRING(0 0, 1 1)"
        )

        // When
        val json = objectMapper.writeValueAsString(metadata)
        val parsed = objectMapper.readTree(json)

        // Then
        assertEquals(0, parsed.get("posOff").asInt())
        assertEquals(0, parsed.get("negOff").asInt())
    }

    @Test
    fun `metadata with non-zero offsets should serialize correctly`() {
        // Given
        val metadata = LineLocationMetadata(
            posOff = 25,
            negOff = 15,
            openLR = "XYZ789",
            propertySet = "relaxed",
            decodingElapsedNanos = 750000L,
            wkt = "LINESTRING(10 10, 20 20)"
        )

        // When
        val json = objectMapper.writeValueAsString(metadata)
        val parsed = objectMapper.readTree(json)

        // Then
        assertEquals(25, parsed.get("posOff").asInt())
        assertEquals(15, parsed.get("negOff").asInt())
    }

    // Helper methods

    private fun createTestFeatureCollection(): FeatureCollection {
        val features = listOf(
            createTestFeature(),
            createTestFeature(roadId = "road-456")
        )
        val metadata = LineLocationMetadata(
            posOff = 0,
            negOff = 0,
            openLR = "CwV/mSIeQA4kBgFxAJ8OEA==",
            propertySet = "default",
            decodingElapsedNanos = 1234567L,
            wkt = "LINESTRING(-122.4194 37.7749, -122.4184 37.7759)"
        )
        return FeatureCollection(features = features, meta = metadata)
    }

    private fun createTestFeature(roadId: String = "road-123"): Feature {
        val geometry = LineStringFeatureGeometry(
            coordinates = listOf(
                listOf(-122.4194, 37.7749),
                listOf(-122.4184, 37.7759)
            )
        )
        val properties = LineLocationFeatureProperties(
            meta = roadId,
            direction = true,
            fow = "MOTORWAY",
            frc = "FRC0",
            lengthMeters = 150
        )
        return Feature(properties = properties, geometry = geometry)
    }
}
