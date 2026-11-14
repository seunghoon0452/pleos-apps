package kr.mobility.mirichaeum

import kr.mobility.core.geo.PlaceCell
import java.time.ZonedDateTime
import kotlin.math.ceil
import kotlin.math.min

/** 일정이 어디서 왔는지. */
enum class GuideSource {
    /** 운행 패턴으로 찾은 반복 목적지 (요일·시간대). */
    ROUTINE,

    /** 캘린더(말·터치)에 등록한 일정의 목적지. */
    PLANNED,

    /** 기록이 2주 미만일 때 하루 평균 이동 (R-15). */
    ESTIMATE,
}

/**
 * 출발 전에 맞춰 둘 잔량 하나(최소 또는 권장)와, 지금 잔량에서 거기까지 채우는 양·시간.
 * - [levelPercent]: 출발할 때 있어야 할 배터리·연료 %. 100을 넘으면 한 번에 채울 수 없어 가는 길에 충전·주유가 필요하다.
 * - [fastMinutes]: 전기차는 급속(100kW) 기준 충전 시간, 내연기관은 주유 시간(약 5분).
 * - [slowMinutes]: 전기차 완속(7kW) 기준 충전 시간. 내연기관은 null.
 */
data class ChargeTarget(
    val levelPercent: Double,
    val addPercent: Double,
    /** kWh 또는 L. */
    val addAmount: Double,
    val fastMinutes: Double,
    val slowMinutes: Double?,
) {
    /** 지금 잔량으로 이미 충분하다. */
    val reached: Boolean get() = addPercent <= 0.0

    /** 한 번에 채울 수 있는 양(100%)을 넘는다. */
    val overFull: Boolean get() = levelPercent > 100.0
}

/**
 * 일정(목적지) 하나에 대한 충전·주유 안내 (F-26).
 * 최소 충전량 = 이 일정을 다녀온 뒤 최소 잔량(전기차 10%, 내연기관 30km)이 남는 양,
 * 권장 충전량 = 필요 거리에 15% 여유를 더하고 다녀온 뒤 권장 잔량(전기차 15%, 내연기관 50km)이 남는 양 (R-08).
 */
data class TripGuide(
    val source: GuideSource,
    val at: ZonedDateTime,
    val title: String,
    /** 이 일정 목적지를 오가는 거리. */
    val roundTripKm: Double,
    /** 지금부터 이 일정을 다녀올 때까지 달릴 거리(앞선 이동 포함). 캘린더처럼 일정만 볼 때는 [roundTripKm]과 같다. */
    val needKm: Double,
    val minimum: ChargeTarget,
    val recommended: ChargeTarget,
    val currentPercent: Double,
    val fuelType: FuelType,
    /** 지금 잔량 기준: 권장 이상 초록, 최소 이상 노랑, 최소 미만 빨강. */
    val verdict: Verdict,
    val trip: VoiceTrip? = null,
    val pattern: RoutinePattern? = null,
)

/**
 * 일정(목적지)마다 최소·권장 충전량과 충전 시간을 계산한다 (F-26, R-08, R-12).
 * 잔량 % 환산은 차가 알려 준 주행가능거리(겨울 보정)를 지금 잔량으로 나눈 '1%당 km'를 쓴다.
 * 그래서 지금 잔량이 권장 이상이면 판정(R-09)도 초록, 최소 이상이면 노랑으로 항상 일치한다.
 */
