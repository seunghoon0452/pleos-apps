package kr.mobility.core.geo

import kotlin.test.Test
import kotlin.test.assertTrue

class KatecTest {
    @Test
    fun `왕복 변환 오차는 1m 미만`() {
        for (p in listOf(GeoPoint(37.5665, 126.9780), GeoPoint(35.1151, 129.0418), GeoPoint(33.4996, 126.5312))) {
            val (x, y) = Katec.fromWgs84(p)
            assertTrue(Geo.distanceMeters(p, Katec.toWgs84(x, y)) < 1.0, "$p")
        }
    }

    @Test
    fun `서울시청은 KATEC 값 범위 안`() {
        val (x, y) = Katec.fromWgs84(GeoPoint(37.5665, 126.9780))
        assertTrue(x in 305_000.0..315_000.0, "x=$x")
        assertTrue(y in 548_000.0..556_000.0, "y=$y")
    }
}
