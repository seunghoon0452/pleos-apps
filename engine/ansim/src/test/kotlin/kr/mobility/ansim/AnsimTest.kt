package kr.mobility.ansim

import kr.mobility.ansim.demo.DemoDrive
import kr.mobility.ansim.demo.DemoDrives
import kr.mobility.core.geo.GeoPoint
import kr.mobility.core.time.KST
import kr.mobility.core.vehicle.DrivingState
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val noon = ZonedDateTime.of(2026, 11, 20, 12, 0, 0, 0, KST) // 금요일

private fun snap(
    car: CarConditions = CarConditions(isNight = false),
    cautions: List<CautionAhead> = emptyList(),
    incidents: List<IncidentAhead> = emptyList(),
    congestion: List<CongestionSegment> = emptyList(),
    at: ZonedDateTime = noon,
) = RoadSnapshot(at, car, cautions, incidents, congestion)

private fun caution(kind: CautionKind, d: Int, id: String = kind.name) = CautionAhead(id, kind, d)

/** 정의서 6장 R-01 ~ R-05 */
class SignalJudgeTest {
    private val judge = SignalJudge()

    @Test
    fun `점수 경계 - 0~2 초록, 3~4 노랑, 5 이상 빨강`() {
        assertEquals(Signal.GREEN, judge.assess(snap(cautions = listOf(caution(CautionKind.FOG_AREA, 500)))).signal)
        assertEquals(Signal.YELLOW, judge.assess(snap(cautions = listOf(caution(CautionKind.ACCIDENT_PRONE_AREA, 500)))).signal)
        val red = judge.assess(
            snap(cautions = listOf(caution(CautionKind.ACCIDENT_PRONE_AREA, 500), caution(CautionKind.FOG_AREA, 700))),
        )
        assertEquals(Signal.RED, red.signal)
        assertEquals(5, red.score)
    }

    @Test
    fun `앞 2km 밖이나 이미 지난 지점은 세지 않는다`() {
        val a = judge.assess(snap(cautions = listOf(caution(CautionKind.WRONG_WAY_DRIVING_ALERT, 2001), caution(CautionKind.ACCIDENT_PRONE_AREA, -10))))
        assertEquals(0, a.score)
    }

    @Test
    fun `같은 종류는 가장 가까운 하나만, 도로 파손은 주의 지점과 돌발 중 하나만`() {
        val a = judge.assess(
            snap(
                cautions = listOf(
                    caution(CautionKind.ACCIDENT_PRONE_AREA, 900, "a"),
                    caution(CautionKind.ACCIDENT_PRONE_AREA, 300, "b"),
                    caution(CautionKind.ROAD_DAMAGE_ALERT, 400),
                ),
                incidents = listOf(IncidentAhead(IncidentKind.ROAD_DAMAGE, 420)),
            ),
        )
        assertEquals(5, a.score)
        assertEquals(300, a.factors.first { it.type == FactorType.ACCIDENT_PRONE }.distanceMeters)
    }

    @Test
    fun `앞길 요인이 없으면 최대 노랑 - 겹침 원칙`() {
        val car = CarConditions(wiperOn = true, isNight = true, outsideTempC = 1.0, fogLightsOn = true, washerFluidLow = true)
        val a = judge.assess(snap(car = car))
        assertEquals(6, a.score)
        assertEquals(Signal.YELLOW, a.signal)
        assertTrue(a.capped)
    }

    @Test
    fun `어린이보호구역은 등하교 시간에 1점 더`() {
        val school = listOf(caution(CautionKind.SCHOOL_ZONE, 300))
        val morning = ZonedDateTime.of(2026, 11, 20, 8, 0, 0, 0, KST)
        val evening = ZonedDateTime.of(2026, 11, 20, 19, 0, 0, 0, KST)
        val saturday = ZonedDateTime.of(2026, 11, 21, 8, 0, 0, 0, KST)
        assertEquals(2, judge.assess(snap(cautions = school, at = morning)).score)
        assertEquals(1, judge.assess(snap(cautions = school, at = evening)).score)
        assertEquals(1, judge.assess(snap(cautions = school, at = saturday)).score)
    }

    @Test
    fun `급정체 - 원활에서 2단계 이상 느려지는 곳이 2km 안에서 시작`() {
        val scorer = RiskScorer()
        assertEquals(900, scorer.suddenJam(listOf(CongestionSegment(Congestion.FAST, -100, 900), CongestionSegment(Congestion.HEAVY, 900, 2000))))
        assertNull(scorer.suddenJam(listOf(CongestionSegment(Congestion.FAST, -100, 900), CongestionSegment(Congestion.MODERATE, 900, 2000))))
        assertNull(scorer.suddenJam(listOf(CongestionSegment(Congestion.SLOW, -100, 900), CongestionSegment(Congestion.HEAVY, 900, 2000))))
        assertEquals(1200, scorer.suddenJam(listOf(CongestionSegment(Congestion.MODERATE, -100, 1200), CongestionSegment(Congestion.HEAVY, 1200, 2000))))
        assertNull(scorer.suddenJam(listOf(CongestionSegment(Congestion.FAST, -100, 2500), CongestionSegment(Congestion.HEAVY, 2500, 3000))))
        // 막히는 구간만 온 경우: 지금 위치는 원활로 본다.
        assertEquals(700, scorer.suddenJam(listOf(CongestionSegment(Congestion.HEAVY, 700, 1500))))
        assertNull(scorer.suddenJam(listOf(CongestionSegment(Congestion.UNKNOWN, -10, 300), CongestionSegment(Congestion.HEAVY, 700, 1500))))
    }

