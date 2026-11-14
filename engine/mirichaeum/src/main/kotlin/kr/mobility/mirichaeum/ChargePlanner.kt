package kr.mobility.mirichaeum

import kr.mobility.core.geo.Geo
import kr.mobility.core.geo.GeoPoint
import kr.mobility.core.geo.PlaceCell
import java.time.ZonedDateTime
import kotlin.math.ceil
import kotlin.math.min

/** 서울 공공 충전요금(2026-08 개편, 원/kWh). 충전소 데이터에 가격이 없을 때 쓴다. */
object Tariff {
    const val SLOW = 295.0
    const val FAST_50 = 325.6
    const val FAST_100 = 348.4
    const val ULTRA = 393.1

    /** 이 출력 미만은 완속으로 본다. */
    const val FAST_MIN_KW = 30.0

    fun priceFor(powerKw: Double): Double = when {
        powerKw < FAST_MIN_KW -> SLOW
        powerKw < 100.0 -> FAST_50
        powerKw < 200.0 -> FAST_100
        else -> ULTRA
    }
}

data class Charger(
    val id: String,
    val name: String,
    val location: GeoPoint,
    val powerKw: Double,
    /** 지금 사용 가능한 충전기 수. */
    val available: Int,
    val pricePerKwh: Double = Tariff.priceFor(powerKw),
    /** 전체 충전기 수. 모르면 null. */
    val total: Int? = null,
    val address: String? = null,
    /** 상태 정보가 몇 분 전 값인지. 모르면 null (A-35). */
    val statusAgeMinutes: Int? = null,
) {
    val isSlow: Boolean get() = powerKw < Tariff.FAST_MIN_KW
}

/** 경로 탐색으로 구한, 평소 경로에서 이 충전소까지 다녀오는 데 더 드는 시간. */
data class RouteCharger(val charger: Charger, val detourMinutes: Double)

data class FuelStation(
    val id: String,
    val name: String,
    val location: GeoPoint,
    val pricePerLiter: Double,
    val detourMinutes: Double,
    val address: String? = null,
)

/** 평소 체류 패턴으로 본, 앞으로 머물 곳. */
data class UpcomingDwell(
    val place: PlaceCell,
    val placeName: String?,
    val arriveAt: ZonedDateTime,
    val expectedMinutes: Double,
    val weeksSeen: Int,
)

enum class SolutionKind {
    /** 어차피 머무는 곳에서 완속 충전. 추가 시간 0분. */
    DWELL,

    /** 평소 경로상(이탈 3분 이내). */
    ON_ROUTE,

    /** 우회 급속. 마지막 수단. */
    DETOUR,

    /** 오늘 밤 집에서 충전 (A-36). 내비에 넣을 것이 없다. */
    HOME,
}

data class Solution(
    val kind: SolutionKind,
    val placeName: String,
    val location: GeoPoint,
    /** 이번에 채울 양(kWh 또는 L). */
    val amount: Double,
    /** 채운 양으로 늘어나는 거리. */
    val addKm: Double,
    /** 평소보다 더 드는 시간(분). 체류 충전은 0. */
    val extraMinutes: Double,
    /** 충전·주유에 걸리는 시간(분). */
    val fillMinutes: Double,
    val costWon: Double,
    val unitPrice: Double,
    val powerKw: Double? = null,
    val startsAt: ZonedDateTime? = null,
    val dwell: UpcomingDwell? = null,
    /** 상세 화면(SC-04)용 값. 모르면 null. */
    val available: Int? = null,
    val total: Int? = null,
    val address: String? = null,
    val statusAgeMinutes: Int? = null,
    /** 경로에서 벗어나는 시간(분). 체류·집 충전은 null. */
    val detourMinutes: Double? = null,
)

data class ChargePlan(
    val fuelType: FuelType,
    /** 채워야 할 양(kWh 또는 L). 5% 또는 5L 단위로 올림. */
    val amount: Double,
    /** 전기차: 채워야 할 %. 내연기관: 탱크 대비 %. */
    val percent: Double,
    /** 채운 양으로 늘어나는 거리(보정 전비 기준). */
    val addKm: Double,
    /** 이때까지 채워야 한다. null이면 48시간 안에 언제든. */
    val deadline: ZonedDateTime?,
    /** 정의서 R-11 순서: 체류 충전 → 경로상 → 우회. 집 충전 조건이 맞으면 맨 앞(A-36). */
    val solutions: List<Solution>,
    /** 내연기관: 후보 주유소 평균 가격(원/L). */
    val averageUnitPrice: Double? = null,
) {
    val best: Solution? get() = solutions.firstOrNull()
}

/**
 * 해결책 만들기 (R-11, R-12).
 * - 필요 충전량 = (필요 + 여유분 − 보정 주행가능거리) ÷ 보정 전비
 * - 체류 충전량 = 충전기 출력 × 체류 시간 × 0.9
 * - 급속 충전 시간 = 충전량 ÷ (출력 × 0.85) — 충전 곡선을 감안한 가안
 */
