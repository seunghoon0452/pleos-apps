package kr.mobility.core.geo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class GeoTest {
    private val seoulStation = GeoPoint(37.5547, 126.9707)
    private val daejeonStation = GeoPoint(36.3324, 127.4345)

    @Test
    fun `서울역-대전역 직선거리는 약 140km`() {
        val km = Geo.distanceMeters(seoulStation, daejeonStation) / 1000
        assertTrue(km in 138.0..144.0, "got $km")
    }

    @Test
    fun `같은 점의 거리는 0`() {
        assertEquals(0.0, Geo.distanceMeters(seoulStation, seoulStation), 1e-9)
    }

    @Test
    fun `방위각 - 정북과 정동`() {
        val north = GeoPoint(38.0, 127.0)
        val east = GeoPoint(37.0, 128.0)
        val origin = GeoPoint(37.0, 127.0)
        assertEquals(0.0, Geo.bearingDegrees(origin, north), 0.5)
        assertEquals(90.0, Geo.bearingDegrees(origin, east), 1.0)
    }

    @Test
    fun `도로 거리 추정은 직선 x 1_3`() {
        val straight = Geo.distanceMeters(seoulStation, daejeonStation) / 1000
        assertEquals(straight * 1.3, Geo.estimatedRoadKm(seoulStation, daejeonStation), 1e-6)
    }

    @Test
    fun `범위를 벗어난 좌표는 거부`() {
        assertFailsWith<IllegalArgumentException> { GeoPoint(91.0, 0.0) }
        assertFailsWith<IllegalArgumentException> { GeoPoint(0.0, 181.0) }
    }

    @Test
    fun `가까운 두 점은 같은 칸, 먼 점은 다른 칸`() {
        val a = PlaceCell.of(GeoPoint(37.50000, 127.03000))
        val b = PlaceCell.of(GeoPoint(37.50010, 127.03010)) // 약 14m
        val far = PlaceCell.of(GeoPoint(37.51000, 127.03000)) // 약 1.1km
        assertEquals(a, b)
        assertNotEquals(a, far)
    }

    @Test
    fun `칸의 중심은 원래 점에서 칸 크기 이내`() {
        val p = GeoPoint(37.4979, 127.0276)
        val cell = PlaceCell.of(p)
        assertTrue(Geo.distanceMeters(p, cell.center) <= PlaceCell.DEFAULT_SIZE_METERS)
        assertEquals(cell, PlaceCell.of(cell.center))
    }
}
