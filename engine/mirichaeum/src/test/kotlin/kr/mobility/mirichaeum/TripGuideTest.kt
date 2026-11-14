package kr.mobility.mirichaeum

import kr.mobility.mirichaeum.demo.DemoPersona
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** F-26 일정(목적지)별 최소·권장 충전량과 충전 시간, R-08 최소·권장 기준. */
class TripGuideTest {
    private val engine = MiriChaeum()
    private val copy = Copywriter()

    @Test
    fun `S1 반복 목적지를 왕복으로 묶고 일정마다 최소·권장을 계산한다`() {
        val r = engine.evaluate(DemoPersona.s1FridayEvening())
        assertEquals(listOf("동네 마트", "양평"), r.guides.map { it.title })
        assertTrue(r.guides.all { it.source == GuideSource.ROUTINE })

        val mart = r.guides[0]
        assertEquals(19.0, mart.roundTripKm, 1e-9) // 회사 → 마트 12km + 마트 → 집 7km
        assertEquals(Verdict.ENOUGH, mart.verdict)

        val trip = assertNotNull(r.focusGuide)
        assertEquals("양평", trip.title)
        assertEquals(120.0, trip.roundTripKm, 1e-9) // 집 → 양평 60km + 양평 → 집 60km
        assertEquals(139.0, trip.needKm, 1e-9) // 금요일 퇴근길 19km를 먼저 달린다
        assertEquals("52%", copy.levelText(trip.minimum))
        assertEquals("63%", copy.levelText(trip.recommended))
        assertTrue(trip.minimum.reached)
        assertFalse(trip.recommended.reached)
        assertEquals("+5%p · 급속 약 3분 · 완속 약 35분", copy.targetDetail(trip, trip.recommended))
        assertEquals(Verdict.TIGHT, trip.verdict)
    }

    @Test
    fun `지금 잔량이 권장 이상이면 초록, 최소 이상이면 노랑, 최소 미만이면 빨강으로 판정과 일치한다`() {
        val base = DemoPersona.s1FridayEvening()
        for (level in listOf(40.0, 52.0, 58.0, 63.0, 80.0)) {
            val s = base.snapshot.copy(levelPercent = level, rangeKm = 218.0 / 58.0 * level)
            val r = engine.evaluate(base.copy(snapshot = s))
            val g = r.guides.last()
            val expected = when {
                level >= g.recommended.levelPercent -> Verdict.ENOUGH
                level >= g.minimum.levelPercent -> Verdict.TIGHT
                else -> Verdict.SHORT
            }
            assertEquals(expected, g.verdict, "level $level")
            assertEquals(r.judgement.verdict, g.verdict, "level $level")
        }
    }

    @Test
    fun `내연기관은 L와 주유 시간으로 안내한다`() {
        val r = engine.evaluate(DemoPersona.s4IceThursday())
        val g = assertNotNull(r.focusGuide)
        assertEquals("양평", g.title)
        assertEquals("36%", copy.levelText(g.minimum))
        assertEquals("45%", copy.levelText(g.recommended))
        assertEquals("+5%p · 약 3L · 주유 약 5분", copy.targetDetail(g, g.recommended))
    }

    @Test
    fun `등록한 장거리 일정은 한 번 충전으로 모자라면 가는 길 충전을 알린다`() {
        val input = DemoPersona.s1FridayEvening()
        val gangneung = DemoPersona.calendarSeeds().first { it.weekly.not() }
        val target = assertNotNull(engine.tripTarget(input.snapshot, gangneung, input.now))
        assertEquals(GuideSource.PLANNED, target.source)
        assertTrue(target.recommended.overFull)
        assertEquals("100% 이상", copy.levelText(target.recommended))
        assertTrue(copy.voiceTripRegistered(gangneung, null, target).contains("가는 길에 충전이 필요해요"))
    }

    @Test
    fun `캘린더 일정에는 그 일정만 다녀올 때의 최소·권장이 붙는다`() {
        val input = DemoPersona.s1FridayEvening()
        val withTrips = input.copy(voiceTrips = DemoPersona.calendarSeeds())
        val r = engine.evaluate(withTrips)
        val from = input.now.toLocalDate()
        val view = engine.calendar(withTrips, r, from, from.plusDays(13))
        val planned = view.days.flatMap { it.entries }.filter { it.kind == EntryKind.PLANNED && it.km != null }
        assertTrue(planned.isNotEmpty())
        assertTrue(planned.all { it.guide != null && it.guide.needKm == it.km })
        // 평소 이동은 목적지로 가는 이동에만 붙고 집으로 돌아오는 이동에는 붙지 않는다.
        val saturday = view.day(LocalDate.of(2026, 10, 31))!!.entries.filter { it.kind == EntryKind.ROUTINE }
        assertEquals(listOf(true, false), saturday.map { it.guide != null })
        assertEquals(120.0, saturday.first().guide!!.roundTripKm, 1e-9)
        // 금요일은 회사 → 마트, 마트 → 집이 같은 오후 6~8시 칸이다. 마트로 가는 이동에만 붙는다.
        val friday = view.day(LocalDate.of(2026, 10, 30))!!.entries.filter { it.kind == EntryKind.ROUTINE }
        assertEquals(listOf("회사 → 동네 마트" to true, "동네 마트 → 집" to false), friday.map { it.title to (it.guide != null) })
        assertEquals(19.0, friday.first().guide!!.roundTripKm, 1e-9)
    }
}
