package com.tomtom.openlr.tool.service

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Metric geometry for WGS84 lon/lat coordinates.
 *
 * The OpenLR map layer must answer questions in metres: how far a candidate line is
 * from a location reference point, and how far along a line a point lies. Doing that
 * arithmetic directly on degrees is wrong in two ways, and both used to be present:
 *
 *  - A degree of longitude is shorter than a degree of latitude by a factor of
 *    `cos(latitude)` — 0.63 at Dutch latitudes — so Euclidean distance in degree
 *    space is anisotropic, and east-west offsets were understated by a third.
 *  - Converting with a flat `× 111000` ignores that factor entirely.
 *
 * Everything here works in a local tangent plane centred on the geometry, where one
 * unit is one metre in both axes. Over the length of a road segment the error is
 * far below the metre resolution OpenLR works at.
 */
object GeoMath {

    /**
     * Metres per degree of latitude at [latDeg] (WGS84 series expansion, cm-accurate).
     */
    fun metresPerDegreeLatitude(latDeg: Double): Double {
        val phi = Math.toRadians(latDeg)
        return 111132.92 - 559.82 * cos(2 * phi) + 1.175 * cos(4 * phi) -
            0.0023 * cos(6 * phi)
    }

    /** Metres per degree of longitude at [latDeg]. Shrinks to zero at the poles. */
    fun metresPerDegreeLongitude(latDeg: Double): Double {
        val phi = Math.toRadians(latDeg)
        return 111412.84 * cos(phi) - 93.5 * cos(3 * phi) + 0.118 * cos(5 * phi)
    }

    /** Great-circle distance in metres. */
    fun distanceMetres(lon1: Double, lat1: Double, lon2: Double, lat2: Double): Double {
        val latScale = metresPerDegreeLatitude((lat1 + lat2) / 2.0)
        val lonScale = metresPerDegreeLongitude((lat1 + lat2) / 2.0)
        val dx = (lon2 - lon1) * lonScale
        val dy = (lat2 - lat1) * latScale
        return sqrt(dx * dx + dy * dy)
    }

    /**
     * Perpendicular distance in metres from a point to the segment `a`-`b`.
     *
     * The previous implementation took the distance to the nearest *vertex*, so a
     * point 5 m off the middle of a 200 m two-vertex segment measured as ~100 m away.
     * That figure feeds candidate rating and `MaxNodeDistance`.
     */
    fun distanceToSegmentMetres(
        lon: Double, lat: Double,
        aLon: Double, aLat: Double,
        bLon: Double, bLat: Double
    ): Double {
        val latScale = metresPerDegreeLatitude(lat)
        val lonScale = metresPerDegreeLongitude(lat)

        val px = (lon - aLon) * lonScale
        val py = (lat - aLat) * latScale
        val bx = (bLon - aLon) * lonScale
        val by = (bLat - aLat) * latScale

        val lengthSquared = bx * bx + by * by
        if (lengthSquared == 0.0) return sqrt(px * px + py * py)

        // Projection parameter, clamped to the segment so endpoints behave correctly.
        val t = max(0.0, min(1.0, (px * bx + py * by) / lengthSquared))
        val dx = px - t * bx
        val dy = py - t * by
        return sqrt(dx * dx + dy * dy)
    }

    /**
     * Cumulative metric length at each vertex of [coordinates], starting at 0.
     *
     * The returned list has one more entry than there are segments; the last element
     * is the total length.
     */
    fun cumulativeLengths(coordinates: List<Pair<Double, Double>>): List<Double> {
        val cumulative = ArrayList<Double>(coordinates.size)
        cumulative.add(0.0)
        var total = 0.0
        for (i in 0 until coordinates.size - 1) {
            val (lon1, lat1) = coordinates[i]
            val (lon2, lat2) = coordinates[i + 1]
            total += distanceMetres(lon1, lat1, lon2, lat2)
            cumulative.add(total)
        }
        return cumulative
    }

    /**
     * Distance in metres from the start of the polyline to the point on it closest
     * to (`lon`, `lat`), and the perpendicular distance to that point.
     */
    fun projectOntoPolyline(
        coordinates: List<Pair<Double, Double>>,
        lon: Double,
        lat: Double
    ): Projection {
        if (coordinates.size < 2) return Projection(0.0, 0.0)

        val cumulative = cumulativeLengths(coordinates)
        var best = Projection(0.0, Double.MAX_VALUE)

        for (i in 0 until coordinates.size - 1) {
            val (aLon, aLat) = coordinates[i]
            val (bLon, bLat) = coordinates[i + 1]
            val distance = distanceToSegmentMetres(lon, lat, aLon, aLat, bLon, bLat)
            if (distance >= best.distanceMetres) continue

            val latScale = metresPerDegreeLatitude(lat)
            val lonScale = metresPerDegreeLongitude(lat)
            val bx = (bLon - aLon) * lonScale
            val by = (bLat - aLat) * latScale
            val px = (lon - aLon) * lonScale
            val py = (lat - aLat) * latScale
            val lengthSquared = bx * bx + by * by
            val t = if (lengthSquared == 0.0) 0.0
                    else max(0.0, min(1.0, (px * bx + py * by) / lengthSquared))
            val segmentLength = cumulative[i + 1] - cumulative[i]
            best = Projection(cumulative[i] + t * segmentLength, distance)
        }
        return best
    }

    /**
     * The coordinate [distanceAlongMetres] from the start of the polyline.
     *
     * `totalLengthMetres` is the authoritative length from the map database, which is
     * ellipsoidal; the metric lengths computed here are used only as ratios, so that
     * authoritative figure sets the scale.
     */
    fun interpolate(
        coordinates: List<Pair<Double, Double>>,
        distanceAlongMetres: Double,
        totalLengthMetres: Double
    ): Pair<Double, Double>? {
        if (coordinates.isEmpty()) return null
        if (coordinates.size == 1) return coordinates[0]

        val cumulative = cumulativeLengths(coordinates)
        val metricTotal = cumulative.last()
        if (metricTotal <= 0.0 || totalLengthMetres <= 0.0) return coordinates[0]

        val target = (distanceAlongMetres / totalLengthMetres) * metricTotal
        if (target <= 0.0) return coordinates.first()
        if (target >= metricTotal) return coordinates.last()

        for (i in 0 until coordinates.size - 1) {
            if (cumulative[i + 1] < target) continue
            val segmentLength = cumulative[i + 1] - cumulative[i]
            val fraction = if (segmentLength == 0.0) 0.0
                           else (target - cumulative[i]) / segmentLength
            val (aLon, aLat) = coordinates[i]
            val (bLon, bLat) = coordinates[i + 1]
            return Pair(aLon + (bLon - aLon) * fraction, aLat + (bLat - aLat) * fraction)
        }
        return coordinates.last()
    }

    /** Result of projecting a point onto a polyline. */
    data class Projection(val alongMetres: Double, val distanceMetres: Double)

    /** True when two metre figures agree to within [toleranceMetres]. */
    fun closeEnough(a: Double, b: Double, toleranceMetres: Double): Boolean =
        abs(a - b) <= toleranceMetres
}
