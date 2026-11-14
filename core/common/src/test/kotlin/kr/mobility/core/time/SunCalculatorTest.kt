package kr.mobility.core.time

import kr.mobility.core.geo.GeoPoint
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SunCalculatorTest {
    private val seoul = GeoPoint(37.5665, 126.9780)

    private fun assertNear(expected: LocalTime, actual: LocalTime, toleranceMin: Long = 10) {
        val diff = abs(ChronoUnit.MINUTES.between(expected, actual))
        assertTrue(diff <= toleranceMin, "expected ~$expected, got $actual")
    }

    @Test
    fun `서울 11월 3일 일출 약 06시58분, 일몰 약 17시32분`() {
        val t = assertNotNull(SunCalculator.sunTimes(LocalDate.of(2026, 11, 3), seoul, KST))
        assertNear(LocalTime.of(6, 58), t.sunrise.toLocalTime())
        assertNear(LocalTime.of(17, 32), t.sunset.toLocalTime())
    }

    @Test
    fun `서울 하지 일출 약 05시11분, 일몰 약 19시57분`() {
        val t = assertNotNull(SunCalculator.sunTimes(LocalDate.of(2026, 6, 21), seoul, KST))
        assertNear(LocalTime.of(5, 11), t.sunrise.toLocalTime())
        assertNear(LocalTime.of(19, 57), t.sunset.toLocalTime())
    }

    @Test
    fun `야간 판단`() {
        assertTrue(SunCalculator.isNight(ZonedDateTime.of(2026, 11, 3, 19, 0, 0, 0, KST), seoul))
        assertTrue(SunCalculator.isNight(ZonedDateTime.of(2026, 11, 3, 5, 0, 0, 0, KST), seoul))
        assertFalse(SunCalculator.isNight(ZonedDateTime.of(2026, 11, 3, 12, 0, 0, 0, KST), seoul))
    }

    @Test
    fun `극야 지역은 null`() {
        assertNull(SunCalculator.sunTimes(LocalDate.of(2026, 12, 21), GeoPoint(80.0, 0.0), KST))
    }
}
