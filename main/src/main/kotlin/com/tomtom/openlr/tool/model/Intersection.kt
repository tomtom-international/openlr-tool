package com.tomtom.openlr.tool.model

/**
 * Represents a road network intersection (node/junction).
 */
data class Intersection(
    val id: Long,
    val meta: String?,
    val longitude: Double,
    val latitude: Double
)
