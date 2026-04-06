package com.tomtom.openlr.tool.model

import openlr.map.FormOfWay
import openlr.map.FunctionalRoadClass
import org.locationtech.jts.geom.LineString

/**
 * Represents a road network segment (link).
 */
data class Road(
    val id: Long,
    val meta: String?,
    val frc: FunctionalRoadClass,
    val fow: FormOfWay,
    val flowDirection: FlowDirection,
    val startNodeId: Long,
    val endNodeId: Long,
    val lengthMeters: Double,
    val geometry: LineString
)

/**
 * Traffic flow direction on a road segment.
 */
enum class FlowDirection {
    /** Traffic flows in both directions */
    BOTH_WAYS,

    /** Traffic flows from start to end node only */
    START_TO_END,

    /** Traffic flows from end to start node only */
    END_TO_START
}
