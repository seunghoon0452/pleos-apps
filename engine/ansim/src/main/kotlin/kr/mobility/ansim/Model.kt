package kr.mobility.ansim

import kr.mobility.core.geo.GeoPoint
import java.time.ZonedDateTime

/**
 * 앞길 주의 지점 종류. NaviHelper SDK의 CautionPointType과 이름을 똑같이 맞춰서
 * 앱 쪽에서 `CautionKind.valueOf(sdk.name)`으로 바로 바꿀 수 있게 했다(모르는 값은 OTHER).
 */
enum class CautionKind {
    SPEED_BUMP,
    SCHOOL_ZONE,
    ACCIDENT_PRONE_AREA,
    RAILROAD_CROSSING,
    FOG_AREA,
    FALLING_ROCK_AREA,
    DOWNHILL_CAUTION_AREA,
    WILDLIFE_CROSSING,
    NARROW_ROAD,
    HIGHWAY_CONSTRUCTION,
    TRAFFIC_INFO_COLLECTION,
    NO_PARKING_ZONE,
    TRAFFIC_LIGHT_VIOLATION_ENFORCEMENT,
    BUS_LANE_VIOLATION_ENFORCEMENT,
    CUT_IN_VIOLATION_ENFORCEMENT,
    BLOCK_INTERSECTION_ENFORCEMENT,
    LANE_CHANGE_CAMERA_ALERT,
    SLIPPERY_ROAD_ALERT,
    ROAD_DAMAGE_ALERT,
    WRONG_WAY_DRIVING_ALERT,
    BUS_LANE_UNAVAILABLE_NOTICE,
    BUS_LANE_AVAILABLE_NOTICE,
    SIGN_MALFUNCION,
    OTHER;

    companion object {
        fun fromSdkName(name: String): CautionKind = entries.firstOrNull { it.name == name } ?: OTHER
    }
}

/** NaviHelper TrafficEventType과 같은 이름. */
enum class IncidentKind {
    ETC, ACCIDENT, ROAD_DAMAGE, REPAIRING, EVENT, DISASTER;

    companion object {
        fun fromSdkName(name: String): IncidentKind = entries.firstOrNull { it.name == name } ?: ETC
    }
}

/** NaviHelper TrafficCongestionType과 같은 이름. 원활 → 서행 → 지체 → 정체 순. */
enum class Congestion(val level: Int) {
    UNKNOWN(-1), FAST(0), MODERATE(1), SLOW(2), HEAVY(3);

    companion object {
        fun fromSdkName(name: String): Congestion = entries.firstOrNull { it.name == name } ?: UNKNOWN
    }
}

/** 앞 주의 지점 하나. [distanceMeters]는 SDK의 remainDistance. */
data class CautionAhead(val id: String, val kind: CautionKind, val distanceMeters: Int)

/**
 * 앞 돌발 상황 하나. SDK는 위치만 주므로 거리는 [IncidentLocator]로 구한다.
 * [id]는 다가가는 동안 바뀌지 않아야 한다(앱에서는 위도·경도로 만든다).
 */
data class IncidentAhead(
    val kind: IncidentKind,
    val distanceMeters: Int,
    val id: String = kind.name,
    val description: String? = null,
)

/** 앞쪽 혼잡 구간. 지금 위치 기준 시작·끝 거리(m). 지금 있는 구간은 start ≤ 0. */
data class CongestionSegment(val type: Congestion, val startMeters: Int, val endMeters: Int)

/** 내 차가 아는 상황 (Vehicle SDK + 표준 차량 데이터). */
data class CarConditions(
    val wiperOn: Boolean = false,
    val fogLightsOn: Boolean = false,
    val washerFluidLow: Boolean = false,
    val outsideTempC: Double? = null,
    /** 모르면 null. 위치가 있으면 엔진이 해 지는 시각으로 계산한다. */
    val isNight: Boolean? = null,
)

/** 한 순간의 입력 전체. 앱은 SDK 이벤트가 올 때마다 이것을 새로 만든다. 저장하지 않는다(R-09). */
data class RoadSnapshot(
    val at: ZonedDateTime,
    val car: CarConditions,
    val cautions: List<CautionAhead> = emptyList(),
    val incidents: List<IncidentAhead> = emptyList(),
    val congestion: List<CongestionSegment> = emptyList(),
    /** 경로 안내 중이면 true, 자유 주행이면 false (R-01). */
    val routeGuidance: Boolean = true,
    val location: GeoPoint? = null,
)

enum class Signal { GREEN, YELLOW, RED }

enum class FactorGroup {
    /** 앞길 주의 지점. */
    ROAD,

    /** 돌발 상황·급정체. */
    INCIDENT,

    /** 내 차·환경. */
    CAR,
}

enum class FactorType {
    // 앞길 (R-02)
    WRONG_WAY, ACCIDENT_PRONE, SLIPPERY, FOG_AREA, DOWNHILL, ROAD_DAMAGE_AREA, CONSTRUCTION_AREA,
    FALLING_ROCK, WILDLIFE, NARROW, RAILROAD, SCHOOL_ZONE,

    // 돌발 (R-03)
    DISASTER, ACCIDENT, ROADWORK, ROAD_DAMAGE, SUDDEN_JAM,

    // 내 차·환경 (R-04)
    RAIN, NIGHT, ICY, FOG_LIGHTS, WASHER_LOW,
}

data class RiskFactor(
    val type: FactorType,
    val group: FactorGroup,
    val points: Int,
    /** 앞 몇 m. 내 차·환경 요인은 null. */
    val distanceMeters: Int? = null,
    /** 같은 지점을 다시 말하지 않기 위한 표식(SDK id 또는 종류+거리). */
    val key: String = type.name,
)

data class Assessment(
    val at: ZonedDateTime,
    val signal: Signal,
    val score: Int,
    val factors: List<RiskFactor>,
    /** 앞길·돌발 요인이 없어서 빨강을 노랑으로 낮췄는지 (R-05 겹침 원칙). */
    val capped: Boolean,
) {
    /** 이유 문장의 주인공: 앞길·돌발 요인 중 점수가 높고 가까운 것. */
    val lead: RiskFactor?
        get() = factors.filter { it.group != FactorGroup.CAR }
            .sortedWith(compareByDescending<RiskFactor> { it.points }.thenBy { it.distanceMeters ?: Int.MAX_VALUE })
            .firstOrNull()

    fun has(type: FactorType): Boolean = factors.any { it.type == type }
}
