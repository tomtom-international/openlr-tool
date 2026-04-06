package com.tomtom.openlr.tool.service

import com.tomtom.openlr.tool.model.FlowDirection
import com.tomtom.openlr.tool.model.Road
import openlr.map.*
import org.locationtech.jts.geom.LineString
import org.springframework.stereotype.Component
import java.awt.geom.Point2D
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Adapter that bridges MapDatabaseService to OpenLR library's MapDatabase interface.
 *
 * This adapter wraps our simplified MapDatabaseService and presents it as the
 * MapDatabase interface expected by the OpenLR encoder/decoder libraries.
 */
@Component
class OpenLrMapDatabaseAdapter(
    val mapDatabaseService: MapDatabaseService
) : MapDatabase {

    private val lineCache = ConcurrentHashMap<Long, Line>()
    private val nodeCache = ConcurrentHashMap<Long, Node>()

    override fun findLinesCloseByCoordinate(
        longitude: Double,
        latitude: Double,
        distance: Int
    ): Iterator<Line> {
        val roads = mapDatabaseService.findRoadsNear(longitude, latitude, distance)
        val lines = roads.flatMap { road -> convertRoadToLines(road) }
        return lines.iterator()
    }

    override fun findNodesCloseByCoordinate(
        longitude: Double,
        latitude: Double,
        distance: Int
    ): Iterator<Node> {
        // Not used by the decoder/encoder in practice
        return emptyList<Node>().iterator()
    }

    override fun getLine(id: Long): Line? {
        return lineCache[id] ?: run {
            val road = mapDatabaseService.getRoad(Math.abs(id)) ?: return null
            val lines = convertRoadToLines(road)
            // Cache all generated lines
            lines.forEach { lineCache[it.id] = it }
            lines.firstOrNull { it.id == id }
        }
    }

    override fun getNode(id: Long): Node? {
        return nodeCache[id] ?: run {
            val intersection = mapDatabaseService.getIntersection(id) ?: return null
            val node = NodeAdapter(
                id = intersection.id,
                longitude = intersection.longitude,
                latitude = intersection.latitude,
                mapDatabase = this
            )
            nodeCache[id] = node
            node
        }
    }

    override fun getNumberOfLines(): Int {
        return mapDatabaseService.getRoadCount()
    }

    override fun getNumberOfNodes(): Int {
        return mapDatabaseService.getIntersectionCount()
    }

    override fun hasTurnRestrictions(): Boolean = false

    override fun hasTurnRestrictionOnPath(path: MutableList<out Line>): Boolean = false

    override fun getAllLines(): Iterator<Line> {
        throw UnsupportedOperationException("getAllLines() not implemented - use findClosestLines() instead")
    }

    override fun getAllNodes(): Iterator<Node> {
        throw UnsupportedOperationException("getAllNodes() not implemented")
    }

    override fun getMapBoundingBox(): java.awt.geom.Rectangle2D.Double? {
        // Return null to indicate no specific bounding box restriction
        return null
    }

    fun clearCaches() {
        lineCache.clear()
        nodeCache.clear()
        mapDatabaseService.clearCaches()
    }

    /**
     * Convert a Road to OpenLR Line objects.
     * A bidirectional road becomes two Lines (forward and reverse).
     */
    private fun convertRoadToLines(road: Road): List<Line> {
        return when (road.flowDirection) {
            FlowDirection.BOTH_WAYS -> listOf(
                createLine(road, id = road.id, reverse = false),
                createLine(road, id = -road.id, reverse = true)
            )
            FlowDirection.START_TO_END -> listOf(
                createLine(road, id = road.id, reverse = false)
            )
            FlowDirection.END_TO_START -> listOf(
                createLine(road, id = -road.id, reverse = true)
            )
        }
    }

    private fun createLine(road: Road, id: Long, reverse: Boolean): Line {
        val geometry = if (reverse) road.geometry.reverse() as LineString else road.geometry
        val startNodeId = if (reverse) road.endNodeId else road.startNodeId
        val endNodeId = if (reverse) road.startNodeId else road.endNodeId

        return LineAdapter(
            id = id,
            road = road,
            geometry = geometry,
            startNodeId = startNodeId,
            endNodeId = endNodeId,
            mapDatabase = this
        )
    }
}

/**
 * OpenLR Line implementation backed by our Road model.
 */
