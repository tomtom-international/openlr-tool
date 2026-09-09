package com.tomtom.openlr.tool.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * Reference values come from `pyproj.Geod(ellps="WGS84")`, an independent
 * implementation, rather than from this code's own arithmetic. Tolerances are
 * physical: [GeoMath] uses a local tangent plane, so it is expected to differ from
 * a true geodesic by a small fraction of a percent over a road segment, and the
 * assertions say so explicitly rather than being widened until they pass.
 */
class GeoMathTest {

    private fun assertClose(expected: Double, actual: Double, relative: Double, floor: Double) {
        val tolerance = maxOf(abs(expected) * relative, floor)
        assertTrue(
            abs(expected - actual) <= tolerance,
            "expected $expected, got $actual (tolerance ±$tolerance)"
        )
    }

    // ---- scale factors -----------------------------------------------------

    @Test
    fun `metres per degree of latitude matches pyproj`() {
        assertClose(110574.30, GeoMath.metresPerDegreeLatitude(0.0), 1e-5, 2.0)
        assertClose(110772.90, GeoMath.metresPerDegreeLatitude(25.0), 1e-5, 2.0)
        assertClose(111274.37, GeoMath.metresPerDegreeLatitude(52.37), 1e-5, 2.0)
        assertClose(111412.27, GeoMath.metresPerDegreeLatitude(60.0), 1e-5, 2.0)
    }

    @Test
    fun `metres per degree of longitude matches pyproj`() {
        assertClose(111319.49, GeoMath.metresPerDegreeLongitude(0.0), 1e-5, 2.0)
        assertClose(100949.86, GeoMath.metresPerDegreeLongitude(25.0), 1e-5, 2.0)
        assertClose(68109.82, GeoMath.metresPerDegreeLongitude(52.37), 1e-5, 2.0)
        assertClose(55799.47, GeoMath.metresPerDegreeLongitude(60.0), 1e-5, 2.0)
    }

    @Test
    fun `longitude scale shrinks with latitude, latitude scale barely moves`() {
        // The whole point: these are not interchangeable, and a flat 111000 is wrong.
        assertTrue(GeoMath.metresPerDegreeLongitude(52.37) < 0.65 * GeoMath.metresPerDegreeLatitude(52.37))
        assertTrue(GeoMath.metresPerDegreeLongitude(0.0) > 111000.0)
    }

    // ---- point to point ----------------------------------------------------

    @Test
    fun `distance matches pyproj for short east-west, north-south and diagonal spans`() {
        assertClose(68.1104, GeoMath.distanceMetres(4.9, 52.37, 4.901, 52.37), 2e-3, 0.05)
        assertClose(111.2744, GeoMath.distanceMetres(4.9, 52.37, 4.9, 52.371), 2e-3, 0.05)
        assertClose(130.4642, GeoMath.distanceMetres(4.9, 52.37, 4.901, 52.371), 2e-3, 0.05)
    }

    @Test
    fun `distance matches pyproj at the equator and over a kilometres-long segment`() {
        assertClose(1113.1949, GeoMath.distanceMetres(0.0, 0.0, 0.01, 0.0), 2e-3, 0.5)
        assertClose(2357.9988,
            GeoMath.distanceMetres(167.9877291, -46.3042884, 167.9964283, -46.2839503),
            5e-3, 1.0)
    }

    // ---- perpendicular distance -------------------------------------------

    // A ~200 m east-west segment at 52.37 N, and a point 5 m due north of its
    // midpoint. Endpoints and the point are pyproj forward-solutions.
    private val segStartLon = 4.9000000
    private val segStartLat = 52.3700000
    private val segEndLon = 4.9029364
    private val segEndLat = 52.3700000
    private val offLon = 4.9014682
    private val offLat = 52.3700449

