package kr.mobility.mirichaeum

import kr.mobility.core.geo.Geo
import kr.mobility.core.geo.GeoPoint
import kr.mobility.core.geo.PlaceCell
import java.time.Duration
import java.time.ZonedDateTime

/** 기어가 P에 들어가거나 P에서 나올 때 앱이 넘기는 값. 값이 없으면 null. */
data class GearEvent(
    val at: ZonedDateTime,
    val parked: Boolean,
    val location: GeoPoint?,
    val odometerKm: Double?,
    val outsideTempC: Double? = null,
)

data class OpenTrip(
    val start: ZonedDateTime,
    val from: PlaceCell?,
    val fromPoint: GeoPoint?,
    val startOdometerKm: Double?,
    val outsideTempC: Double?,
)

/** 막 주차한 상태. 짧게 섰다가 다시 출발하면 같은 운행으로 잇기 위해 [pending]을 잠시 들고 있는다. */
data class ParkedInfo(
    val at: ZonedDateTime,
    val place: PlaceCell?,
    val point: GeoPoint?,
    val odometerKm: Double?,
    val pending: OpenTrip?,
)

data class RecorderState(val open: OpenTrip? = null, val parked: ParkedInfo? = null)

/**
 * 운행·체류 기록기 (F-01, F-02, R-01).
 * - 운행 1회 = P에서 나와서 다시 P에 들어갈 때까지. 3분 안에 다시 출발하면 같은 운행으로 본다(가안).
 * - 거리는 총 주행거리 차이, 없으면 직선거리 × 1.3.
 * - 위치는 300m 칸으로만 남긴다(R-14). 상태는 앱이 저장했다가 다시 넘긴다.
 */
class TripRecorder(
    private val mergeWithin: Duration = Duration.ofMinutes(3),
    private val minTripKm: Double = 0.3,
) {
    data class Output(val state: RecorderState, val trips: List<TripRecord> = emptyList(), val dwells: List<DwellRecord> = emptyList())

    fun handle(state: RecorderState, e: GearEvent): Output = if (e.parked) park(state, e) else depart(state, e)

    /** 시간이 흘렀음을 알린다. 주차 후 3분이 지났으면 그 운행을 확정한다. */
    fun tick(state: RecorderState, now: ZonedDateTime): Output {
        val parked = state.parked ?: return Output(state)
        val pending = parked.pending ?: return Output(state)
        if (Duration.between(parked.at, now) < mergeWithin) return Output(state)
        return Output(state.copy(parked = parked.copy(pending = null)), trips = listOfNotNull(close(pending, parked)))
    }

    private fun park(state: RecorderState, e: GearEvent): Output {
        val open = state.open ?: return Output(state)
        val parked = ParkedInfo(e.at, e.location?.let { PlaceCell.of(it) }, e.location, e.odometerKm, pending = open)
        return Output(RecorderState(open = null, parked = parked))
    }

    private fun depart(state: RecorderState, e: GearEvent): Output {
        if (state.open != null) return Output(state)
        val parked = state.parked
        if (parked?.pending != null && Duration.between(parked.at, e.at) < mergeWithin) {
            return Output(RecorderState(open = parked.pending, parked = null))
        }
        val trips = listOfNotNull(parked?.pending?.let { close(it, parked) })
        val dwells = listOfNotNull(
            parked?.place?.let { place -> DwellRecord(place, parked.at, e.at).takeIf { it.minutes > 0 } },
        )
        val open = OpenTrip(e.at, e.location?.let { PlaceCell.of(it) }, e.location, e.odometerKm, e.outsideTempC)
        return Output(RecorderState(open = open, parked = null), trips, dwells)
    }

    private fun close(open: OpenTrip, parked: ParkedInfo): TripRecord? {
        val from = open.from ?: return null
        val to = parked.place ?: return null
        val km = if (open.startOdometerKm != null && parked.odometerKm != null) {
            parked.odometerKm - open.startOdometerKm
        } else if (open.fromPoint != null && parked.point != null) {
            Geo.estimatedRoadKm(open.fromPoint, parked.point)
        } else {
            return null
        }
        if (km < minTripKm) return null
        return TripRecord(open.start, parked.at, from, to, km, open.outsideTempC)
    }
}
