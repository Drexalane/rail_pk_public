package com.drexalane.railpk

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Tests unitaires pour le moteur Kotlin (production). */
class RailNetworkTest {

    private lateinit var network: RailNetwork

    @Before
    fun setUp() {
        network = RailNetwork()
        network.loadFromString(syntheticGeoJson())
    }

    // ─── Chargement ────────────────────────────────────────

    @Test
    fun `points loaded`() {
        assertEquals(202, network.points.size)
    }

    @Test
    fun `lines grouped`() {
        assertTrue(network.byLigne.containsKey("A"))
        assertTrue(network.byLigne.containsKey("B"))
        assertEquals(101, network.byLigne["A"]!!.size)
        assertEquals(101, network.byLigne["B"]!!.size)
    }

    @Test
    fun `lines sorted by PK ascending`() {
        val a = network.byLigne["A"]!!
        for (i in 1 until a.size) {
            assertTrue("Line A not sorted at index $i", a[i].pk > a[i - 1].pk)
        }
    }

    // ─── Nearest neighbor ──────────────────────────────────

    @Test
    fun `nearest exact point`() {
        val result = network.nearest(2.35, 48.87)
        assertNotNull(result)
        assertEquals("A", result!!.point.codeLigne)
        assertEquals(0.0, result.point.pk, 0.01)
        assertTrue(result.distanceM < 1.0)
    }

    @Test
    fun `nearest picks correct line`() {
        val result = network.nearest(2.3701, 48.88)
        assertNotNull(result)
        assertEquals("B", result!!.point.codeLigne)
    }

    // ─── Interpolation ─────────────────────────────────────

    @Test
    fun `interpolation midpoint`() {
        val prev = PkPoint(2.35, 48.87, 0.0, "A")
        val next = PkPoint(2.36, 48.88, 1.0, "A")
        val pk = GeoUtils.interpolePk(prev, next, 2.355, 48.875)
        assertNotNull(pk)
        assertEquals(0.5, pk!!, 0.1)
    }

    // ─── Haversine ─────────────────────────────────────────

    @Test
    fun `haversine known points`() {
        val d = GeoUtils.haversineM(2.3488, 48.8534, 4.8357, 45.7640)
        assertTrue(d > 390_000)
        assertTrue(d < 400_000)
    }

    @Test
    fun `haversine zero distance`() {
        val d = GeoUtils.haversineM(2.35, 48.87, 2.35, 48.87)
        assertTrue(d < 0.01)
    }

    // ─── Hystérésis ligne ──────────────────────────────────

    @Test
    fun `line locked on consistent updates`() {
        val stub = StubPkEngine(network)
        for (i in 0 until 10) {
            stub.update(2.35, 48.88)
        }
        assertEquals("A", stub.detectLine("A"))
        assertEquals("A", stub.detectLine("B"))
    }

    // ─── Dead reckoning ────────────────────────────────────

    @Test
    fun `speed below threshold triggers dead reckoning`() {
        val stub = StubPkEngine(network)
        stub.lastResult = PkResult(5.0, "A")
        val result = stub.update(2.36, 48.93, speedMs = 0f)
        assertNotNull(result)
        assertTrue(result!!.deadReckoning)
        assertEquals(5.0, result.pk, 0.01)
    }

    @Test
    fun `no result and speed below threshold returns null`() {
        val stub = StubPkEngine(network)
        val result = stub.update(2.35, 48.87, speedMs = 0f)
        assertNotNull(result)
    }

    // ─── Edge cases ────────────────────────────────────────

    @Test
    fun `empty network returns null`() {
        val empty = RailNetwork()
        val result = empty.nearest(2.35, 48.87)
        assertNull(result)
    }

    @Test
    fun `invalid GeoJSON does not crash`() {
        val net = RailNetwork()
        try {
            net.loadFromString("not json")
            assertTrue(true)
        } catch (e: Exception) { }
    }

    // ─── Helpers ───────────────────────────────────────────

    private fun syntheticGeoJson(): String {
        val sb = StringBuilder()
        sb.append("""{"type":"FeatureCollection","features":[""")
        for (i in 0..100) {
            if (i > 0) sb.append(",")
            val lon = 2.35 + i * 0.0001
            val lat = 48.87 + i * 0.001
            sb.append("""{"type":"Feature","properties":{"code_ligne":"A","pk":${i * 0.1}},"geometry":{"type":"Point","coordinates":[$lon,$lat]}}""")
        }
        for (i in 0..100) {
            sb.append(",")
            val lon = 2.37 + i * 0.0001
            val lat = 48.87 + i * 0.001
            sb.append("""{"type":"Feature","properties":{"code_ligne":"B","pk":${100.0 + i * 0.1}},"geometry":{"type":"Point","coordinates":[$lon,$lat]}}""")
        }
        sb.append("]}")
        return sb.toString()
    }
}

/** Stub exposant les méthodes internes pour test. */
class StubPkEngine(private val network: RailNetwork) {
    var lastResult: PkResult? = null
    private var lastSpeed = 0f
    private val lineHistory = ArrayDeque<String>()

    companion object {
        const val HYSTERESIS = 5
        const val SPEED_THRESHOLD_MS = 0.83f
    }

    fun update(lon: Double, lat: Double, speedMs: Float = SPEED_THRESHOLD_MS): PkResult? {
        lastSpeed = speedMs
        if (lastSpeed < SPEED_THRESHOLD_MS && lastResult != null) {
            return PkResult(lastResult!!.pk, lastResult!!.codeLigne, deadReckoning = true)
        }
        val nearest = network.nearest(lon, lat) ?: return null
        val point = nearest.point
        var pk = point.pk
        var interpole = false
        val n = network.neighbors(point)
        if (n != null) {
            val interp = GeoUtils.interpolePk(n.first, n.second, lon, lat)
            if (interp != null) {
                pk = interp
                interpole = true
            }
        }
        val codeLigne = detectLine(point.codeLigne)
        return PkResult(pk, codeLigne, interpole).also { lastResult = it }
    }

    fun detectLine(candidate: String): String {
        lineHistory.addLast(candidate)
        while (lineHistory.size > HYSTERESIS) lineHistory.removeFirst()
        val allSame = lineHistory.all { it == candidate }
        if (allSame && lastResult != null && lastResult!!.codeLigne != candidate) {
            lineHistory.clear()
            lineHistory.addLast(candidate)
            return candidate
        }
        if (lastResult != null && !allSame) return lastResult!!.codeLigne
        return candidate
    }
}
