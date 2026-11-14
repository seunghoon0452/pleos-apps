package kr.mobility.core.text

import kr.mobility.core.vehicle.DrivingState
import kr.mobility.core.vehicle.Gear
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KoreanTest {
    @Test
    fun `조사 고르기`() {
        assertEquals("마트를", Korean.eulReul("마트"))
        assertEquals("주유소가", Korean.iGa("주유소"))
        assertEquals("충전소는", Korean.eunNeun("충전소"))
        assertEquals("집으로", Korean.euroRo("집"))
        assertEquals("서울로", Korean.euroRo("서울"))
        assertEquals("회사로", Korean.euroRo("회사"))
        assertEquals("3번을", Korean.eulReul("3번"))
        assertEquals("1을", Korean.eulReul("1"))
        assertEquals("2를", Korean.eulReul("2"))
        assertEquals("일정이에요", Korean.iya("일정"))
        assertEquals("15km예요", Korean.iya("15km"))
    }

    @Test
    fun `금액 읽기`() {
        assertEquals("12,340원", Korean.won(12_340.0))
        assertEquals("1만 2천 원", Korean.wonSpoken(12_340.0))
        assertEquals("3천 5백 원", Korean.wonSpoken(3_460.0))
        assertEquals("5만 원", Korean.wonSpoken(50_200.0))
        assertEquals("0원", Korean.wonSpoken(20.0))
    }

    @Test
    fun `시간·날짜 읽기`() {
        assertEquals("47분", Korean.minutes(47.0))
        assertEquals("1시간 5분", Korean.minutes(65.0))
        assertEquals("2시간", Korean.minutes(120.0))
        assertEquals("11월 3일 화요일", Korean.spokenDate(LocalDate.of(2026, 11, 3)))
        assertEquals("오후 3시", Korean.spokenTime(LocalTime.of(15, 0)))
        assertEquals("오전 7시 30분", Korean.spokenTime(LocalTime.of(7, 30)))
        assertEquals("밤 12시", Korean.spokenTime(LocalTime.MIDNIGHT))
        assertEquals("낮 12시", Korean.spokenTime(LocalTime.NOON))
    }

    @Test
    fun `상황 구분`() {
        assertEquals(DrivingState.PARKED, DrivingState.from(Gear.PARK, 0.0))
        assertEquals(DrivingState.CHARGING, DrivingState.from(Gear.PARK, 0.0, charging = true))
        assertEquals(DrivingState.PARKED, DrivingState.from(Gear.DRIVE, 0.0, parkingBrake = true))
        assertEquals(DrivingState.STOPPED, DrivingState.from(Gear.DRIVE, 0.0))
        assertEquals(DrivingState.DRIVING, DrivingState.from(Gear.DRIVE, 40.0))
        assertTrue(DrivingState.STOPPED.isRestricted)
        assertFalse(DrivingState.PARKED.isRestricted)
    }
}
