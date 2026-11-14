package kr.mobility.mirichaeum

import kr.mobility.core.geo.GeoPoint
import kr.mobility.core.geo.PlaceCell
import java.time.Duration
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

enum class FuelType {
    EV,

    /** 내연기관·하이브리드. 주유로 채운다. */
    ICE,
}

/**
 * 지금 차의 에너지 상태. 앱 쪽에서 Vehicle SDK 값으로 채운다.
 * - EV: [levelPercent] = EvBattery 충전 상태, [efficiency] = 누적 전비(km/kWh), [capacity] = 배터리 용량(kWh)
 * - ICE: [levelPercent] = Fuel 잔량 %, [efficiency] = 누적 연비(km/L), [capacity] = 연료 탱크(L)
 */
data class EnergySnapshot(
    val fuelType: FuelType,
    val rangeKm: Double,
    val levelPercent: Double,
    val efficiency: Double,
    val capacity: Double,
    val outsideTempC: Double?,
    val at: ZonedDateTime,
    val odometerKm: Double? = null,
) {
    init {
        require(rangeKm >= 0) { "rangeKm < 0" }
        require(levelPercent in 0.0..100.0) { "levelPercent out of range: $levelPercent" }
        require(efficiency > 0) { "efficiency must be > 0" }
        require(capacity > 0) { "capacity must be > 0" }
    }

    /** 가득 채울 때까지 더 넣을 수 있는 양(kWh 또는 L). */
    val headroom: Double get() = capacity * (100.0 - levelPercent) / 100.0
}

/** 운행 1회 = 시동 켜짐부터 꺼짐까지 (R-01). 위치는 동네 단위 칸으로만 남긴다. */
data class TripRecord(
    val start: ZonedDateTime,
    val end: ZonedDateTime,
    val from: PlaceCell,
    val to: PlaceCell,
    val distanceKm: Double,
    val outsideTempC: Double? = null,
)

/** 도착부터 다음 출발까지 머문 기록 (F-02). */
data class DwellRecord(
    val place: PlaceCell,
    val arrive: ZonedDateTime,
    val depart: ZonedDateTime,
) {
    val minutes: Double get() = Duration.between(arrive, depart).toMinutes().toDouble()
}

enum class DistanceSource {
    /** NaviHelper 경로 탐색. */
    NAVI,

    /** 카카오 길찾기(시연 한정). */
    KAKAO,

    /** 직선거리 × 1.3. */
    STRAIGHT,
}

/** 일정을 어떻게 넣었는지. */
enum class TripEntry {
    /** 말로 등록 (F-04). */
    VOICE,

    /** 주차 중 캘린더에서 터치로 등록 (F-23). */
    TOUCH,
}

/**
 * 이동 일정 (F-04, F-23, R-05). 말 또는 캘린더 터치로 등록하고, 날짜·시각·장소만 저장한다.
 * [weekly]면 [at]부터 매주 같은 요일·시각에 반복한다 (F-24).
 */
data class VoiceTrip(
    val id: String,
    val at: ZonedDateTime,
    val placeQuery: String,
    val placeName: String?,
    val destination: GeoPoint?,
    val purpose: String? = null,
    val oneWayKm: Double? = null,
    val distanceSource: DistanceSource? = null,
    val weekly: Boolean = false,
    val entry: TripEntry = TripEntry.VOICE,
) {
    /** 장소를 아직 고르지 않았으면(정차 후 선택) 거리를 알 수 없다. */
    val roundTripKm: Double? get() = oneWayKm?.let { it * 2 }

    val label: String get() = placeName ?: placeQuery

    /** [from] 이상 [until] 미만의 일정 시각. 한 번짜리는 0개 또는 1개. */
    fun occurrences(from: ZonedDateTime, until: ZonedDateTime): List<ZonedDateTime> {
        if (!weekly) return if (!at.isBefore(from) && at.isBefore(until)) listOf(at) else emptyList()
        var t = at
        if (t.isBefore(from)) {
            t = t.plusWeeks(ChronoUnit.WEEKS.between(t, from))
            if (t.isBefore(from)) t = t.plusWeeks(1)
        }
        return generateSequence(t) { it.plusWeeks(1) }.takeWhile { it.isBefore(until) }.toList()
    }

    /** [from] 이후 첫 일정 시각. 지난 한 번짜리 일정이면 null. */
    fun nextAt(from: ZonedDateTime): ZonedDateTime? = when {
        !weekly -> at.takeIf { !it.isBefore(from) }
        else -> occurrences(from, from.plusWeeks(1).plusSeconds(1)).firstOrNull()
    }
}

enum class Verdict {
    /** 초록: 충분. */
    ENOUGH,

    /** 노랑: 빠듯. 갈 수는 있지만 여유분이 모자람. */
    TIGHT,

    /** 빨강: 부족. 이대로는 못 감. */
    SHORT,
}
