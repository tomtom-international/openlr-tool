package com.tomtom.openlr.tool.service

import com.tomtom.openlr.tool.model.FlowDirection
import com.tomtom.openlr.tool.model.Intersection
import com.tomtom.openlr.tool.model.Road
import openlr.map.FormOfWay
import openlr.map.FunctionalRoadClass
import org.locationtech.jts.geom.LineString
import org.locationtech.jts.io.WKBReader
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service

/**
 * Service for accessing road network data from PostgreSQL/PostGIS database.
 *
 * Provides methods to query roads, intersections, and their spatial relationships
 * for OpenLR encoding and decoding operations.
 */
@Service
class MapDatabaseService(
    private val jdbcTemplate: JdbcTemplate,
    @Value("\${db_schema}") private val schema: String,
    @Value("\${roads_table}") private val roadsTable: String,
    @Value("\${intersections_table}") private val intersectionsTable: String,
    @Value("\${cache_size}") cacheSize: Int
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val wkbReader = WKBReader()

    // Bounded so that decoding across a large network cannot grow the heap without
    // limit. `cache_size` is the bound, not an initial capacity.
    private val roadCache = LruCache<Long, Road>(cacheSize)
    private val intersectionCache = LruCache<Long, Intersection>(cacheSize)

    /**
     * Find roads within a given distance of a point.
     * Uses PostGIS spatial index for efficient querying.
     */
    fun findRoadsNear(longitude: Double, latitude: Double, distanceMeters: Int): List<Road> {
        val sql = """
            SELECT
                r.id, r.meta, r.frc, r.fow, r.flowdir,
                r.from_int, r.to_int, r.len, r.geom,
                ST_X(j1.geom) as start_lon, ST_Y(j1.geom) as start_lat, j1.meta as start_meta,
                ST_X(j2.geom) as end_lon, ST_Y(j2.geom) as end_lat, j2.meta as end_meta,
                ST_DistanceSpheroid(
                    ST_SetSRID(r.geom, 4326),
                    ST_GeomFromText('POINT($longitude $latitude)', 4326),
                    'SPHEROID["WGS 84",6378137,298.257223563]'
                ) as distance
            FROM $schema.$roadsTable r
            JOIN $schema.$intersectionsTable j1 ON r.from_int = j1.id
            JOIN $schema.$intersectionsTable j2 ON r.to_int = j2.id
            WHERE r.geom && ST_Buffer(ST_GeographyFromText('SRID=4326;POINT($longitude $latitude)'), $distanceMeters)::geometry
            ORDER BY distance ASC
        """.trimIndent()

        val roads = jdbcTemplate.query(sql) { rs, _ ->
            val road = parseRoad(rs)
            // Cache intersections while we're at it
            cacheIntersectionFromResultSet(rs, "from_int", "start_lon", "start_lat", "start_meta")
            cacheIntersectionFromResultSet(rs, "to_int", "end_lon", "end_lat", "end_meta")
            road
        }

        logger.debug("Found {} roads within {}m of ({}, {})", roads.size, distanceMeters, longitude, latitude)
        return roads
    }

    /**
     * Get a specific road by ID.
     */
    fun getRoad(id: Long): Road? {
        // The fetch happens outside any cache lock -- see LruCache.
        return roadCache.get(id) ?: fetchRoad(id)?.also { roadCache.put(id, it) }
    }

    /**
     * Get a specific intersection by ID.
     */
    fun getIntersection(id: Long): Intersection? {
        return intersectionCache.get(id) ?: fetchIntersection(id)?.also { intersectionCache.put(id, it) }
    }

    /**
     * Get roads by metadata field (external ID).
     */
    fun getRoadsByMeta(meta: String): List<Road> {
        val sql = """
            SELECT r.id, r.meta, r.frc, r.fow, r.flowdir,
                   r.from_int, r.to_int, r.len, r.geom
            FROM $schema.$roadsTable r
            WHERE r.meta = ?
        """.trimIndent()

        return jdbcTemplate.query(sql, { rs, _ -> parseRoad(rs) }, meta)
    }

    /**
     * Get all roads connected to a given intersection.
     */
    fun getRoadsConnectedToIntersection(intersectionId: Long): List<Road> {
        val sql = """
            SELECT r.id, r.meta, r.frc, r.fow, r.flowdir,
                   r.from_int, r.to_int, r.len, r.geom
            FROM $schema.$roadsTable r
            WHERE r.from_int = ? OR r.to_int = ?
        """.trimIndent()

        return jdbcTemplate.query(sql, { rs, _ -> parseRoad(rs) }, intersectionId, intersectionId)
    }

    /**
     * Get total number of roads in database.
     */
    fun getRoadCount(): Int {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM $schema.$roadsTable",
            Int::class.java
        ) ?: 0
    }

    /**
     * Number of OpenLR lines in the network.
     *
     * Not the same as the road count: a two-way road becomes two lines, one per
     * direction (§7.5). `getNumberOfLines()` previously returned the row count, which
     * under-reports by up to half.
     */
    fun getLineCount(): Int {
        return jdbcTemplate.queryForObject(
            "SELECT COALESCE(SUM(CASE WHEN flowdir = 1 THEN 2 ELSE 1 END), 0) " +
                "FROM $schema.$roadsTable",
            Int::class.java
        ) ?: 0
    }

    /**
     * Get total number of intersections in database.
     */
    fun getIntersectionCount(): Int {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM $schema.$intersectionsTable",
            Int::class.java
        ) ?: 0
    }

    /**
     * Clear all caches.
     */
    fun clearCaches() {
        roadCache.clear()
        intersectionCache.clear()
        logger.info("Caches cleared")
    }

    /** Occupancy and hit rates, so cache behaviour is observable. */
    fun cacheStats(): Map<String, LruCache.Stats> = mapOf(
        "roads" to roadCache.stats(),
        "intersections" to intersectionCache.stats()
    )

    // Private helper methods

    private fun fetchRoad(id: Long): Road? {
        val sql = """
            SELECT r.id, r.meta, r.frc, r.fow, r.flowdir,
                   r.from_int, r.to_int, r.len, r.geom
            FROM $schema.$roadsTable r
            WHERE r.id = ?
        """.trimIndent()

        return jdbcTemplate.query(sql, { rs, _ -> parseRoad(rs) }, id).firstOrNull()
    }

    private fun fetchIntersection(id: Long): Intersection? {
        val sql = """
            SELECT id, meta, ST_X(geom) as lon, ST_Y(geom) as lat
            FROM $schema.$intersectionsTable
            WHERE id = ?
        """.trimIndent()

        return jdbcTemplate.query(sql, { rs, _ ->
            Intersection(
                id = rs.getLong("id"),
                meta = rs.getString("meta"),
                longitude = rs.getDouble("lon"),
                latitude = rs.getDouble("lat")
            )
        }, id).firstOrNull()
    }

    private fun parseRoad(rs: java.sql.ResultSet): Road {
        val geomBytes = rs.getBytes("geom")
        val geometry = parseGeometry(geomBytes)

        return Road(
            id = rs.getLong("id"),
            meta = rs.getString("meta"),
            frc = mapFunctionalRoadClass(rs.getInt("frc")),
            fow = mapFormOfWay(rs.getInt("fow")),
            flowDirection = mapFlowDirection(rs.getInt("flowdir"), rs.getLong("id")),
            startNodeId = rs.getLong("from_int"),
            endNodeId = rs.getLong("to_int"),
            lengthMeters = rs.getDouble("len"),
            geometry = geometry
        )
    }

    private fun parseGeometry(bytes: ByteArray): LineString {
        // PostgreSQL returns WKB in hex format
        val wkbHex = String(bytes)
        val wkbBytes = WKBReader.hexToBytes(wkbHex)
        val geom = wkbReader.read(wkbBytes)

        return when (geom) {
            is LineString -> geom
            else -> {
                // Handle MultiLineString by taking first geometry
                if (geom.geometryType == "MultiLineString" && geom.numGeometries > 0) {
                    geom.getGeometryN(0) as LineString
                } else {
                    throw IllegalArgumentException("Unexpected geometry type: ${geom.geometryType}")
                }
            }
        }
    }

    /**
     * Opportunistically cache an intersection seen in a road query result.
     *
     * `meta` is selected by the query so the cached entry is complete. It used to be
     * stored as null, and because the cache is checked before the database, a later
     * [getIntersection] then returned a null `meta` for the rest of the process
     * lifetime.
     */
    private fun cacheIntersectionFromResultSet(
        rs: java.sql.ResultSet,
        idColumn: String,
        lonColumn: String,
        latColumn: String,
        metaColumn: String
    ) {
        val id = rs.getLong(idColumn)
        if (intersectionCache.get(id) == null) {
            val intersection = Intersection(
                id = id,
                meta = rs.getString(metaColumn),
                longitude = rs.getDouble(lonColumn),
                latitude = rs.getDouble(latColumn)
            )
            intersectionCache.put(id, intersection)
        }
    }

    /**
     * Map the `fow` column to an OpenLR form of way.
     *
     * The values are the ordinals of `openlr.map.FormOfWay`, so 7 is OTHER -- a
     * distinct OpenLR value, not a synonym for UNDEFINED. Orbis-derived networks
     * use it heavily (roughly a third of segments in a Netherlands extract:
     * service roads, parking areas, pedestrian ways), and folding it into
     * UNDEFINED costs the decoder the FOW rating for all of them.
     */
    private fun mapFormOfWay(value: Int): FormOfWay = when (value) {
        0 -> FormOfWay.UNDEFINED
        1 -> FormOfWay.MOTORWAY
        2 -> FormOfWay.MULTIPLE_CARRIAGEWAY
        3 -> FormOfWay.SINGLE_CARRIAGEWAY
        4 -> FormOfWay.ROUNDABOUT
        5 -> FormOfWay.TRAFFIC_SQUARE
        6 -> FormOfWay.SLIPROAD
        7 -> FormOfWay.OTHER
        else -> FormOfWay.UNDEFINED
    }

    private fun mapFunctionalRoadClass(value: Int): FunctionalRoadClass = when (value) {
        0 -> FunctionalRoadClass.FRC_0
        1 -> FunctionalRoadClass.FRC_1
        2 -> FunctionalRoadClass.FRC_2
        3 -> FunctionalRoadClass.FRC_3
        4 -> FunctionalRoadClass.FRC_4
        5 -> FunctionalRoadClass.FRC_5
        6 -> FunctionalRoadClass.FRC_6
        else -> FunctionalRoadClass.FRC_7
    }

    /**
     * Read a `flowdir` value, tolerating data that predates the CHECK constraint.
     *
     * Only 1, 2 and 3 are defined ([FlowDirection.fromDbValue]). An undefined value is
     * bad data rather than a two-way marker, so it is logged; the fallback keeps the
     * road usable instead of failing the query, at the cost of allowing traversal in
     * both directions on a segment that may in fact be one-way.
     */
    private fun mapFlowDirection(value: Int, roadId: Long): FlowDirection =
        FlowDirection.fromDbValue(value) ?: run {
            logger.warn(
                "Road {} has undefined flowdir {} (expected 1=two-way, 2=one-way end to start, " +
                    "3=one-way start to end); treating as two-way",
                roadId, value
            )
            FlowDirection.BOTH_WAYS
        }
}
