package kr.mobility.mirichaeum

import kr.mobility.core.geo.GeoPoint
import kr.mobility.core.geo.PlaceCell
import kr.mobility.core.time.KST
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TripRecorderTest {
    private val rec = TripRecorder()
    private val home = GeoPoint(37.2517, 127.0710)
    private val work = GeoPoint(37.4020, 127.1086)
    private fun t(h: Int, m: Int = 0) = ZonedDateTime.of(2026, 10, 30, h, m, 0, 0, KST)

    @Test
    fun `출발 → 주차 → 3분 뒤 운행 확정, 다음 출발 때 체류 기록`() {
        var s = RecorderState()
        s = rec.handle(s, GearEvent(t(8), parked = false, location = home, odometerKm = 1000.0)).state
        s = rec.handle(s, GearEvent(t(8, 40), parked = true, location = work, odometerKm = 1019.0)).state
        val early = rec.tick(s, t(8, 41))
        assertTrue(early.trips.isEmpty())
        val done = rec.tick(early.state, t(8, 44))
        val trip = done.trips.single()
        assertEquals(19.0, trip.distanceKm, 1e-9)
        assertEquals(PlaceCell.of(home), trip.from)
        assertEquals(PlaceCell.of(work), trip.to)

        val next = rec.handle(done.state, GearEvent(t(18, 30), parked = false, location = work, odometerKm = 1019.0))
        assertEquals(590.0, next.dwells.single().minutes)
        assertTrue(next.trips.isEmpty())
    }

    @Test
    fun `3분 안에 다시 출발하면 같은 운행`() {
        var s = RecorderState()
        s = rec.handle(s, GearEvent(t(8), false, home, 1000.0)).state
        s = rec.handle(s, GearEvent(t(8, 20), true, GeoPoint(37.33, 127.09), 1010.0)).state
        val resumed = rec.handle(s, GearEvent(t(8, 22), false, GeoPoint(37.33, 127.09), 1010.0))
        assertTrue(resumed.trips.isEmpty() && resumed.dwells.isEmpty())
        s = rec.handle(resumed.state, GearEvent(t(8, 40), true, work, 1019.0)).state
        val trip = rec.tick(s, t(9)).trips.single()
        assertEquals(t(8), trip.start)
        assertEquals(19.0, trip.distanceKm, 1e-9)
    }

    @Test
    fun `총 주행거리가 없으면 직선거리 x 1_3, 아주 짧은 이동은 버린다`() {
        var s = rec.handle(RecorderState(), GearEvent(t(8), false, home, null)).state
        s = rec.handle(s, GearEvent(t(8, 40), true, work, null)).state
        val trip = rec.tick(s, t(9)).trips.single()
        assertTrue(trip.distanceKm in 21.0..23.0, "${trip.distanceKm}")

        var short = rec.handle(RecorderState(), GearEvent(t(10), false, home, 500.0)).state
        short = rec.handle(short, GearEvent(t(10, 5), true, home, 500.1)).state
        assertTrue(rec.tick(short, t(11)).trips.isEmpty())
    }

    @Test
    fun `주차 상태에서 다시 출발하면 미확정 운행을 먼저 확정한다`() {
        var s = rec.handle(RecorderState(), GearEvent(t(8), false, home, 1000.0)).state
        s = rec.handle(s, GearEvent(t(8, 40), true, work, 1019.0)).state
        val out = rec.handle(s, GearEvent(t(12), false, work, 1019.0))
        assertEquals(1, out.trips.size)
        assertEquals(1, out.dwells.size)
    }
}
