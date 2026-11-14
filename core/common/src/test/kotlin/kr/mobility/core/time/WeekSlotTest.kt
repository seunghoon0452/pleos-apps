package kr.mobility.core.time

import java.time.DayOfWeek
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WeekSlotTest {
    private fun kst(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) = ZonedDateTime.of(y, mo, d, h, mi, 0, 0, KST)

    @Test
    fun `시각을 2시간 칸으로 묶는다`() {
        // 2026-10-30은 금요일
        assertEquals(WeekSlot(DayOfWeek.FRIDAY, 4), WeekSlot.of(kst(2026, 10, 30, 9, 59)))
        assertEquals(WeekSlot(DayOfWeek.FRIDAY, 0), WeekSlot.of(kst(2026, 10, 30, 0, 0)))
        assertEquals(WeekSlot(DayOfWeek.FRIDAY, 11), WeekSlot.of(kst(2026, 10, 30, 23, 30)))
    }

    @Test
    fun `진행 중인 칸이면 그 칸의 시작 시각`() {
        val slot = WeekSlot(DayOfWeek.FRIDAY, 4)
        assertEquals(kst(2026, 10, 30, 8), slot.nextStart(kst(2026, 10, 30, 9, 30)))
    }

    @Test
    fun `이미 지난 칸이면 다음 주`() {
        val slot = WeekSlot(DayOfWeek.FRIDAY, 4)
        assertEquals(kst(2026, 11, 6, 8), slot.nextStart(kst(2026, 10, 30, 10, 0)))
    }

    @Test
    fun `다른 요일이면 다가오는 그 요일`() {
        val slot = WeekSlot(DayOfWeek.SATURDAY, 5)
        assertEquals(kst(2026, 10, 31, 10), slot.nextStart(kst(2026, 10, 30, 22, 0)))
    }

    @Test
    fun `칸 번호 범위 확인`() {
        assertFailsWith<IllegalArgumentException> { WeekSlot(DayOfWeek.MONDAY, 12) }
    }

    @Test
    fun `사람이 읽는 시간대 이름`() {
        assertEquals("토요일 오전", KoreanTimeWords.slotLabel(WeekSlot(DayOfWeek.SATURDAY, 5)))
        assertEquals("월요일 아침", KoreanTimeWords.slotLabel(WeekSlot(DayOfWeek.MONDAY, 4)))
        assertEquals("금요일 저녁", KoreanTimeWords.slotLabel(WeekSlot(DayOfWeek.FRIDAY, 9)))
    }
}
