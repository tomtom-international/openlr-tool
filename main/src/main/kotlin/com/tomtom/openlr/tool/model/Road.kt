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
    END_TO_START;

    companion object {
        /**
         * Interpret the `flowdir` column of `local.roads`.
         *
         * Exactly three values are defined: `1` is two-way, `2` is one-way against the
         * digitised direction (end to start), and `3` is one-way with it (start to end).
         *
         * Returns `null` for anything else. Other values are **not** a synonym for
         * two-way — they are reserved, so that assigning one a meaning later cannot
         * change how existing data is read. `local.roads` carries a CHECK constraint
         * that keeps them out of the column; callers reading a legacy database without
         * that constraint decide how to handle `null` (see
         * `MapDatabaseService.mapFlowDirection`, which warns and falls back to two-way).
         *
         * This is the single source of truth for the mapping; do not duplicate it.
         */
        fun fromDbValue(value: Int): FlowDirection? = when (value) {
            1 -> BOTH_WAYS
            2 -> END_TO_START
            3 -> START_TO_END
            else -> null
        }
    }
}