private class LineAdapter(
    private val id: Long,
    private val road: Road,
    private val geometry: LineString,
    private val startNodeId: Long,
    private val endNodeId: Long,
    private val mapDatabase: OpenLrMapDatabaseAdapter
) : Line {

    override fun getID(): Long = id

    override fun getStartNode(): Node = mapDatabase.getNode(startNodeId)
        ?: throw IllegalStateException("Start node $startNodeId not found")

    override fun getEndNode(): Node = mapDatabase.getNode(endNodeId)
        ?: throw IllegalStateException("End node $endNodeId not found")

    override fun getLineLength(): Int = road.lengthMeters.toInt()

    override fun getFOW(): FormOfWay = road.fow

    override fun getFRC(): FunctionalRoadClass = road.frc

    override fun getShapeCoordinates(): List<GeoCoordinates> {
        return geometry.coordinates.map { coord ->
            GeoCoordinatesImpl(coord.x, coord.y)
        }
    }

    override fun getShape(): java.awt.geom.Path2D.Double {
        val path = java.awt.geom.Path2D.Double()
        val coords = geometry.coordinates

        if (coords.isNotEmpty()) {
            path.moveTo(coords[0].x, coords[0].y)
            for (i in 1 until coords.size) {
                path.lineTo(coords[i].x, coords[i].y)
            }
        }

        return path
    }

    override fun getGeoCoordinateAlongLine(distanceAlong: Int): GeoCoordinates? {
        if (distanceAlong < 0 || distanceAlong > lineLength) return null

        val totalLength = geometry.length
        val fraction = distanceAlong.toDouble() / road.lengthMeters
        val targetDistance = fraction * totalLength

        var accumulated = 0.0
        for (i in 0 until geometry.numPoints - 1) {
            val p1 = geometry.getCoordinateN(i)
            val p2 = geometry.getCoordinateN(i + 1)
            val segmentLength = Math.sqrt(
                Math.pow(p2.x - p1.x, 2.0) + Math.pow(p2.y - p1.y, 2.0)
            )

            if (accumulated + segmentLength >= targetDistance) {
                val segmentFraction = (targetDistance - accumulated) / segmentLength
                val x = p1.x + (p2.x - p1.x) * segmentFraction
                val y = p1.y + (p2.y - p1.y) * segmentFraction
                return GeoCoordinatesImpl(x, y)
            }
            accumulated += segmentLength
        }

        // Return last point if we've gone too far
        val lastCoord = geometry.getCoordinateN(geometry.numPoints - 1)
        return GeoCoordinatesImpl(lastCoord.x, lastCoord.y)
    }

    override fun getPointAlongLine(distanceAlong: Int): Point2D.Double? {
        val geoCoord = getGeoCoordinateAlongLine(distanceAlong) ?: return null
        return Point2D.Double(geoCoord.longitudeDeg, geoCoord.latitudeDeg)
    }

    override fun distanceToPoint(longitude: Double, latitude: Double): Int {
        val distance = geometry.coordinates.minOfOrNull { coord ->
            val dx = coord.x - longitude
            val dy = coord.y - latitude
            Math.sqrt(dx * dx + dy * dy)
        } ?: Double.MAX_VALUE
        return (distance * 111000).toInt() // Rough conversion to meters
    }

    override fun measureAlongLine(longitude: Double, latitude: Double): Int {
        // Find the closest point on the line and return the distance from start
        var minDistance = Double.MAX_VALUE
        var closestSegmentIndex = 0
        var closestPointOnSegment: org.locationtech.jts.geom.Coordinate? = null

        for (i in 0 until geometry.numPoints - 1) {
            val p1 = geometry.getCoordinateN(i)
            val p2 = geometry.getCoordinateN(i + 1)

            // Project point onto line segment
            val dx = p2.x - p1.x
            val dy = p2.y - p1.y
            val t = ((longitude - p1.x) * dx + (latitude - p1.y) * dy) / (dx * dx + dy * dy)
            val tClamped = Math.max(0.0, Math.min(1.0, t))

            val projX = p1.x + tClamped * dx
            val projY = p1.y + tClamped * dy

            val distToSegment = Math.sqrt(
                Math.pow(longitude - projX, 2.0) + Math.pow(latitude - projY, 2.0)
            )

            if (distToSegment < minDistance) {
                minDistance = distToSegment
                closestSegmentIndex = i
                closestPointOnSegment = org.locationtech.jts.geom.Coordinate(projX, projY)
            }
        }

        // Calculate distance along line from start to the closest point
        var distanceAlong = 0.0
        for (i in 0 until closestSegmentIndex) {
            val p1 = geometry.getCoordinateN(i)
            val p2 = geometry.getCoordinateN(i + 1)
            distanceAlong += Math.sqrt(
                Math.pow(p2.x - p1.x, 2.0) + Math.pow(p2.y - p1.y, 2.0)
            )
        }

        // Add distance within the closest segment
        if (closestPointOnSegment != null) {
            val p1 = geometry.getCoordinateN(closestSegmentIndex)
            distanceAlong += Math.sqrt(
                Math.pow(closestPointOnSegment.x - p1.x, 2.0) +
                Math.pow(closestPointOnSegment.y - p1.y, 2.0)
            )
        }

        // Convert geometric distance to meters (rough approximation)
        val totalLength = geometry.length
        val fraction = distanceAlong / totalLength
        return (fraction * road.lengthMeters).toInt()
    }

    override fun getNextLines(): Iterator<Line> {
        val connectedRoads = mapDatabase.mapDatabaseService.getRoadsConnectedToIntersection(endNodeId)
        val lines = connectedRoads
            .filter { it.id != Math.abs(id) }  // Exclude self
            .flatMap { road ->
                when (road.flowDirection) {
                    FlowDirection.BOTH_WAYS -> listOf(
                        mapDatabase.getLine(road.id),
                        mapDatabase.getLine(-road.id)
                    )
                    FlowDirection.START_TO_END -> listOf(mapDatabase.getLine(road.id))
                    FlowDirection.END_TO_START -> listOf(mapDatabase.getLine(-road.id))
                }
            }
            .filterNotNull()
            .filter { it.startNode.id == endNodeId }  // Only lines that start from our end node

        return lines.iterator()
    }

    override fun getPrevLines(): Iterator<Line> {
        val connectedRoads = mapDatabase.mapDatabaseService.getRoadsConnectedToIntersection(startNodeId)
        val lines = connectedRoads
            .filter { it.id != Math.abs(id) }  // Exclude self
            .flatMap { road ->
                when (road.flowDirection) {
                    FlowDirection.BOTH_WAYS -> listOf(
                        mapDatabase.getLine(road.id),
                        mapDatabase.getLine(-road.id)
                    )
                    FlowDirection.START_TO_END -> listOf(mapDatabase.getLine(road.id))
                    FlowDirection.END_TO_START -> listOf(mapDatabase.getLine(-road.id))
                }
            }
            .filterNotNull()
            .filter { it.endNode.id == startNodeId }  // Only lines that end at our start node

        return lines.iterator()
    }

    override fun getNames(): Map<Locale, List<String>> = emptyMap()

    override fun toString(): String = "Line(id=$id, frc=${road.frc}, fow=${road.fow}, length=${road.lengthMeters}m)"
}

