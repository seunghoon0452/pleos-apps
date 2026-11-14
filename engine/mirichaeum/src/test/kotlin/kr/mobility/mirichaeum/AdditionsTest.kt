package kr.mobility.mirichaeum

import kr.mobility.core.geo.GeoPoint
import kr.mobility.core.time.KST
import kr.mobility.core.vehicle.DrivingState
import kr.mobility.mirichaeum.demo.DemoPersona
import kr.mobility.mirichaeum.demo.DemoPlaces
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 상세 기획서(A-33, A-36, A-44, A-46, SC-04)에서 더한 규칙. */
class AdditionsTest {
    private val engine = MiriChaeum()
    private val now = ZonedDateTime.of(2026, 10, 30, 18, 30, 0, 0, KST)
    private val registrar = VoiceTripRegistrar(DemoPlaces, idFactory = { "id" })
    private val origin = GeoPoint(37.4020, 127.1086)

    @Test
    fun `S1 안내 끝에 응을 기다릴 해결책은 마트 체류 충전`() {
        val r = engine.evaluate(DemoPersona.s1FridayEvening())
        assertEquals(SolutionKind.DWELL, r.askingSolution?.kind)
        assertEquals("동네 마트", r.askingSolution?.placeName)
        assertTrue(r.voiceLine!!.endsWith("그곳으로 안내할까요?"))
    }

    @Test
    fun `충분하면 물을 해결책이 없다`() {
        assertNull(engine.evaluate(DemoPersona.s3MondayMorning()).askingSolution)
    }

    @Test
    fun `집 충전 가능이면 오늘 밤 집에서가 1순위이고 묻지 않는다`() {
        val r = engine.evaluate(DemoPersona.s1FridayEvening().copy(homeCharging = true))
        val best = assertNotNull(r.plan?.best)
        assertEquals(SolutionKind.HOME, best.kind)
        assertEquals(0.0, best.extraMinutes)
        assertNull(r.askingSolution)
        assertTrue(r.voiceLine!!.endsWith("오늘 밤 집에서 5% 충전하면 권장량이 돼요."), r.voiceLine)
        assertEquals(SolutionKind.DWELL, r.plan!!.solutions[1].kind)
    }

    @Test
    fun `집 충전 불가면 집 해결책이 없다`() {
        val r = engine.evaluate(DemoPersona.s1FridayEvening())
        assertTrue(r.plan!!.solutions.none { it.kind == SolutionKind.HOME })
    }

    @Test
    fun `되물은 대답을 합쳐 일정을 완성한다`() {
        val first = "내일 대전 출장"
        val step = registrar.interpret(first, now, origin)
        assertIs<VoiceStep.AskMissing>(step)
        assertEquals("몇 시 일정인가요?", step.prompt)
        val done = registrar.interpretFollowUp(first, "오후 3시", now, origin)
        val confirm = assertIs<VoiceStep.Confirm>(done)
        assertEquals(15, confirm.draft.at.hour)
        assertEquals("대전 정부청사", confirm.draft.placeName)
    }

    @Test
    fun `장소를 되물어도 완성된다`() {
        val first = "내일 오후 3시 일정 있어"
        assertIs<VoiceStep.AskMissing>(registrar.interpret(first, now, origin))
        assertIs<VoiceStep.Confirm>(registrar.interpretFollowUp(first, "대전", now, origin))
    }

    @Test
    fun `되물어도 없으면 여전히 빠진 항목`() {
        assertIs<VoiceStep.AskMissing>(registrar.interpretFollowUp("내일 대전 출장", "글쎄", now, origin))
    }

    @Test
    fun `충전 목표는 고정되고 도달하면 알려준다`() {
        val input = DemoPersona.s1FridayEvening()
        val r = engine.evaluate(input)
        val start = engine.chargingStatus(input.snapshot, r, 7.0, now)
        assertEquals(63.0, start.targetPercent, 0.01)
        assertFalse(start.reached)
        val full = input.snapshot.copy(levelPercent = 63.0, rangeKm = 218.0 / 58.0 * 63.0)
        val done = engine.chargingStatus(full, engine.evaluate(input.copy(snapshot = full)), 7.0, now, fixedTarget = 63.0)
        assertTrue(done.reached)
        assertEquals("권장량에 도달했어요. 주말 양평까지 충분해요.", done.line)
    }

    @Test
    fun `출력을 모르면 시간에 약을 붙인다`() {
        val input = DemoPersona.s1FridayEvening()
        val line = engine.chargingStatus(input.snapshot, engine.evaluate(input), 7.0, now, powerKnown = false).line
        assertTrue(line.startsWith("권장 63%까지 약 "), line)
    }

    @Test
    fun `해결책에 상세 화면 값이 실린다`() {
        val s = engine.evaluate(DemoPersona.s1FridayEvening()).plan!!.solutions
        assertEquals(2, s[0].available)
        assertEquals(4, s[0].total)
        assertEquals(3, s[0].statusAgeMinutes)
        assertEquals(2.0, s[1].detourMinutes)
        assertNotNull(engine.evaluate(DemoPersona.s4IceThursday()).plan?.averageUnitPrice)
    }

    @Test
    fun `도착 안내`() {
        val input = DemoPersona.s1FridayEvening()
        val r = engine.evaluate(input)
        val (decision, line) = assertNotNull(engine.onArrival(r, DemoPersona.mart, DrivingState.PARKED, now, emptyList()))
        assertEquals(AlertChannel.VOICE, decision.channel)
        assertTrue(line.startsWith("동네 마트에 도착했어요."), line)
        assertNull(engine.onArrival(r, DemoPersona.home, DrivingState.PARKED, now, emptyList()))
    }
}
