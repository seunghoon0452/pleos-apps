package kr.mobility.ansim.app

import kr.mobility.ansim.CarConditions
import kr.mobility.ansim.CautionAhead
import kr.mobility.ansim.CautionKind
import kr.mobility.ansim.Congestion
import kr.mobility.ansim.CongestionSegment
import kr.mobility.ansim.IncidentAhead
import kr.mobility.ansim.IncidentKind
import kr.mobility.ansim.IncidentLocator
import kr.mobility.ansim.RoadSnapshot
import kr.mobility.ansim.Signal
import kr.mobility.core.geo.GeoPoint
import kr.mobility.core.ui.Tone
import kr.mobility.core.vehicle.DrivingState
import kr.mobility.platform.pleos.LocationFix
import kr.mobility.platform.pleos.NaviState
import kr.mobility.platform.pleos.VehicleReading
import java.time.ZonedDateTime

/** SDK에서 받은 값 → 엔진 입력. 매 순간 새로 만들고 저장하지 않는다(R-09). */
object RoadMapping {
    fun snapshot(now: ZonedDateTime, reading: VehicleReading, navi: NaviState, fix: LocationFix?): RoadSnapshot {
        val position = navi.position?.let { GeoPoint(it.lat, it.lng) to it.heading }
            ?: fix?.let { GeoPoint(it.lat, it.lng) to (it.heading ?: 0.0) }
        val incidents = position?.let { (point, heading) ->
            navi.incidents.mapNotNull { i ->
                IncidentLocator.distanceAhead(point, heading, GeoPoint(i.lat, i.lng))?.let { d ->
                    IncidentAhead(IncidentKind.fromSdkName(i.type), d, id = "${i.lat},${i.lng}", description = i.description)
                }
            }
        } ?: emptyList()
        return RoadSnapshot(
            at = now,
            car = CarConditions(
                wiperOn = reading.wiperOn,
                fogLightsOn = reading.frontFogLightOn,
                washerFluidLow = reading.washerFluidLow,
                outsideTempC = reading.outsideTempC,
                isNight = null,
            ),
            cautions = navi.cautions.map { CautionAhead(it.id, CautionKind.fromSdkName(it.type), it.remainMeters) },
            incidents = incidents,
            // 가안: SDK의 offset을 지금 위치부터의 거리로 본다. 에뮬레이터 경로 안내로 확인 필요.
            congestion = navi.congestion.map {
                CongestionSegment(Congestion.fromSdkName(it.type), it.offsetMeters.toInt(), (it.offsetMeters + it.lengthMeters).toInt())
            },
            routeGuidance = navi.routeGuidance,
            location = position?.first,
        )
    }

    fun state(reading: VehicleReading): DrivingState =
        DrivingState.from(reading.gear, reading.speedKmh ?: 0.0, reading.parkingBrake == true, reading.charging == true)

    fun tone(signal: Signal?): Tone? = when (signal) {
        Signal.GREEN -> Tone.GOOD
        Signal.YELLOW -> Tone.WARN
        Signal.RED -> Tone.DANGER
        null -> null
    }
}
