package kr.mobility.core.time

import kr.mobility.core.geo.GeoPoint
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

data class SunTimes(val sunrise: ZonedDateTime, val sunset: ZonedDateTime)

/**
 * 해 뜨는 시각·지는 시각 계산(NOAA 일반 태양 위치 계산식).
 * 관심 주행의 '야간' 요인 판단에 쓴다. 오차는 보통 수 분 이내다.
 */
object SunCalculator {
    private const val ZENITH_DEG = 90.833 // 대기 굴절과 해의 반지름을 반영한 일출·일몰 기준

    /** 극지방처럼 해가 뜨지 않거나 지지 않는 날은 null. */
    fun sunTimes(date: LocalDate, point: GeoPoint, zone: ZoneId): SunTimes? {
        val gamma = 2 * Math.PI / 365.0 * (date.dayOfYear - 1)
        val eqTimeMin = 229.18 * (
            0.000075 + 0.001868 * cos(gamma) - 0.032077 * sin(gamma) -
                0.014615 * cos(2 * gamma) - 0.040849 * sin(2 * gamma)
            )
        val decl = 0.006918 - 0.399912 * cos(gamma) + 0.070257 * sin(gamma) -
            0.006758 * cos(2 * gamma) + 0.000907 * sin(2 * gamma) -
            0.002697 * cos(3 * gamma) + 0.00148 * sin(3 * gamma)
        val latRad = Math.toRadians(point.lat)
        val cosHa = cos(Math.toRadians(ZENITH_DEG)) / (cos(latRad) * cos(decl)) - tan(latRad) * tan(decl)
        if (cosHa < -1.0 || cosHa > 1.0) return null
        val haDeg = Math.toDegrees(acos(cosHa))
        val sunriseUtcMin = 720 - 4 * (point.lng + haDeg) - eqTimeMin
        val sunsetUtcMin = 720 - 4 * (point.lng - haDeg) - eqTimeMin
        val midnightUtc = date.atStartOfDay(ZoneOffset.UTC)
        return SunTimes(
            sunrise = midnightUtc.plusSeconds((sunriseUtcMin * 60).toLong()).withZoneSameInstant(zone),
            sunset = midnightUtc.plusSeconds((sunsetUtcMin * 60).toLong()).withZoneSameInstant(zone),
        )
    }

    /** 해가 진 뒤부터 다음 날 해 뜨기 전까지를 야간으로 본다. */
    fun isNight(at: ZonedDateTime, point: GeoPoint): Boolean {
        val times = sunTimes(at.toLocalDate(), point, at.zone) ?: return false
        return at.isBefore(times.sunrise) || !at.isBefore(times.sunset)
    }
}
