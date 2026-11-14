package kr.mobility.mirichaeum

import kr.mobility.core.geo.PlaceCell
import kr.mobility.core.time.WeekSlot
import java.time.DayOfWeek
import java.time.Duration
import java.time.ZonedDateTime

/** 평소 이동 하나: 같은 요일·시간대에 같은 곳으로 가는 반복 (R-03). */
data class RoutinePattern(
    val slot: WeekSlot,
    val from: PlaceCell,
    val to: PlaceCell,
    val typicalKm: Double,
    val weeksSeen: Int,
    val windowWeeks: Int,
) {
    /** 확신도 = 나타난 주 수 ÷ 4. */
    val confidence: Double get() = weeksSeen.toDouble() / windowWeeks
}

/** 평소 체류 하나: 같은 요일·시간대에 같은 곳에 머무는 반복. 체류 충전(R-11)에 쓴다. */
data class DwellPattern(
    val slot: WeekSlot,
    val place: PlaceCell,
    val typicalMinutes: Double,
    val weeksSeen: Int,
    val windowWeeks: Int,
) {
    val confidence: Double get() = weeksSeen.toDouble() / windowWeeks
}

/** 집·회사 같은 장소 역할. 안내 문구("퇴근할 때")에 쓴다. */
enum class PlaceRole { HOME, WORK, OTHER }

/**
 * 최근 기록에서 평소 이동·체류를 찾는다.
 * 기본값은 정의서 R-03: 최근 4주 중 3주 이상, 2시간 단위.
 */
class PatternLearner(
    private val windowWeeks: Int = 4,
    private val minWeeks: Int = 3,
) {
    private fun weekIndex(at: ZonedDateTime, now: ZonedDateTime): Int? {
        if (!at.isBefore(now)) return null
        val index = (Duration.between(at, now).toDays() / 7).toInt()
        return if (index < windowWeeks) index else null
    }

    fun learnTrips(trips: List<TripRecord>, now: ZonedDateTime): List<RoutinePattern> =
        trips.mapNotNull { t -> weekIndex(t.start, now)?.let { t to it } }
            .groupBy { (t, _) -> WeekSlot.of(t.start) to t.to }
            .mapNotNull { (key, group) ->
                val weeks = group.map { it.second }.distinct().size
                if (weeks < minWeeks) return@mapNotNull null
                val records = group.map { it.first }
                RoutinePattern(
                    slot = key.first,
                    from = records.groupingBy { it.from }.eachCount().maxBy { it.value }.key,
                    to = key.second,
                    typicalKm = median(records.map { it.distanceKm }),
                    weeksSeen = weeks,
                    windowWeeks = windowWeeks,
                )
            }
            .sortedWith(compareBy({ it.slot.day }, { it.slot.slot }))

    fun learnDwells(dwells: List<DwellRecord>, now: ZonedDateTime): List<DwellPattern> =
        dwells.mapNotNull { d -> weekIndex(d.arrive, now)?.let { d to it } }
            .groupBy { (d, _) -> WeekSlot.of(d.arrive) to d.place }
            .mapNotNull { (key, group) ->
                val weeks = group.map { it.second }.distinct().size
                if (weeks < minWeeks) return@mapNotNull null
                DwellPattern(key.first, key.second, median(group.map { it.first.minutes }), weeks, windowWeeks)
            }
            .sortedWith(compareBy({ it.slot.day }, { it.slot.slot }))

    companion object {
        fun median(values: List<Double>): Double {
            require(values.isNotEmpty())
            val s = values.sorted()
            val mid = s.size / 2
            return if (s.size % 2 == 1) s[mid] else (s[mid - 1] + s[mid]) / 2
        }
    }
}

/**
 * 집 = 밤(0~5시)에 가장 오래 머문 곳, 회사 = 평일에 4시간 이상 머문 시간이 가장 긴 곳(집 제외).
 * 기록이 부족하면 해당 역할이 비어 있을 수 있다.
 */
object PlaceRoles {
    private const val WORK_MIN_MINUTES = 240.0
    private val weekend = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)

    fun infer(dwells: List<DwellRecord>): Map<PlaceCell, PlaceRole> {
        val nightMinutes = mutableMapOf<PlaceCell, Long>()
        for (d in dwells) {
            var day = d.arrive.toLocalDate()
            while (!day.isAfter(d.depart.toLocalDate())) {
                val nightStart = day.atStartOfDay(d.arrive.zone)
                val nightEnd = nightStart.plusHours(5)
                val overlap = Duration.between(maxOf(d.arrive, nightStart), minOf(d.depart, nightEnd)).toMinutes()
                if (overlap > 0) nightMinutes.merge(d.place, overlap, Long::plus)
                day = day.plusDays(1)
            }
        }
        val home = nightMinutes.maxByOrNull { it.value }?.key
        val work = dwells
            .filter { it.place != home && it.minutes >= WORK_MIN_MINUTES && it.arrive.dayOfWeek !in weekend }
            .groupBy { it.place }
            .maxByOrNull { (_, list) -> list.sumOf { it.minutes } }
            ?.key
        return buildMap {
            dwells.map { it.place }.distinct().forEach { put(it, PlaceRole.OTHER) }
            if (work != null) put(work, PlaceRole.WORK)
            if (home != null) put(home, PlaceRole.HOME)
        }
    }
}
