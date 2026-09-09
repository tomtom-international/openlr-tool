package com.tomtom.openlr.tool.model

/**
 * GeoJSON response models for OpenLR decoding.
 */
open class DecodingResponse

/**
 * GeoJSON FeatureCollection - main response for successful decoding.
 */
data class FeatureCollection(
    val features: List<Feature>,
    val meta: LocationMetadata
) : DecodingResponse() {
    val type: String = "FeatureCollection"
}

/**
 * GeoJSON Feature - represents a single road segment.
 */
data class Feature(
    val properties: FeatureProperties,
    val geometry: FeatureGeometry
) {
    val type: String = "Feature"
}

/**
 * Base class for feature geometry.
 */
open class FeatureGeometry

/**
 * LineString geometry for road segments.
 */
data class LineStringFeatureGeometry(
    val coordinates: List<List<Double>>
) : FeatureGeometry() {
    val type: String = "LineString"
}

/**
 * Base class for feature properties.
 */
open class FeatureProperties

/**
 * Properties for a line location feature.
 */
data class LineLocationFeatureProperties(
    /**
     * The source-network reference from `local.roads.meta`. This is the only
     * segment identifier the API exposes: `local.roads.id` is an opaque internal
     * key that exists because the OpenLR library requires a Long per line, and it
     * is deliberately not published. `/api/v1/encode` accepts these same `meta`
     * values in `path`, so a decode result can be re-encoded directly.
     */
    val meta: String,
    val direction: Boolean,
    val fow: String,
    val frc: String,
    val lengthMeters: Int
) : FeatureProperties()

/**
 * Properties of a road segment returned by the network query endpoints.
 *
 * Deliberately mirrors [LineLocationFeatureProperties] and deliberately omits
 * `local.roads.id`, which is an opaque internal key. Serialising the [Road] model
 * directly instead used to emit the JTS geometry object by reflection: 48 KB for a
 * 25-metre segment, consisting of an `envelope` chain 3,424 levels deep, with the
 * coordinates absent entirely.
 */
data class RoadFeatureProperties(
    val meta: String,
    val frc: String,
    val fow: String,
    val flowDirection: String,
    val lengthMeters: Double,
    val startNodeId: Long,
    val endNodeId: Long
) : FeatureProperties()

/**
 * Base class for location metadata.
 */
open class LocationMetadata

/**
 * Metadata for a line location.
 */
data class LineLocationMetadata(
    val posOff: Int,
    val negOff: Int,
    val openLR: String,
    val propertySet: String,
    val decodingElapsedNanos: Long,
    val wkt: String
) : LocationMetadata()

/**
 * Metadata for a road query result.
 */
data class RoadQueryMetadata(
    val count: Int
) : LocationMetadata()

/**
 * Response for failed decoding.
 */
class DecodingFailureResponse(code: String, failureReason: String) : DecodingResponse() {
    val msg = "Failed to decode $code"
    val reason = failureReason
}
