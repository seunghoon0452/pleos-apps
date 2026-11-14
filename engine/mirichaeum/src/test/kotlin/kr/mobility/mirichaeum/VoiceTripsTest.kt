package kr.mobility.mirichaeum

import kr.mobility.core.time.KST
import kr.mobility.core.voice.ParseIssue
import kr.mobility.mirichaeum.demo.DemoPersona
import kr.mobility.mirichaeum.demo.DemoPlaces
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VoiceTripsTest {
    private val now = ZonedDateTime.of(2026, 10, 30, 8, 20, 0, 0, KST)
    private val registrar = VoiceTripRegistrar(DemoPlaces, idFactory = { "id" })

    @Test
    fun `빠진 것은 하나씩 묻는다`() {
        assertEquals("어디로 가세요?", assertIs<VoiceStep.AskMissing>(registrar.interpret("내일 오후 3시 출장", now, null)).prompt)
        assertEquals(ParseIssue.NO_TIME, assertIs<VoiceStep.AskMissing>(registrar.interpret("내일 대전 출장", now, null)).missing)
    }

    @Test
    fun `후보가 멀리 흩어져 있으면 정차 후 고르게 한다`() {
        val step = assertIs<VoiceStep.PickPlaceLater>(registrar.interpret("내일 오후 2시 카페", now, DemoPersona.workPoint))
        assertEquals("10월 31일 토요일 오후 2시 카페 일정이에요. 장소는 차를 세운 뒤에 골라주세요.", step.prompt)
        assertNull(step.draft.roundTripKm)
        val picked = registrar.withPlace(step.draft, step.candidates.first(), DemoPersona.workPoint)
        assertEquals("판교 카페", picked.placeName)
        assertTrue(picked.roundTripKm!! < 5.0)
    }

    @Test
    fun `내비 거리가 있으면 그것을 쓴다`() {
        val withNavi = VoiceTripRegistrar(DemoPlaces, road = { _, _ -> 162.0 to DistanceSource.NAVI }, idFactory = { "id" })
        val trip = assertIs<VoiceStep.Confirm>(withNavi.interpret("다음 주 화요일 오후 3시 대전 출장", now, DemoPersona.workPoint)).draft
        assertEquals(DistanceSource.NAVI, trip.distanceSource)
        assertEquals(324.0, trip.roundTripKm)
    }

    @Test
    fun `오전 오후를 안 말하면 확인이 필요하다고 표시`() {
        val step = assertIs<VoiceStep.Confirm>(registrar.interpret("내일 3시 강남역 약속", now, DemoPersona.workPoint))
        assertTrue(step.needsHourCheck)
        assertEquals("10월 31일 토요일 오후 3시, 강남역 맞나요?", step.prompt)
    }

    @Test
    fun `응 아니 구분`() {
        listOf("응", "네 맞아요", "어 그래", "맞습니다", "좋아").forEach { assertEquals(Answer.YES, YesNo.classify(it), it) }
        listOf("아니", "아니요", "아뇨 틀렸어", "취소해", "응 아니야").forEach { assertEquals(Answer.NO, YesNo.classify(it), it) }
        listOf("음", "노래 틀어줘", "").forEach { assertEquals(Answer.UNKNOWN, YesNo.classify(it), it) }
    }

    @Test
    fun `일정 목록 넣기 빼기`() {
        val a = VoiceTrip("a", now.plusDays(2), "x", null, null)
        val b = VoiceTrip("b", now.plusDays(1), "y", null, null)
        val list = VoiceTripBook.upsert(VoiceTripBook.upsert(emptyList(), a), b)
        assertEquals(listOf("b", "a"), list.map { it.id })
        assertEquals(listOf("a"), VoiceTripBook.remove(list, "b").map { it.id })
        assertEquals(2, VoiceTripBook.upsert(list, a.copy(placeQuery = "z")).size)
    }
}
