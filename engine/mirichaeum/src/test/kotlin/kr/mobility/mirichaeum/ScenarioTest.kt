package kr.mobility.mirichaeum

import kr.mobility.core.time.KST
import kr.mobility.core.vehicle.DrivingState
import kr.mobility.mirichaeum.demo.DemoPersona
import kr.mobility.mirichaeum.demo.DemoPlaces
import java.time.YearMonth
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 서비스 정의서 5장 핵심 시나리오를 시연 데이터로 끝까지 돌려본다. */
class ScenarioTest {
    private val engine = MiriChaeum()

    @Test
    fun `S1 금요일 퇴근길 - 노랑, 마트 체류 충전 추천, 음성 1회`() {
        val r = engine.evaluate(DemoPersona.s1FridayEvening())

        assertEquals(Verdict.TIGHT, r.judgement.verdict)
        assertEquals(139.0, r.demand.totalKm, 1e-9)
        assertEquals(196.2, r.judgement.correctedRangeKm, 1e-9)
        assertEquals("최근 4주 중 3주, 토요일 오전 장거리(약 120km)", r.reason)
        assertEquals("주말 양평 전 최소 52% · 권장 63%", r.cardLine)

        val plan = assertNotNull(r.plan)
        assertEquals(5.0, plan.percent, 1e-9)
        val best = assertNotNull(plan.best)
        assertEquals(SolutionKind.DWELL, best.kind)
        assertEquals("동네 마트", best.placeName)
        assertEquals(0.0, best.extraMinutes)
        assertEquals(
            "주말 양평 왕복 약 120km 전까지 최소 52%, 권장 63%가 필요해요. 지금 58%라 권장량에 조금 못 미쳐요. " +
                "권장량까지 급속 약 3분, 완속 약 35분이에요. 금요일마다 들르시는 동네 마트에 완속 충전기가 있어요. " +
                "머무시는 40분 동안 충전하면 권장량까지 채워져요. 그곳으로 안내할까요?",
            r.voiceLine,
        )
        assertEquals(AlertChannel.VOICE, r.alert?.channel)
        // 체류 다음 순서로 경로상·우회 급속도 후보로 남는다.
        assertEquals(listOf(SolutionKind.DWELL, SolutionKind.ON_ROUTE, SolutionKind.DETOUR), plan.solutions.map { it.kind })
    }

    @Test
    fun `S1 마트에 도착하면 한 번 더 알려주고, 같은 안내는 반복하지 않는다`() {
        val input = DemoPersona.s1FridayEvening()
        val r = engine.evaluate(input)
        val arriveAt = input.now.plusMinutes(25)
        val sent = listOf(SentAlert(input.now, r.alertKey))
        val (decision, line) = assertNotNull(engine.onArrival(r, DemoPersona.mart, DrivingState.PARKED, arriveAt, sent))
        assertEquals(AlertChannel.VOICE, decision.channel)
        assertEquals("동네 마트에 도착했어요. 근처 완속 충전기에서 40분 충전하면 주말 양평 일정 권장량까지 채워져요.", line)

        val again = engine.evaluate(input.copy(now = input.now.plusHours(1), sentAlerts = sent))
        assertEquals(AlertChannel.CARD_ONLY, again.alert?.channel)
    }

    @Test
    fun `S3 월요일 아침 - 초록, 말하지 않음`() {
        val r = engine.evaluate(DemoPersona.s3MondayMorning())
        assertEquals(Verdict.ENOUGH, r.judgement.verdict)
        assertEquals(76.0, r.demand.totalKm, 1e-9)
        assertNull(r.plan)
        assertNull(r.voiceLine)
        assertEquals("앞으로 48시간 충분해요", r.cardLine)
        assertEquals(AlertChannel.CARD_ONLY, r.alert?.channel)
    }

    @Test
    fun `S4 내연기관 - 주말 장거리 대비 오늘 주유, 경로상 싼 주유소`() {
        val r = engine.evaluate(DemoPersona.s4IceThursday())
        assertEquals(Verdict.TIGHT, r.judgement.verdict)
        assertEquals(177.0, r.demand.totalKm, 1e-9)
        assertEquals(
            "주말 양평 왕복 약 120km 전까지 최소 36%, 권장 45%가 필요해요. 지금 40%라 권장량에 조금 못 미쳐요. " +
                "권장량까지 약 3L, 주유는 약 5분이면 돼요. 퇴근길 새길 셀프주유소가 L당 40원 저렴해요. 경유지로 넣을까요?",
            r.voiceLine,
        )
    }

    @Test
    fun `S5 충전 중 - 권장 잔량과 남은 시간`() {
        val input = DemoPersona.s1FridayEvening()
        val r = engine.evaluate(input)
        val status = engine.chargingStatus(input.snapshot, r, chargerPowerKw = 7.0, now = input.now)
        assertEquals(63.0, status.targetPercent, 1e-9)
        assertTrue(status.enough)
        assertEquals("권장 63%까지 35분. 이대로면 주말 양평 일정까지 충분해요.", status.line)
    }

    @Test
    fun `S2 음성 일정 등록부터 판정 반영까지`() {
        val now = ZonedDateTime.of(2026, 10, 30, 8, 20, 0, 0, KST) // 금요일 출근길 주행 중
        val registrar = VoiceTripRegistrar(DemoPlaces, idFactory = { "v1" })
        val step = registrar.interpret("다음 주 화요일 오후 3시에 대전 출장 있어", now, origin = DemoPersona.workPoint)
        val confirm = assertIs<VoiceStep.Confirm>(step)
        assertEquals("11월 3일 화요일 오후 3시, 대전 정부청사 맞나요?", confirm.prompt)
        assertEquals(Answer.YES, YesNo.classify("응"))

        val trip = confirm.draft
        assertEquals(DistanceSource.STRAIGHT, trip.distanceSource)
        val (trips, dwells) = DemoPersona.history(now)
        val patterns = PatternLearner().learnTrips(trips, now)
        val reminder = ReminderPlanner.plan(trip, patterns, PlaceRoles.infer(dwells))
        assertEquals("월요일 퇴근할 때", reminder?.phrase)
        assertEquals("등록했어요. 왕복 약 310km예요. 월요일 퇴근할 때 다시 알려드릴게요.", Copywriter().voiceTripRegistered(trip, reminder))

        // 월요일 퇴근 때: 이동 일정이 48시간 안에 들어와 빨강이 된다.
        val monday = ZonedDateTime.of(2026, 11, 2, 18, 30, 0, 0, KST)
        val base = DemoPersona.s1FridayEvening(monday)
        val r = engine.evaluate(base.copy(voiceTrips = listOf(trip)))
        assertEquals(Verdict.SHORT, r.judgement.verdict)
        assertTrue(r.reason.startsWith("11월 3일 대전 정부청사 출장"), r.reason)
    }

    @Test
    fun `월간 리포트 문구`() {
        val month = YearMonth.of(2026, 11)
        val report = SavingsCalculator().monthly(DemoPersona.prefillRecords(month), month)
        assertEquals(3, report.count)
        assertEquals(3, report.avoidedDetours)
        assertEquals("이번 달 미리 채움으로 급속 우회 3번을 줄여 53분, 4천 2백 원을 아꼈어요.", Copywriter().monthly(report))
    }
}