class ChargePlanner(
    private val judge: RangeJudge = RangeJudge(),
    private val dwellMinMinutes: Double = 30.0,
    private val dwellRadiusMeters: Double = 300.0,
    private val onRouteMaxDetourMinutes: Double = 3.0,
    private val dwellChargeFactor: Double = 0.9,
    private val fastChargeFactor: Double = 0.85,
    private val fuelingMinutes: Double = 5.0,
    private val homeMinMinutes: Double = 360.0,
    private val homePowerKw: Double = 7.0,
) {
    fun upcomingDwells(
        patterns: List<DwellPattern>,
        now: ZonedDateTime,
        until: ZonedDateTime,
        names: Map<PlaceCell, String> = emptyMap(),
    ): List<UpcomingDwell> = patterns.flatMap { p ->
        generateSequence(p.slot.nextStart(now)) { it.plusWeeks(1) }
            .takeWhile { it.isBefore(until) }
            .map { UpcomingDwell(p.place, names[p.place], maxOf(it, now), p.typicalMinutes, p.weeksSeen) }
            .toList()
    }.sortedBy { it.arriveAt }

    /**
     * 언제까지 채워야 하는지. 시간순으로 거리를 쌓다가
     * (누적 × 1.15 + 최소 잔량)이 보정 주행가능거리를 넘는 첫 이동의 출발 시각.
     */
    fun deadline(demand: Demand, judgement: Judgement): ZonedDateTime? {
        var cumulative = 0.0
        val bufferRatio = if (judgement.needKm > 0) judgement.bufferKm / judgement.needKm else 0.0
        for (item in demand.items.sortedBy { it.at }) {
            cumulative += item.km
            if (cumulative * (1 + bufferRatio) + judgement.reserveKm > judgement.correctedRangeKm) return item.at
        }
        return null
    }

    fun plan(
        snapshot: EnergySnapshot,
        judgement: Judgement,
        demand: Demand,
        dwells: List<UpcomingDwell> = emptyList(),
        nearbyChargers: List<Charger> = emptyList(),
        routeChargers: List<RouteCharger> = emptyList(),
        stations: List<FuelStation> = emptyList(),
        /** 집 충전 '가능'일 때 집 칸(A-36). */
        homeCell: PlaceCell? = null,
    ): ChargePlan? {
        if (judgement.verdict == Verdict.ENOUGH) return null
        val deadline = deadline(demand, judgement)
        return when (snapshot.fuelType) {
            FuelType.EV -> planEv(snapshot, judgement, deadline, dwells, nearbyChargers, routeChargers, homeCell)
            FuelType.ICE -> planIce(snapshot, judgement, deadline, stations)
        }
    }

    private fun planEv(
        snapshot: EnergySnapshot,
        judgement: Judgement,
        deadline: ZonedDateTime?,
        dwells: List<UpcomingDwell>,
        nearbyChargers: List<Charger>,
        routeChargers: List<RouteCharger>,
        homeCell: PlaceCell?,
    ): ChargePlan {
        val eff = judge.correctedEfficiency(snapshot)
        val rawKwh = judgement.shortfallKm / eff
        val percent = min(roundUp(rawKwh / snapshot.capacity * 100, 5.0), 100.0 - snapshot.levelPercent)
        val kwh = snapshot.capacity * percent / 100.0
        val solutions = mutableListOf<Solution>()

        // 집 충전 가능 + 출발 전 집에 6시간 이상 머물 예정이면 1순위 (A-36)
        val home = homeCell?.let { cell ->
            dwells.firstOrNull { d ->
                d.place == cell && d.expectedMinutes >= homeMinMinutes && (deadline == null || d.arriveAt.isBefore(deadline))
            }
        }?.takeIf { d -> min(homePowerKw * d.expectedMinutes / 60.0 * dwellChargeFactor, snapshot.headroom) >= kwh }
            ?.let { d ->
                Solution(
                    kind = SolutionKind.HOME,
                    placeName = d.placeName ?: "집",
                    location = d.place.center,
                    amount = kwh,
                    addKm = kwh * eff,
                    extraMinutes = 0.0,
                    fillMinutes = kwh / (homePowerKw * dwellChargeFactor) * 60.0,
                    costWon = kwh * Tariff.SLOW,
                    unitPrice = Tariff.SLOW,
                    powerKw = homePowerKw,
                    startsAt = d.arriveAt,
                    dwell = d,
                )
            }

        // ① 체류 충전: 30분 이상 머무는 곳 반경 300m 안의 완속
        for (d in dwells) {
            if (d.expectedMinutes < dwellMinMinutes || d.place == homeCell) continue
            if (deadline != null && !d.arriveAt.isBefore(deadline)) continue
            val charger = nearbyChargers
                .filter { it.isSlow && it.available > 0 }
                .filter { Geo.distanceMeters(it.location, d.place.center) <= dwellRadiusMeters }
                .maxWithOrNull(compareBy<Charger> { it.powerKw }.thenByDescending { it.pricePerKwh }) ?: continue
            val canCharge = min(charger.powerKw * d.expectedMinutes / 60.0 * dwellChargeFactor, snapshot.headroom)
            if (canCharge < kwh) continue
            solutions += Solution(
                kind = SolutionKind.DWELL,
                placeName = d.placeName ?: charger.name,
                location = charger.location,
                amount = kwh,
                addKm = kwh * eff,
                extraMinutes = 0.0,
                fillMinutes = kwh / (charger.powerKw * dwellChargeFactor) * 60.0,
                costWon = kwh * charger.pricePerKwh,
                unitPrice = charger.pricePerKwh,
                powerKw = charger.powerKw,
                startsAt = d.arriveAt,
                dwell = d,
                available = charger.available,
                total = charger.total,
                address = charger.address,
                statusAgeMinutes = charger.statusAgeMinutes,
            )
        }

        // ② 평소 경로상(이탈 3분 이내), 가격순 / ③ 우회 급속, 더 드는 시간순
        val usable = routeChargers.filter { it.charger.available > 0 }
        val onRoute = usable.filter { it.detourMinutes <= onRouteMaxDetourMinutes }
            .map { fastSolution(SolutionKind.ON_ROUTE, it, kwh, eff) }
            .sortedWith(compareBy({ it.unitPrice }, { it.extraMinutes }))
        val detour = usable.filter { it.detourMinutes > onRouteMaxDetourMinutes && !it.charger.isSlow }
            .map { fastSolution(SolutionKind.DETOUR, it, kwh, eff) }
            .sortedWith(compareBy({ it.extraMinutes }, { it.unitPrice }))

        return ChargePlan(
            fuelType = FuelType.EV,
            amount = kwh,
            percent = percent,
            addKm = kwh * eff,
            deadline = deadline,
            solutions = listOfNotNull(home) + solutions.sortedBy { it.startsAt } + onRoute + detour,
        )
    }

    private fun fastSolution(kind: SolutionKind, rc: RouteCharger, kwh: Double, eff: Double): Solution {
        val c = rc.charger
        val chargeMinutes = kwh / (c.powerKw * fastChargeFactor) * 60.0
        return Solution(
            kind = kind,
            placeName = c.name,
            location = c.location,
            amount = kwh,
            addKm = kwh * eff,
            extraMinutes = rc.detourMinutes + chargeMinutes,
            fillMinutes = chargeMinutes,
            costWon = kwh * c.pricePerKwh,
            unitPrice = c.pricePerKwh,
            powerKw = c.powerKw,
            available = c.available,
            total = c.total,
            address = c.address,
            statusAgeMinutes = c.statusAgeMinutes,
            detourMinutes = rc.detourMinutes,
        )
    }

    private fun planIce(
        snapshot: EnergySnapshot,
        judgement: Judgement,
        deadline: ZonedDateTime?,
        stations: List<FuelStation>,
    ): ChargePlan {
        val eff = judge.correctedEfficiency(snapshot)
        val liters = min(roundUp(judgement.shortfallKm / eff, 5.0), snapshot.headroom)
        fun toSolution(kind: SolutionKind, s: FuelStation) = Solution(
            kind = kind,
            placeName = s.name,
            location = s.location,
            amount = liters,
            addKm = liters * eff,
            extraMinutes = s.detourMinutes + fuelingMinutes,
            fillMinutes = fuelingMinutes,
            costWon = liters * s.pricePerLiter,
            unitPrice = s.pricePerLiter,
            address = s.address,
            detourMinutes = s.detourMinutes,
        )
        val onRoute = stations.filter { it.detourMinutes <= onRouteMaxDetourMinutes }
            .map { toSolution(SolutionKind.ON_ROUTE, it) }
            .sortedWith(compareBy({ it.unitPrice }, { it.extraMinutes }))
        val detour = stations.filter { it.detourMinutes > onRouteMaxDetourMinutes }
            .map { toSolution(SolutionKind.DETOUR, it) }
            .sortedWith(compareBy({ it.extraMinutes }, { it.unitPrice }))
        return ChargePlan(
            fuelType = FuelType.ICE,
            amount = liters,
            percent = liters / snapshot.capacity * 100.0,
            addKm = liters * eff,
            deadline = deadline,
            solutions = onRoute + detour,
            averageUnitPrice = stations.map { it.pricePerLiter }.takeIf { it.isNotEmpty() }?.average(),
        )
    }

    private fun roundUp(value: Double, step: Double): Double = ceil(value / step - 1e-9) * step
}
