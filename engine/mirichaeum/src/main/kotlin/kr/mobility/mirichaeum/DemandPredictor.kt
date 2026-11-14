package kr.mobility.mirichaeum

import kr.mobility.core.geo.PlaceCell
import kr.mobility.core.time.WeekSlot
import java.time.LocalDate
import java.time.ZonedDateTime
import kotlin.math.abs

enum class DemandSource {
    /** 평소 이동 패턴. */
    ROUTINE,

    /** 말 또는 캘린더로 등록한 이동 일정. */
    VOICE,

    /** 기록이 2주 미만일 때 하루 평균으로 추정 (R-15). */
    ESTIMATE,
}

data class DemandItem(
    val source: DemandSource,
    val at: ZonedDateTime,
    val km: Double,
    val pattern: RoutinePattern? = null,
    val voiceTrip: VoiceTrip? = null,
)

/** 지금부터 [until]까지 필요할 것으로 보이는 거리. */
data class Demand(
    val from: ZonedDateTime,
    val until: ZonedDateTime,
    val items: List<DemandItem>,
    /** 장소를 아직 안 골라서 거리를 모르는 이동 일정. 화면에서 "장소를 골라주세요"로 보여준다. */
    val unresolvedVoiceTrips: List<VoiceTrip> = emptyList(),
) {
    val totalKm: Double get() = items.sumOf { it.km }

    /** 이유로 보여줄 가장 큰 항목(정의서: 이유를 항상 보여준다). */
    val mainItem: DemandItem? get() = items.maxByOrNull { it.km }
}

/** R-04: 예상 거리 = 확신도 75% 이상 평소 이동 + 이동 일정(왕복). */
class DemandPredictor(
    private val horizonHours: Long = 48,
    private val minConfidence: Double = 0.75,
) {
    fun predict(
        now: ZonedDateTime,
        patterns: List<RoutinePattern>,
        voiceTrips: List<VoiceTrip>,
        recentTrips: List<TripRecord> = emptyList(),
        skipDays: Set<LocalDate> = emptySet(),
    ): Demand = between(now, now.plusHours(horizonHours), patterns, voiceTrips, recentTrips, skipDays)

    /**
     * [from]부터 [until]까지 같은 규칙으로 센다. 48시간 판정과 캘린더 전망(F-22)이 함께 쓴다.
     * - [skipDays]: '이날은 평소대로 안 다녀요'로 고른 날. 그날 평소 이동은 세지 않는다 (F-25).
     * - 등록한 일정과 같은 날 같은 곳을 오가는 평소 이동은 한 번만 센다 (A-84).
     */
    fun between(
        from: ZonedDateTime,
        until: ZonedDateTime,
        patterns: List<RoutinePattern>,
        voiceTrips: List<VoiceTrip>,
        recentTrips: List<TripRecord> = emptyList(),
        skipDays: Set<LocalDate> = emptySet(),
    ): Demand {
        val planned = mutableListOf<DemandItem>()
        val unresolved = mutableListOf<VoiceTrip>()
        for (v in voiceTrips) {
            val times = v.occurrences(from, until)
            if (times.isEmpty()) continue
            val km = v.roundTripKm
            if (km == null) unresolved += v else times.forEach { planned += DemandItem(DemandSource.VOICE, it, km, voiceTrip = v) }
        }

        val routine = mutableListOf<DemandItem>()
        for (p in patterns) {
            if (p.confidence < minConfidence) continue
            var occurrence = p.slot.nextStart(from)
            while (occurrence.isBefore(until)) {
                val counted = occurrence.toLocalDate() !in skipDays &&
                    !alreadyDone(p, occurrence, from, recentTrips) &&
                    !coveredByPlanned(p, occurrence, planned)
                if (counted) routine += DemandItem(DemandSource.ROUTINE, occurrence, p.typicalKm, pattern = p)
                occurrence = occurrence.plusWeeks(1)
            }
        }

        return Demand(from, until, (routine + planned).sortedBy { it.at }, unresolved)
    }

    /** R-15: 기록이 부족할 때 하루 평균 거리로 48시간을 추정한다. */
    fun estimate(now: ZonedDateTime, dailyKm: Double, voiceTrips: List<VoiceTrip> = emptyList()): Demand {
        val base = predict(now, emptyList(), voiceTrips)
        val estimateItem = DemandItem(DemandSource.ESTIMATE, now, dailyKm * horizonHours / 24.0)
        return base.copy(items = (base.items + estimateItem).sortedBy { it.at })
    }

    /** 진행 중인 시간대에 같은 목적지로 이미 다녀왔으면 다시 세지 않는다. */
    private fun alreadyDone(
        p: RoutinePattern,
        occurrence: ZonedDateTime,
        now: ZonedDateTime,
        recentTrips: List<TripRecord>,
    ): Boolean {
        if (occurrence.isAfter(now)) return false
        return recentTrips.any {
            it.to == p.to && !it.start.isBefore(occurrence) && it.start.isBefore(now) &&
                WeekSlot.of(it.start) == p.slot
        }
    }

    /** A-84: 같은 날 등록한 일정의 목적지(옆 칸 포함)를 오가는 평소 이동은 일정 왕복에 이미 들어 있다. */
    private fun coveredByPlanned(p: RoutinePattern, occurrence: ZonedDateTime, planned: List<DemandItem>): Boolean =
        planned.any { item ->
            val dest = item.voiceTrip?.destination ?: return@any false
            val cell = PlaceCell.of(dest, p.to.sizeMeters)
            item.at.toLocalDate() == occurrence.toLocalDate() && (near(cell, p.to) || near(cell, p.from))
        }

    private fun near(a: PlaceCell, b: PlaceCell): Boolean = abs(a.row - b.row) <= 1 && abs(a.col - b.col) <= 1
}
