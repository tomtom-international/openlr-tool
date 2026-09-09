package com.tomtom.openlr.tool.service

import com.tomtom.openlr.tool.model.FlowDirection
import com.tomtom.openlr.tool.model.Road
import openlr.map.*
import org.locationtech.jts.geom.LineString
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.awt.geom.Point2D
import java.util.*

/**
 * Adapter that bridges MapDatabaseService to OpenLR library's MapDatabase interface.
 *
 * This adapter wraps our simplified MapDatabaseService and presents it as the
 * MapDatabase interface expected by the OpenLR encoder/decoder libraries.
 */
@Component
class OpenLrMapDatabaseAdapter(
    val mapDatabaseService: MapDatabaseService,
    @Value("\${cache_size}") cacheSize: Int
) : MapDatabase {

    // Bounded, like the road and intersection caches. A two-way road yields two
    // lines, so the line cache is given headroom over the configured figure.
    private val lineCache = LruCache<Long, Line>(cacheSize * 2)
    private val nodeCache = LruCache<Long, Node>(cacheSize)

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
        return lineCache.get(id) ?: run {
            val road = mapDatabaseService.getRoad(Math.abs(id)) ?: return null
            val lines = convertRoadToLines(road)
            // Cache all generated lines
            lines.forEach { lineCache.put(it.id, it) }
            lines.firstOrNull { it.id == id }
        }
    }

    override fun getNode(id: Long): Node? {
        return nodeCache.get(id) ?: run {
            val intersection = mapDatabaseService.getIntersection(id) ?: return null
            val node = NodeAdapter(
                id = intersection.id,
                longitude = intersection.longitude,
                latitude = intersection.latitude,
                mapDatabase = this
            )
            nodeCache.put(id, node)
            node
        }
    }

    override fun getNumberOfLines(): Int {
        // Lines, not rows: a two-way road is two lines.
        return mapDatabaseService.getLineCount()
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

    /** Occupancy and hit rates for every cache in the map layer. */
    fun cacheStats(): Map<String, LruCache.Stats> =
        mapOf("lines" to lineCache.stats(), "nodes" to nodeCache.stats()) +
            mapDatabaseService.cacheStats()

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

    /** Lon/lat pairs for [GeoMath]. */
    private fun lonLat(): List<Pair<Double, Double>> =
        geometry.coordinates.map { Pair(it.x, it.y) }

    override fun getGeoCoordinateAlongLine(distanceAlong: Int): GeoCoordinates? {
        if (distanceAlong < 0 || distanceAlong > lineLength) return null
        val point = GeoMath.interpolate(lonLat(), distanceAlong.toDouble(), road.lengthMeters)
            ?: return null
        return GeoCoordinatesImpl(point.first, point.second)
    }

    override fun getPointAlongLine(distanceAlong: Int): Point2D.Double? {
        val geoCoord = getGeoCoordinateAlongLine(distanceAlong) ?: return null
        return Point2D.Double(geoCoord.longitudeDeg, geoCoord.latitudeDeg)
    }

    /**
     * Perpendicular distance in metres from the point to this line.
     *
     * Feeds candidate rating and `MaxNodeDistance`, so it has to be the distance to
     * the line and not to its nearest vertex: on a 200 m segment with only endpoints,
     * a point 5 m off the middle used to measure ~100 m away.
     */
    override fun distanceToPoint(longitude: Double, latitude: Double): Int {
        val coords = lonLat()
        if (coords.isEmpty()) return Int.MAX_VALUE
        if (coords.size == 1) {
            return GeoMath.distanceMetres(longitude, latitude, coords[0].first, coords[0].second)
                .toInt()
        }
        return GeoMath.projectOntoPolyline(coords, longitude, latitude).distanceMetres.toInt()
    }

    /**
     * Distance in metres from the start of this line to the point on it closest to
     * the given coordinate.
     *
     * Scaled by the map database's own length: `len` is authoritative and
     * ellipsoidal, so the tangent-plane figures are used only as a ratio.
     */
    override fun measureAlongLine(longitude: Double, latitude: Double): Int {
        val coords = lonLat()
        if (coords.size < 2) return 0

        val metricTotal = GeoMath.cumulativeLengths(coords).last()
        if (metricTotal <= 0.0) return 0

        val along = GeoMath.projectOntoPolyline(coords, longitude, latitude).alongMetres
        return ((along / metricTotal) * road.lengthMeters).toInt()
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
        // The OpenLR *encoder* calls this, so it cannot throw: doing so failed most
        // /api/v1/encode requests with "Use getOutgoingLines() or getIncomingLines()
        // instead". Outgoing plus incoming is the full set of lines touching this
        // node -- a two-way road contributes one of each, matching
        // getNumberConnectedLines().
        return (getOutgoingLines().asSequence() + getIncomingLines().asSequence()).iterator()
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
