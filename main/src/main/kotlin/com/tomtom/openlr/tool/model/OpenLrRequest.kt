package com.tomtom.openlr.tool.model

/**
 * Request to decode an OpenLR location reference.
 */
data class DecodeRequest(
    val openLrCode: String,
    val props: String = "default"
)

/**
 * Request to encode a path as an OpenLR location reference.
 */
data class EncodeRequest(
    val path: List<String>,
    val positiveOffset: Int = 0,
    val negativeOffset: Int = 0,
    val encoderProperties: String = "default"
)

/**
 * Response from decoding operation.
 */
data class DecodeResponse(
    val success: Boolean,
    val location: LocationData?,
    val error: String? = null
)

/**
 * Response from encoding operation.
 */
data class EncodeResponse(
    val success: Boolean,
    val openLrCode: String?,
    val error: String? = null
)

/**
 * Decoded location data.
 */
data class LocationData(
    val type: String,
    val coordinates: List<Coordinate>,
    val roads: List<RoadInfo>
)

data class Coordinate(
    val longitude: Double,
    val latitude: Double
)

data class RoadInfo(
    val id: Long,
    val meta: String?,
    val frc: String,
    val fow: String
)