    @Test
    fun `야간은 위치가 있으면 해 지는 시각으로 계산`() {
        val seoul = GeoPoint(37.5665, 126.9780)
        val night = RoadSnapshot(ZonedDateTime.of(2026, 11, 20, 19, 0, 0, 0, KST), CarConditions(), location = seoul)
        val day = night.copy(at = noon)
        assertTrue(judge.assess(night).has(FactorType.NIGHT))
        assertTrue(!judge.assess(day).has(FactorType.NIGHT))
    }

    @Test
    fun `민감도 설정`() {
        val one = snap(cautions = listOf(caution(CautionKind.SLIPPERY_ROAD_ALERT, 300)))
        assertEquals(Signal.GREEN, SignalJudge(sensitivity = Sensitivity.NORMAL).assess(one).signal)
        assertEquals(Signal.YELLOW, SignalJudge(sensitivity = Sensitivity.HIGH).assess(one).signal)
    }

    @Test
    fun `SDK 이름으로 바로 바꿀 수 있다`() {
        assertEquals(CautionKind.FOG_AREA, CautionKind.fromSdkName("FOG_AREA"))
        assertEquals(CautionKind.OTHER, CautionKind.fromSdkName("NEW_TYPE_2027"))
        assertEquals(IncidentKind.DISASTER, IncidentKind.fromSdkName("DISASTER"))
        assertEquals(Congestion.HEAVY, Congestion.fromSdkName("HEAVY"))
    }
}

/** R-06 */
class SignalAlertPolicyTest {
    private val judge = SignalJudge()
    private val policy = SignalAlertPolicy()
    private val redSnap = snap(cautions = listOf(caution(CautionKind.WRONG_WAY_DRIVING_ALERT, 900, "w1"), caution(CautionKind.FOG_AREA, 950)))
    private val yellowSnap = snap(cautions = listOf(caution(CautionKind.ACCIDENT_PRONE_AREA, 900)))

    @Test
    fun `빨강으로 바뀔 때만 말하고, 빨강이 이어지면 조용`() {
        val y = judge.assess(yellowSnap)
        val r = judge.assess(redSnap)
        val first = policy.decide(y, r, VoiceHistory(), DrivingState.DRIVING)
        assertEquals(VoiceAction.SPEAK, first.action)
        val next = policy.decide(r, judge.assess(redSnap.copy(at = noon.plusSeconds(10))), first.history, DrivingState.DRIVING)
        assertEquals(VoiceAction.SILENT, next.action)
        assertEquals(VoiceAction.SILENT, policy.decide(null, y, VoiceHistory(), DrivingState.DRIVING).action)
    }

    @Test
    fun `같은 구간은 다시 말하지 않고, 5분 안에는 다른 구간도 말하지 않는다`() {
        val y = judge.assess(yellowSnap)
        val r = judge.assess(redSnap)
        val spoken = policy.decide(y, r, VoiceHistory(), DrivingState.DRIVING).history
        val later = noon.plusMinutes(6)
        assertEquals(VoiceAction.SILENT, policy.decide(y, judge.assess(redSnap.copy(at = later)), spoken, DrivingState.DRIVING).action)
        val other = snap(cautions = listOf(caution(CautionKind.WRONG_WAY_DRIVING_ALERT, 900, "w2"), caution(CautionKind.FOG_AREA, 950)), at = noon.plusMinutes(3))
        assertEquals(VoiceAction.SILENT, policy.decide(y, judge.assess(other), spoken, DrivingState.DRIVING).action)
        assertEquals(VoiceAction.SPEAK, policy.decide(y, judge.assess(other.copy(at = later)), spoken, DrivingState.DRIVING).action)
    }

    @Test
    fun `내비 음성 중이거나 신호 대기면 기다렸다가 말한다`() {
        val session = AnsimSession()
        assertEquals(VoiceAction.SILENT, session.update(yellowSnap, DrivingState.DRIVING).decision.action)
        val waiting = session.update(redSnap, DrivingState.DRIVING, naviSpeaking = true)
        assertEquals(VoiceAction.WAIT, waiting.decision.action)
        assertNull(waiting.voiceLine)
        val stopped = session.update(redSnap.copy(at = noon.plusSeconds(5)), DrivingState.STOPPED)
        assertEquals(VoiceAction.WAIT, stopped.decision.action)
        val spoken = session.update(redSnap.copy(at = noon.plusSeconds(10)), DrivingState.DRIVING)
        assertEquals(VoiceAction.SPEAK, spoken.decision.action)
        assertEquals("900m 앞 역주행 차량 경고가 있어요. 속도를 줄이고 오른쪽 차로로 가세요.", spoken.voiceLine)
    }

