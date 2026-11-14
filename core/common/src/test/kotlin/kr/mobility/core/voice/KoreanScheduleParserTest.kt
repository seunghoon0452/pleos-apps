package kr.mobility.core.voice

import kr.mobility.core.time.KST
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KoreanScheduleParserTest {
    private val parser = KoreanScheduleParser()

    // 기준: 2026-10-30(금) 오전 10시
    private val now = ZonedDateTime.of(2026, 10, 30, 10, 0, 0, 0, KST)

    private fun p(s: String) = parser.parse(s, now)

    @Test
    fun `다음 주 요일 + 오후 시각 + 장소 + 목적`() {
        val r = p("다음 주 화요일 오후 3시에 대전 출장 있어")
        assertEquals(LocalDate.of(2026, 11, 3), r.date)
        assertEquals(LocalTime.of(15, 0), r.time)
        assertEquals("대전", r.placeQuery)
        assertEquals("출장", r.purpose)
        assertTrue(r.isComplete)
        assertTrue(r.issues.isEmpty(), "issues=${r.issues}")
    }

    @Test
    fun `내일 아침 반 단위, 조사 으로 제거`() {
        val r = p("내일 아침 7시 반 강남역으로 미팅")
        assertEquals(LocalDate.of(2026, 10, 31), r.date)
        assertEquals(LocalTime.of(7, 30), r.time)
        assertEquals("강남역", r.placeQuery)
        assertEquals("미팅", r.purpose)
    }

    @Test
    fun `월 일 지정`() {
        val r = p("11월 7일 오전 10시 부산 해운대 여행")
        assertEquals(LocalDate.of(2026, 11, 7), r.date)
        assertEquals(LocalTime.of(10, 0), r.time)
        assertEquals("부산 해운대", r.placeQuery)
        assertEquals("여행", r.purpose)
    }

    @Test
    fun `지난 월 일은 내년`() {
        assertEquals(LocalDate.of(2027, 3, 1), p("3월 1일 오후 2시 수원").date)
    }

    @Test
    fun `모레 저녁`() {
        val r = p("모레 저녁 7시에 판교 현대백화점 모임")
        assertEquals(LocalDate.of(2026, 11, 1), r.date)
        assertEquals(LocalTime.of(19, 0), r.time)
        assertEquals("판교 현대백화점", r.placeQuery)
    }

    @Test
    fun `오전 오후 없는 3시는 15시로 보고 확인 필요 표시`() {
        val r = p("3시에 병원 가야 돼")
        assertEquals(LocalTime.of(15, 0), r.time)
        assertEquals(LocalDate.of(2026, 10, 30), r.date)
        assertEquals("병원", r.purpose)
        assertNull(r.placeQuery)
        assertFalse(r.isComplete)
        assertEquals(setOf(ParseIssue.HOUR_ASSUMED, ParseIssue.DATE_ASSUMED, ParseIssue.NO_PLACE), r.issues)
    }

    @Test
    fun `이미 지난 시각만 말하면 내일`() {
        val r = p("9시 회의 판교")
        assertEquals(LocalDate.of(2026, 10, 31), r.date)
        assertEquals(LocalTime.of(9, 0), r.time)
        assertTrue(ParseIssue.DATE_ASSUMED in r.issues)
    }

    @Test
    fun `한글 숫자 시각`() {
        val r = p("내일 열한시 반에 인천공항")
        assertEquals(LocalTime.of(11, 30), r.time)
        assertEquals("인천공항", r.placeQuery)
        assertEquals(LocalTime.of(14, 0), p("내일 오후 두시 수원").time)
    }

    @Test
    fun `밤 12시와 자정은 다음 날 0시`() {
        val r = p("내일 밤 12시 서울역")
        assertEquals(LocalDate.of(2026, 11, 1), r.date)
        assertEquals(LocalTime.MIDNIGHT, r.time)
        val m = p("내일 자정 서울역")
        assertEquals(LocalDate.of(2026, 11, 1), m.date)
        assertEquals(LocalTime.of(12, 0), p("내일 정오 서울역").time)
    }

    @Test
    fun `시계 표기`() {
        val r = p("14:30 회의 강남")
        assertEquals(LocalTime.of(14, 30), r.time)
        assertEquals(LocalDate.of(2026, 10, 30), r.date)
        assertEquals("강남", r.placeQuery)
    }

    @Test
    fun `이번 주 지난 요일은 다음 그 요일`() {
        assertEquals(LocalDate.of(2026, 11, 4), p("이번 주 수요일 오후 2시 성수동").date)
        assertEquals(LocalDate.of(2026, 10, 31), p("토요일 오후 2시 성수동").date)
    }

    @Test
    fun `다음 주말, 시간 없음`() {
        val r = p("다음 주말 가평 캠핑")
        assertEquals(LocalDate.of(2026, 11, 7), r.date)
        assertNull(r.time)
        assertEquals("가평", r.placeQuery)
        assertEquals("캠핑", r.purpose)
        assertTrue(ParseIssue.NO_TIME in r.issues)
    }

    @Test
    fun `N일 뒤와 날짜만`() {
        assertEquals(LocalDate.of(2026, 11, 2), p("3일 뒤 오후 2시 수원").date)
        assertEquals(LocalDate.of(2026, 11, 1), p("이틀 뒤 오후 2시 수원").date)
        assertEquals(LocalDate.of(2026, 11, 15), p("15일 오후 2시 수원").date)
    }

    @Test
    fun `조사 로 처리 - 받침 없는 말, ㄹ받침, 도로 이름`() {
        assertEquals("회사", p("내일 오후 2시 회사로").placeQuery)
        assertEquals("서울", p("내일 오후 2시 서울로 출장").placeQuery)
        assertEquals("종로", p("내일 오후 2시 종로").placeQuery)
        assertEquals("세종대로", p("내일 오후 2시 세종대로").placeQuery)
        assertEquals("강남역", p("내일 오후 2시 강남역에서 약속").placeQuery)
    }

    @Test
    fun `시간 뒤는 시각으로 읽지 않는다`() {
        assertNull(p("2시간 뒤 출발").time)
    }

    @Test
    fun `등록해줘 같은 끝말은 장소에서 뺀다`() {
        val r = p("내일 오후 3시 대전 출장 등록해줘")
        assertEquals("대전", r.placeQuery)
        assertEquals("출장", r.purpose)
    }
}