    @Test
    fun `perpendicular distance to a segment is the perpendicular, not the nearest vertex`() {
        val d = GeoMath.distanceToSegmentMetres(
            offLon, offLat, segStartLon, segStartLat, segEndLon, segEndLat
        )
        assertClose(5.0, d, 0.0, 0.15)
        // Regression guard: the previous implementation returned the vertex distance,
        // which pyproj puts at 100.12 m for this geometry.
        assertTrue(d < 20.0, "got $d m; a vertex-distance implementation returns ~100 m")
    }

    @Test
    fun `a point beyond the segment end clamps to the endpoint`() {
        // 50 m east of the eastern endpoint.
        val beyondLon = segEndLon + 50.0 / GeoMath.metresPerDegreeLongitude(segEndLat)
        val d = GeoMath.distanceToSegmentMetres(
            beyondLon, segEndLat, segStartLon, segStartLat, segEndLon, segEndLat
        )
        assertClose(50.0, d, 0.0, 0.5)
    }

    @Test
    fun `a degenerate segment falls back to point distance`() {
        val d = GeoMath.distanceToSegmentMetres(
            4.901, 52.37, 4.9, 52.37, 4.9, 52.37
        )
        assertClose(68.1104, d, 2e-3, 0.05)
    }

    // ---- polyline ----------------------------------------------------------

    private val polyline = listOf(
        Pair(4.9000000, 52.3700000),
        Pair(4.9029364, 52.3700000),   // 200 m east
        Pair(4.9029364, 52.3717983)    // then 200 m north
    )

    @Test
    fun `cumulative lengths accumulate per vertex`() {
        val c = GeoMath.cumulativeLengths(polyline)
        assertEquals(3, c.size)
        assertEquals(0.0, c[0])
        assertClose(200.0, c[1], 0.0, 1.0)
        assertClose(400.0, c[2], 0.0, 1.0)
    }

    @Test
    fun `projecting onto a polyline gives distance along and offset`() {
        val p = GeoMath.projectOntoPolyline(polyline, offLon, offLat)
        assertClose(100.0, p.alongMetres, 0.0, 1.0)
        assertClose(5.0, p.distanceMetres, 0.0, 0.15)
    }

    @Test
    fun `projecting a point near the far end measures along the whole first leg`() {
        val p = GeoMath.projectOntoPolyline(polyline, 4.9029364, 52.3709000)
        assertClose(200.0 + 100.09, p.alongMetres, 0.0, 2.0)
        assertTrue(p.distanceMetres < 1.0, "point is on the line; got ${p.distanceMetres}")
    }

    @Test
    fun `interpolate walks the polyline and scales to the authoritative length`() {
        // Ask for the halfway point using a database length of 400 m.
        val mid = GeoMath.interpolate(polyline, 200.0, 400.0)!!
        assertClose(segEndLon, mid.first, 0.0, 1e-5)
        assertClose(segEndLat, mid.second, 0.0, 1e-5)
    }

    @Test
    fun `interpolate clamps to the ends`() {
        assertEquals(polyline.first(), GeoMath.interpolate(polyline, -10.0, 400.0))
        assertEquals(polyline.last(), GeoMath.interpolate(polyline, 999.0, 400.0))
    }

    @Test
    fun `interpolate honours the database length rather than the metric length`() {
        // If the map says the line is 800 m, then 400 m along is its midpoint even
        // though the geometry measures 400 m.
        val mid = GeoMath.interpolate(polyline, 400.0, 800.0)!!
        assertClose(segEndLon, mid.first, 0.0, 1e-5)
    }

    @Test
    fun `empty and single-point geometries do not throw`() {
        assertEquals(null, GeoMath.interpolate(emptyList(), 10.0, 100.0))
        val single = listOf(Pair(4.9, 52.37))
        assertEquals(single[0], GeoMath.interpolate(single, 10.0, 100.0))
        assertEquals(0.0, GeoMath.projectOntoPolyline(single, 4.9, 52.37).alongMetres)
    }
}
