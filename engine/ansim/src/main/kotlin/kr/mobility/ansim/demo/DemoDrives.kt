package kr.mobility.ansim.demo

import kr.mobility.ansim.CarConditions
import kr.mobility.ansim.CautionAhead
import kr.mobility.ansim.CautionKind
import kr.mobility.ansim.Congestion
import kr.mobility.ansim.CongestionSegment
import kr.mobility.ansim.IncidentAhead
import kr.mobility.ansim.IncidentKind
import kr.mobility.ansim.RoadSnapshot
import kr.mobility.core.geo.GeoPoint
import kr.mobility.core.time.KST
import java.time.ZonedDateTime

/** 시연 모드(F-10)용 가상 주행. 10초마다 한 장면, 시속 약 60km(장면당 약 170m)로 다가간다. */
data class DemoDrive(val id: String, val title: String, val frames: List<RoadSnapshot>)

object DemoDrives {
    private const val STEP_SECONDS = 10L
    private const val STEP_METERS = 170
    private val somewhere = GeoPoint(37.55, 127.20)

    private fun drive(
        id: String,
        title: String,
        start: ZonedDateTime,
        steps: Int,
        build: (step: Int, at: ZonedDateTime, traveled: Int) -> RoadSnapshot,
    ) = DemoDrive(id, title, (0 until steps).map { build(it, start.plusSeconds(it * STEP_SECONDS), it * STEP_METERS) })

    /** S1. 비 오는 밤, 국도 내리막 → 빨강. 두 번째 장면에서 1.2km 앞 사고 다발 + 급경사 내리막이 들어온다. */
    val s1RainyNight = drive("S1", "비 오는 밤, 국도 내리막", ZonedDateTime.of(2026, 11, 20, 21, 0, 0, 0, KST), 10) { step, at, traveled ->
        val cautions = if (step == 0) emptyList() else listOf(
            CautionAhead("demo-ap-1", CautionKind.ACCIDENT_PRONE_AREA, 1200 + STEP_METERS - traveled),
            CautionAhead("demo-dh-1", CautionKind.DOWNHILL_CAUTION_AREA, 1250 + STEP_METERS - traveled),
        )
        RoadSnapshot(at, CarConditions(wiperOn = true, outsideTempC = 8.0, isNight = true), cautions = cautions)
    }

    /** S2. 안개 낀 새벽 → 노랑(야간 1 + 안개 구간 2 = 3). 음성 없음. */
    val s2FoggyDawn = drive("S2", "안개 낀 새벽", ZonedDateTime.of(2026, 11, 20, 5, 30, 0, 0, KST), 4) { _, at, traveled ->
        RoadSnapshot(
            at,
            CarConditions(outsideTempC = 6.0),
            cautions = listOf(CautionAhead("demo-fog-1", CautionKind.FOG_AREA, 800 - traveled)),
            location = somewhere,
        )
    }

    /** S3. 겨울 아침: 2℃ + 미끄러운 도로 → 노랑, 이어서 앞쪽이 갑자기 막히면 → 빨강. */
    val s3WinterMorning = drive("S3", "겨울 아침", ZonedDateTime.of(2026, 12, 15, 9, 0, 0, 0, KST), 6) { step, at, traveled ->
        val jam = if (step < 2) {
            listOf(CongestionSegment(Congestion.FAST, -500, 5000))
        } else {
            listOf(CongestionSegment(Congestion.FAST, -500, 900), CongestionSegment(Congestion.HEAVY, 900, 3000))
        }
        RoadSnapshot(
            at,
            CarConditions(outsideTempC = 2.0),
            cautions = listOf(CautionAhead("demo-slip-1", CautionKind.SLIPPERY_ROAD_ALERT, 1500 - traveled)),
            congestion = jam,
            location = somewhere,
        )
    }

    /** S4. 돌발 상황: 1.5km 앞 사고 + 정체 → 빨강. */
    val s4Incident = drive("S4", "돌발 사고", ZonedDateTime.of(2026, 11, 20, 14, 0, 0, 0, KST), 5) { _, at, traveled ->
        RoadSnapshot(
            at,
            CarConditions(outsideTempC = 12.0),
            incidents = listOf(IncidentAhead(IncidentKind.ACCIDENT, 1500 - traveled, id = "demo-acc-1")),
            congestion = listOf(
                CongestionSegment(Congestion.FAST, -300, 1400 - traveled),
                CongestionSegment(Congestion.SLOW, 1400 - traveled, 2500),
            ),
            location = somewhere,
        )
    }

    /** S5. 평소 낮 → 초록. 과속방지턱·단속 카메라는 점수에 넣지 않는다. */
    val s5Normal = drive("S5", "평소 낮", ZonedDateTime.of(2026, 11, 20, 13, 0, 0, 0, KST), 3) { _, at, traveled ->
        RoadSnapshot(
            at,
            CarConditions(outsideTempC = 15.0),
            cautions = listOf(
                CautionAhead("demo-bump-1", CautionKind.SPEED_BUMP, 600 - traveled),
                CautionAhead("demo-cam-1", CautionKind.TRAFFIC_LIGHT_VIOLATION_ENFORCEMENT, 900 - traveled),
            ),
            location = somewhere,
        )
    }

    val all: List<DemoDrive> = listOf(s1RainyNight, s2FoggyDawn, s3WinterMorning, s4Incident, s5Normal)
}