    @Test
    fun `음성 끄기`() {
        val off = SignalAlertPolicy(voiceEnabled = false)
        assertEquals(VoiceAction.SILENT, off.decide(null, judge.assess(redSnap), VoiceHistory(), DrivingState.DRIVING).action)
    }
}

/** 정의서 5장 시나리오 S1~S6을 시연 주행으로 돌려본다. */
class AnsimScenarioTest {
    private fun run(drive: DemoDrive): Pair<List<Frame>, AnsimSession> {
        val session = AnsimSession()
        return drive.frames.map { session.update(it, DrivingState.DRIVING) } to session
    }

    private fun spoken(frames: List<Frame>) = frames.mapNotNull { it.voiceLine }

    @Test
    fun `S1 비 오는 밤 내리막 - 빨강 1회 안내`() {
        val (frames, session) = run(DemoDrives.s1RainyNight)
        assertEquals(Signal.YELLOW, frames[0].assessment.signal)
        assertEquals(Signal.RED, frames[1].assessment.signal)
        assertEquals(8, frames[1].assessment.score)
        assertEquals(
            listOf("1km 앞 사고가 잦은 내리막이에요. 비가 와서 미끄러워요. 속도를 줄이고 차간 거리를 넉넉히 두세요."),
            spoken(frames),
        )
        assertEquals("앞 1km 사고 잦은 구간 · 속도를 줄이세요", frames[1].screenLine)
        assertEquals(Signal.YELLOW, frames.last().assessment.signal) // 지나간 뒤
        assertEquals("오늘 주의 구간 2곳을 안전하게 지났어요.", session.endTrip())
    }

    @Test
    fun `S2 안개 낀 새벽 - 노랑, 화면만`() {
        val (frames, _) = run(DemoDrives.s2FoggyDawn)
        assertEquals(Signal.YELLOW, frames[0].assessment.signal)
        assertEquals(3, frames[0].assessment.score)
        assertEquals("앞 800m 안개 구간 · 안개등을 켜 보세요", frames[0].screenLine)
        assertTrue(spoken(frames).isEmpty())
    }

    @Test
    fun `S3 겨울 아침 - 노랑에서 급정체로 빨강`() {
        val (frames, _) = run(DemoDrives.s3WinterMorning)
        assertEquals(listOf(Signal.YELLOW, Signal.YELLOW, Signal.RED), frames.take(3).map { it.assessment.signal })
        assertEquals(listOf("앞에 갑자기 막히는 구간이 있어요. 노면이 얼었을 수 있어요."), spoken(frames))
    }

    @Test
    fun `S4 돌발 사고 - 빨강`() {
        val (frames, session) = run(DemoDrives.s4Incident)
        assertEquals(Signal.RED, frames[0].assessment.signal)
        assertEquals(listOf("1.5km 앞 사고가 났어요. 미리 속도를 줄이세요."), spoken(frames))
        assertEquals("700m 앞 급정체 구간 2점, 800m 앞 사고 현장 3점. 합계 5점이라 빨강이에요.", session.why())
    }

    @Test
    fun `S5 평소 - 초록, 소리 없음`() {
        val (frames, session) = run(DemoDrives.s5Normal)
        assertTrue(frames.all { it.assessment.signal == Signal.GREEN })
        assertEquals("주의할 구간이 없어요", frames[0].screenLine)
        assertTrue(spoken(frames).isEmpty())
        assertEquals("오늘은 주의 구간 없이 다녀왔어요.", session.endTrip())
    }
}

class AnsimCopyTest {
    private val copy = AnsimCopy()

    @Test
    fun `거리 읽기`() {
        assertEquals("바로", copy.distance(50))
        assertEquals("800m", copy.distance(780))
        assertEquals("1km", copy.distance(1200))
        assertEquals("1.5km", copy.distance(1500))
        assertEquals("2km", copy.distance(1900))
    }

    @Test
    fun `왜 질문에는 점수 계산을 그대로 읽어준다`() {
        val a = SignalJudge().assess(snap(car = CarConditions(wiperOn = true, isNight = true)))
        assertEquals("비 2점, 야간 1점. 합계 3점이라 노랑이에요.", copy.whyLine(a))
    }

    @Test
    fun `돌발 정보 위치를 진행 방향 앞쪽 거리로 바꾼다`() {
        val here = GeoPoint(37.5, 127.0)
        val north = GeoPoint(37.509, 127.0) // 약 1km 북쪽
        assertTrue(IncidentLocator.distanceAhead(here, 0.0, north)!! in 950..1050)
        assertNull(IncidentLocator.distanceAhead(here, 180.0, north)) // 뒤쪽
        assertNull(IncidentLocator.distanceAhead(here, 0.0, GeoPoint(37.53, 127.0))) // 2km 밖
        assertEquals(CongestionSegment(Congestion.HEAVY, 1000, 3000), IncidentLocator.segmentFromRatios(Congestion.HEAVY, 20, 40, 10_000, 1000))
    }
}
