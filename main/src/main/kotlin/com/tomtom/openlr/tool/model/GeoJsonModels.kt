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
    val meta: String,
    val direction: Boolean,
    val fow: String,
    val frc: String,
    val lengthMeters: Int
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
 * Response for failed decoding.
 */
class DecodingFailureResponse(code: String, failureReason: String) : DecodingResponse() {
    val msg = "Failed to decode $code"
    val reason = failureReason
}