class TripGuidePlanner(
    private val judge: RangeJudge = RangeJudge(),
    private val fastKw: Double = FAST_KW,
    private val slowKw: Double = SLOW_KW,
    private val iceFillMinutes: Double = ICE_FILL_MINUTES,
) {
    fun kmPerPercent(s: EnergySnapshot): Double {
        val corrected = s.rangeKm * judge.winterFactor(s.fuelType, s.outsideTempC)
        return if (s.levelPercent >= 1.0 && corrected > 0) corrected / s.levelPercent
        else judge.correctedEfficiency(s) * s.capacity / 100.0
    }

    /** 출발할 때 [km]를 달릴 수 있는 잔량과, 지금부터 거기까지 채우는 양·시간. */
    fun target(s: EnergySnapshot, km: Double): ChargeTarget {
        val level = km / kmPerPercent(s)
        // 화면에는 올림한 %를 보여주므로 채우는 양·시간도 그 값까지로 계산한다. 이미 기준 이상이면 0.
        val shown = ceil(level - 1e-9)
        val add = if (s.levelPercent >= level) 0.0 else (min(shown, 100.0) - s.levelPercent).coerceAtLeast(0.0)
        val amount = add / 100.0 * s.capacity
        return when (s.fuelType) {
            FuelType.EV -> ChargeTarget(level, add, amount, amount / (fastKw * FAST_FACTOR) * 60.0, amount / (slowKw * SLOW_FACTOR) * 60.0)
            FuelType.ICE -> ChargeTarget(level, add, amount, if (add > 0) iceFillMinutes else 0.0, null)
        }
    }

    /** [needKm]을 달려야 할 때의 안내. */
    fun guide(s: EnergySnapshot, event: TripEvent, needKm: Double): TripGuide {
        val j = judge.judge(s, needKm)
        return TripGuide(
            source = event.source,
            at = event.at,
            title = event.title,
            roundTripKm = event.roundTripKm,
            needKm = needKm,
            minimum = target(s, j.minimumKm),
            recommended = target(s, j.safeKm),
            currentPercent = s.levelPercent,
            fuelType = s.fuelType,
            verdict = j.verdict,
            trip = event.trip,
            pattern = event.pattern,
        )
    }

    /**
     * 48시간 안의 일정마다 지금 잔량 기준 안내. 그 일정 전에 할 이동도 함께 센다(중간에 채우지 않는다고 본다).
     */
    fun guides(s: EnergySnapshot, demand: Demand, roles: Map<PlaceCell, PlaceRole>, names: Map<PlaceCell, String>): List<TripGuide> =
        TripEvents.find(demand, roles, names).map { guide(s, it, it.cumulativeKm) }

    /** 캘린더: 이 일정 하나만 다녀올 때 필요한 최소·권장 잔량(출발 기준). */
    fun standalone(s: EnergySnapshot, event: TripEvent): TripGuide = guide(s, event, event.roundTripKm)

    companion object {
        /** 급속 충전 기준 출력(kW)과 평균 효율. 충전 중 화면(S5)과 같은 계산. */
        const val FAST_KW = 100.0
        const val FAST_FACTOR = 0.85

        /** 완속 충전 기준 출력(kW)과 평균 효율. */
        const val SLOW_KW = 7.0
        const val SLOW_FACTOR = 0.9

        /** 내연기관 주유 시간(분, 가안). */
        const val ICE_FILL_MINUTES = 5.0
    }
}

/** 예상 이동(Demand)에서 찾은 일정(목적지) 하나. */
data class TripEvent(
    val source: GuideSource,
    val at: ZonedDateTime,
    val title: String,
    val roundTripKm: Double,
    /** 지금부터 이 일정을 다녀올 때까지의 누적 거리. */
    val cumulativeKm: Double,
    val trip: VoiceTrip? = null,
    val pattern: RoutinePattern? = null,
)

/**
 * 예상 이동을 '목적지 일정'으로 묶는다 (F-26).
 * - 캘린더 일정: 등록한 일정 하나 = 목적지 왕복 하나.
 * - 평소 이동: 집이 아닌 곳으로 가는 반복 이동을 목적지로 보고, 그곳에서 떠나는 다음 이동(24시간 안)과 묶어 왕복으로 센다.
 *   돌아오는 이동을 못 찾으면 같은 거리로 돌아온다고 본다. 집으로 오는 이동은 따로 일정으로 만들지 않는다.
 * - 기록이 적을 때(추정): 48시간 평균 이동 하나.
 */
object TripEvents {
    fun find(demand: Demand, roles: Map<PlaceCell, PlaceRole>, names: Map<PlaceCell, String>): List<TripEvent> {
        val items = demand.items.sortedBy { it.at }
        fun cumulativeThrough(index: Int): Double = items.take(index + 1).sumOf { it.km }
        val consumed = mutableSetOf<Int>()
        val events = mutableListOf<TripEvent>()
        for ((i, item) in items.withIndex()) {
            if (i in consumed) continue
            when (item.source) {
                DemandSource.VOICE -> {
                    val v = item.voiceTrip!!
                    events += TripEvent(GuideSource.PLANNED, item.at, listOfNotNull(v.label, v.purpose).joinToString(" "), item.km, cumulativeThrough(i), trip = v)
                }
                DemandSource.ESTIMATE ->
                    events += TripEvent(GuideSource.ESTIMATE, item.at, "평소 이동 (하루 평균)", item.km, cumulativeThrough(i))
                DemandSource.ROUTINE -> {
                    val p = item.pattern!!
                    if (roles[p.to] == PlaceRole.HOME) continue
                    // 같은 2시간 칸에 가는 이동과 돌아오는 이동이 함께 있을 수 있어 순서가 아니라 시각으로 찾는다.
                    val back = items.indices.firstOrNull { k ->
                        k != i && k !in consumed && items[k].source == DemandSource.ROUTINE && items[k].pattern?.from == p.to &&
                            !items[k].at.isBefore(item.at) && items[k].at.isBefore(item.at.plusHours(24))
                    }
                    val title = names[p.to] ?: when (roles[p.to]) {
                        PlaceRole.WORK -> "회사"
                        else -> "자주 가는 곳"
                    }
                    if (back != null) {
                        consumed += back
                        events += TripEvent(GuideSource.ROUTINE, item.at, title, item.km + items[back].km, cumulativeThrough(maxOf(i, back)), pattern = p)
                    } else {
                        events += TripEvent(GuideSource.ROUTINE, item.at, title, item.km * 2, cumulativeThrough(i) + item.km, pattern = p)
                    }
                }
            }
        }
        return events.sortedBy { it.at }
    }
}
