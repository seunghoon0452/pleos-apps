package kr.mobility.ansim

import kr.mobility.core.geo.Geo
import kr.mobility.core.geo.GeoPoint
import kr.mobility.core.time.SunCalculator
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZonedDateTime
import kotlin.math.abs

/** 어린이보호구역 +1점을 주는 등하교 시간(가안): 평일 07:30~09:00, 12:00~16:30. */
object SchoolHours {
    fun isActive(at: ZonedDateTime): Boolean {
        if (at.dayOfWeek == DayOfWeek.SATURDAY || at.dayOfWeek == DayOfWeek.SUNDAY) return false
        val t = at.toLocalTime()
        return t in LocalTime.of(7, 30)..LocalTime.of(9, 0) || t in LocalTime.of(12, 0)..LocalTime.of(16, 30)
    }
}

/**
 * 위험 요인 뽑기 (R-01 ~ R-04).
 * 같은 종류는 가장 가까운 하나만 센다. 도로 파손·공사는 주의 지점과 돌발 정보에 같이 올 수 있어서 한 번만 센다.
 */
class RiskScorer(
    private val rangeMeters: Int = 2000,
    private val icyTempC: Double = 3.0,
    private val schoolHours: (ZonedDateTime) -> Boolean = SchoolHours::isActive,
) {
    private val cautionTable: Map<CautionKind, Pair<FactorType, Int>> = mapOf(
        CautionKind.WRONG_WAY_DRIVING_ALERT to (FactorType.WRONG_WAY to 4),
        CautionKind.ACCIDENT_PRONE_AREA to (FactorType.ACCIDENT_PRONE to 3),
        CautionKind.SLIPPERY_ROAD_ALERT to (FactorType.SLIPPERY to 2),
        CautionKind.FOG_AREA to (FactorType.FOG_AREA to 2),
        CautionKind.DOWNHILL_CAUTION_AREA to (FactorType.DOWNHILL to 2),
        CautionKind.ROAD_DAMAGE_ALERT to (FactorType.ROAD_DAMAGE_AREA to 2),
        // 정의서 표에는 없지만 돌발 '공사'와 같은 점수로 둔다(가안).
        CautionKind.HIGHWAY_CONSTRUCTION to (FactorType.CONSTRUCTION_AREA to 2),
        CautionKind.FALLING_ROCK_AREA to (FactorType.FALLING_ROCK to 1),
        CautionKind.WILDLIFE_CROSSING to (FactorType.WILDLIFE to 1),
        CautionKind.NARROW_ROAD to (FactorType.NARROW to 1),
        CautionKind.RAILROAD_CROSSING to (FactorType.RAILROAD to 1),
        CautionKind.SCHOOL_ZONE to (FactorType.SCHOOL_ZONE to 1),
    )

    private val incidentTable: Map<IncidentKind, Pair<FactorType, Int>> = mapOf(
        IncidentKind.DISASTER to (FactorType.DISASTER to 4),
        IncidentKind.ACCIDENT to (FactorType.ACCIDENT to 3),
        IncidentKind.REPAIRING to (FactorType.ROADWORK to 2),
        IncidentKind.ROAD_DAMAGE to (FactorType.ROAD_DAMAGE to 2),
    )

    /** 같은 것으로 보는 묶음. */
    private fun family(type: FactorType): String = when (type) {
        FactorType.ROAD_DAMAGE_AREA, FactorType.ROAD_DAMAGE -> "ROAD_DAMAGE"
        FactorType.CONSTRUCTION_AREA, FactorType.ROADWORK -> "CONSTRUCTION"
        else -> type.name
    }

    private fun inRange(distance: Int) = distance in 0..rangeMeters

    fun factors(s: RoadSnapshot): List<RiskFactor> {
        val road = s.cautions.filter { inRange(it.distanceMeters) }.mapNotNull { c ->
            val (type, base) = cautionTable[c.kind] ?: return@mapNotNull null
            val points = if (type == FactorType.SCHOOL_ZONE && schoolHours(s.at)) base + 1 else base
            RiskFactor(type, FactorGroup.ROAD, points, c.distanceMeters, key = "caution:${c.id}")
        }
        val incidents = s.incidents.filter { inRange(it.distanceMeters) }.mapNotNull { i ->
            val (type, points) = incidentTable[i.kind] ?: return@mapNotNull null
            RiskFactor(type, FactorGroup.INCIDENT, points, i.distanceMeters, key = "incident:${i.id}")
        }
        val jam = suddenJam(s.congestion)?.let {
            RiskFactor(FactorType.SUDDEN_JAM, FactorGroup.INCIDENT, 2, it, key = "jam")
        }
        val ahead = (road + incidents + listOfNotNull(jam))
            .groupBy { family(it.type) }
            .map { (_, list) -> list.sortedWith(compareByDescending<RiskFactor> { it.points }.thenBy { it.distanceMeters }).first() }

        val car = s.car
        val night = car.isNight ?: s.location?.let { SunCalculator.isNight(s.at, it) } ?: false
        val env = buildList {
            if (car.wiperOn) add(RiskFactor(FactorType.RAIN, FactorGroup.CAR, 2))
            if (night) add(RiskFactor(FactorType.NIGHT, FactorGroup.CAR, 1))
            if (car.outsideTempC != null && car.outsideTempC <= icyTempC) add(RiskFactor(FactorType.ICY, FactorGroup.CAR, 1))
            if (car.fogLightsOn) add(RiskFactor(FactorType.FOG_LIGHTS, FactorGroup.CAR, 1))
            if (car.washerFluidLow) add(RiskFactor(FactorType.WASHER_LOW, FactorGroup.CAR, 1))
        }
        return ahead.sortedBy { it.distanceMeters } + env
    }

    /**
     * 원활하다가 갑자기 막히는 곳: 지금 구간보다 2단계 이상 느린 구간이 앞 2km 안에서 시작하면 그 시작 거리.
     * (원활 → 지체·정체, 서행 → 정체)
     */
    fun suddenJam(segments: List<CongestionSegment>): Int? {
        // 혼잡 정보는 막히는 구간만 오는 경우가 많아서, 지금 위치를 덮는 구간이 없으면 원활로 본다(가안).
        val currentLevel = segments.filter { it.startMeters <= 0 && it.endMeters > 0 }
            .maxByOrNull { it.startMeters }?.type?.level ?: Congestion.FAST.level
        if (currentLevel < 0) return null
        return segments
            .filter { it.startMeters in 1..rangeMeters && it.type.level - currentLevel >= 2 }
            .minOfOrNull { it.startMeters }
    }
}