/**
 * OpenLR Node implementation backed by our Intersection model.
 */
private class NodeAdapter(
    private val id: Long,
    private val longitude: Double,
    private val latitude: Double,
    private val mapDatabase: OpenLrMapDatabaseAdapter
) : Node {

    override fun getID(): Long = id

    override fun getLongitudeDeg(): Double = longitude

    override fun getLatitudeDeg(): Double = latitude

    override fun getGeoCoordinates(): GeoCoordinates = GeoCoordinatesImpl(longitude, latitude)

    override fun getConnectedLines(): Iterator<Line> {
        // This is expensive, so only call when needed
        throw UnsupportedOperationException("Use getOutgoingLines() or getIncomingLines() instead")
    }

    override fun getOutgoingLines(): Iterator<Line> {
        val roads = mapDatabase.mapDatabaseService.getRoadsConnectedToIntersection(id)
        val lines = roads.flatMap { road ->
            when (road.flowDirection) {
                FlowDirection.BOTH_WAYS -> {
                    if (road.startNodeId == id) {
                        listOf(mapDatabase.getLine(road.id))
                    } else {
                        listOf(mapDatabase.getLine(-road.id))
                    }
                }
                FlowDirection.START_TO_END -> {
                    if (road.startNodeId == id) listOf(mapDatabase.getLine(road.id)) else emptyList()
                }
                FlowDirection.END_TO_START -> {
                    if (road.endNodeId == id) listOf(mapDatabase.getLine(-road.id)) else emptyList()
                }
            }
        }.filterNotNull()

        return lines.iterator()
    }

    override fun getIncomingLines(): Iterator<Line> {
        val roads = mapDatabase.mapDatabaseService.getRoadsConnectedToIntersection(id)
        val lines = roads.flatMap { road ->
            when (road.flowDirection) {
                FlowDirection.BOTH_WAYS -> {
                    if (road.endNodeId == id) {
                        listOf(mapDatabase.getLine(road.id))
                    } else {
                        listOf(mapDatabase.getLine(-road.id))
                    }
                }
                FlowDirection.START_TO_END -> {
                    if (road.endNodeId == id) listOf(mapDatabase.getLine(road.id)) else emptyList()
                }
                FlowDirection.END_TO_START -> {
                    if (road.startNodeId == id) listOf(mapDatabase.getLine(-road.id)) else emptyList()
                }
            }
        }.filterNotNull()

        return lines.iterator()
    }

    override fun getNumberConnectedLines(): Int {
        val roads = mapDatabase.mapDatabaseService.getRoadsConnectedToIntersection(id)
        return roads.sumOf { road: Road ->
            when (road.flowDirection) {
                FlowDirection.BOTH_WAYS -> 2
                else -> 1
            }.toInt()
        }
    }

    override fun toString(): String = "Node(id=$id, lon=$longitude, lat=$latitude)"
}
