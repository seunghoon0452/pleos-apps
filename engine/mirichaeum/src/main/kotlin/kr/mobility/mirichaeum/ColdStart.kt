package kr.mobility.mirichaeum

import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.ZonedDateTime

/** 앱을 열 때 읽은 총 주행거리. */
data class OdometerReading(val at: ZonedDateTime, val km: Double)

/**
 * R-02 대비책: 앱이 꺼져 있어서 운행 기록이 빠졌을 때,
 * 총 주행거리 차이를 지난 시간에 고르게 나눠 날짜별·요일별 하루 평균을 낸다.
 */
object OdometerFallback {
    fun kmByDate(readings: List<OdometerReading>): Map<LocalDate, Double> {
        val sorted = readings.sortedBy { it.at }
        val result = sortedMapOf<LocalDate, Double>()
        for ((a, b) in sorted.zipWithNext()) {
            val delta = b.km - a.km
            val totalMinutes = Duration.between(a.at, b.at).toMinutes().toDouble()
            if (delta <= 0 || totalMinutes <= 0) continue
            var cursor = a.at
            while (cursor.isBefore(b.at)) {
                val nextMidnight = cursor.toLocalDate().plusDays(1).atStartOfDay(cursor.zone)
                val segmentEnd = minOf(nextMidnight, b.at)
                val share = Duration.between(cursor, segmentEnd).toMinutes() / totalMinutes
                result.merge(cursor.toLocalDate(), delta * share, Double::plus)
                cursor = segmentEnd
            }
        }
        return result
    }

    fun weekdayAverages(readings: List<OdometerReading>): Map<DayOfWeek, Double> {
        val sorted = readings.sortedBy { it.at }
        if (sorted.size < 2) return emptyMap()
        val byDate = kmByDate(sorted)
        val dates = generateSequence(sorted.first().at.toLocalDate()) { it.plusDays(1) }
            .takeWhile { !it.isAfter(sorted.last().at.toLocalDate()) }
            .toList()
        return dates.groupBy { it.dayOfWeek }.mapValues { (_, ds) -> ds.sumOf { byDate[it] ?: 0.0 } / ds.size }
    }

    fun dailyAverage(readings: List<OdometerReading>): Double? {
        val sorted = readings.sortedBy { it.at }
        if (sorted.size < 2) return null
        val days = Duration.between(sorted.first().at, sorted.last().at).toMinutes() / (24.0 * 60)
        if (days < 0.5) return null
        return (sorted.last().km - sorted.first().km).coerceAtLeast(0.0) / days
    }
}

/** R-15: 기록이 2주 미만이면 패턴 대신 하루 평균으로 판정한다. */
object ColdStart {
    const val MIN_HISTORY_DAYS = 14L

    /** 사용자 답도, 총 주행거리도 없을 때 쓰는 하루 평균(가안). */
    const val DEFAULT_DAILY_KM = 40.0

    fun isColdStart(trips: List<TripRecord>, now: ZonedDateTime): Boolean {
        val first = trips.minOfOrNull { it.start } ?: return true
        return Duration.between(first, now).toDays() < MIN_HISTORY_DAYS
    }
}