/** 민감도 설정(F-09). 기본값은 정의서 R-05: 3~4 노랑, 5 이상 빨강. */
enum class Sensitivity(val yellowFrom: Int, val redFrom: Int) {
    LOW(4, 6),
    NORMAL(3, 5),
    HIGH(2, 4),
}

/** R-05: 점수 → 신호등. 앞길·돌발 요인이 하나도 없으면 최대 노랑. */
class SignalJudge(
    private val scorer: RiskScorer = RiskScorer(),
    private val sensitivity: Sensitivity = Sensitivity.NORMAL,
) {
    fun assess(snapshot: RoadSnapshot): Assessment {
        val factors = scorer.factors(snapshot)
        val score = factors.sumOf { it.points }
        val raw = when {
            score >= sensitivity.redFrom -> Signal.RED
            score >= sensitivity.yellowFrom -> Signal.YELLOW
            else -> Signal.GREEN
        }
        val hasAhead = factors.any { it.group != FactorGroup.CAR }
        val capped = raw == Signal.RED && !hasAhead
        return Assessment(snapshot.at, if (capped) Signal.YELLOW else raw, score, factors, capped)
    }
}

/** SDK 돌발 정보(TrafficEventInfo)는 위도·경도만 주므로, 진행 방향 앞쪽에 있는지와 거리를 구한다. */
object IncidentLocator {
    fun distanceAhead(
        current: GeoPoint,
        headingDegrees: Double,
        target: GeoPoint,
        maxMeters: Double = 2000.0,
        coneDegrees: Double = 45.0,
    ): Int? {
        val d = Geo.distanceMeters(current, target)
        if (d > maxMeters) return null
        val diff = abs(((Geo.bearingDegrees(current, target) - headingDegrees + 540.0) % 360.0) - 180.0)
        return if (diff <= coneDegrees) d.toInt() else null
    }

    /** SDK 혼잡 정보(TrafficInfo)의 경로 비율(%)을 지금 위치 기준 거리로 바꾼다. */
    fun segmentFromRatios(
        type: Congestion,
        startRatio: Int,
        endRatio: Int,
        routeLengthMeters: Int,
        traveledMeters: Int,
    ): CongestionSegment = CongestionSegment(
        type,
        startMeters = routeLengthMeters * startRatio / 100 - traveledMeters,
        endMeters = routeLengthMeters * endRatio / 100 - traveledMeters,
    )
}
