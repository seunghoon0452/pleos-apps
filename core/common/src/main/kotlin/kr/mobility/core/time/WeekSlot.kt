package kr.mobility.core.time

import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

val KST: ZoneId = ZoneId.of("Asia/Seoul")

/**
 * 요일 + 2시간 단위 시간대. 운행 패턴을 묶는 단위다(정의서 R-03).
 * slot 0 = 00:00~02:00, slot 11 = 22:00~24:00.
 */
data class WeekSlot(val day: DayOfWeek, val slot: Int) {
    init {
        require(slot in 0 until SLOTS_PER_DAY) { "slot out of range: $slot" }
    }

    val startHour: Int get() = slot * HOURS_PER_SLOT

    /** [from] 이후(진행 중인 칸 포함) 가장 가까운 이 칸의 시작 시각. */
    fun nextStart(from: ZonedDateTime): ZonedDateTime {
        val base = from.truncatedTo(ChronoUnit.DAYS)
        for (offset in 0..7) {
            val day = base.plusDays(offset.toLong())
            if (day.dayOfWeek != this.day) continue
            val start = day.withHour(startHour)
            if (start.plusHours(HOURS_PER_SLOT.toLong()) > from) return start
        }
        error("unreachable")
    }

    companion object {
        const val HOURS_PER_SLOT = 2
        const val SLOTS_PER_DAY = 24 / HOURS_PER_SLOT

        fun of(time: ZonedDateTime): WeekSlot = WeekSlot(time.dayOfWeek, time.hour / HOURS_PER_SLOT)
    }
}

/** 요일·시간대를 사람이 읽는 말로 바꾼다. 예: "토요일 오전". */
object KoreanTimeWords {
    private val dayNames = mapOf(
        DayOfWeek.MONDAY to "월요일", DayOfWeek.TUESDAY to "화요일", DayOfWeek.WEDNESDAY to "수요일",
        DayOfWeek.THURSDAY to "목요일", DayOfWeek.FRIDAY to "금요일", DayOfWeek.SATURDAY to "토요일",
        DayOfWeek.SUNDAY to "일요일",
    )

    fun dayName(day: DayOfWeek): String = dayNames.getValue(day)

    fun partOfDay(hour: Int): String = when (hour) {
        in 0..4 -> "새벽"
        in 5..8 -> "아침"
        in 9..11 -> "오전"
        in 12..13 -> "점심"
        in 14..17 -> "오후"
        in 18..20 -> "저녁"
        else -> "밤"
    }

    fun slotLabel(slot: WeekSlot): String = "${dayName(slot.day)} ${partOfDay(slot.startHour)}"
}
