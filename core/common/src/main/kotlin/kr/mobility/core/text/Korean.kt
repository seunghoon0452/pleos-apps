package kr.mobility.core.text

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.roundToLong

/** 안내 문구용 한국어 도우미: 조사 고르기, 금액·거리·시간 읽기. */
object Korean {
    // 숫자로 끝나는 말은 읽는 소리로 받침을 판단한다(0 영, 1 일, 3 삼, 6 육, 7 칠, 8 팔 = 받침 있음).
    private val digitHasFinal = mapOf(
        '0' to true, '1' to true, '2' to false, '3' to true, '4' to false,
        '5' to false, '6' to true, '7' to true, '8' to true, '9' to false,
    )

    private fun lastSound(word: String): Char? = word.trimEnd().lastOrNull { !it.isWhitespace() && it !in ")]" }

    /** 마지막 글자에 받침이 있는지. 한글·숫자가 아니면 false. */
    fun hasFinal(word: String): Boolean {
        val ch = lastSound(word) ?: return false
        if (ch in '가'..'힣') return (ch - '가') % 28 != 0
        return digitHasFinal[ch] ?: false
    }

    private fun hasRieulFinal(word: String): Boolean {
        val ch = lastSound(word) ?: return false
        if (ch in '가'..'힣') return (ch - '가') % 28 == 8
        return ch in "178" // 일, 칠, 팔
    }

    fun eunNeun(word: String) = word + if (hasFinal(word)) "은" else "는"
    fun iGa(word: String) = word + if (hasFinal(word)) "이" else "가"
    fun eulReul(word: String) = word + if (hasFinal(word)) "을" else "를"
    fun gwaWa(word: String) = word + if (hasFinal(word)) "과" else "와"
    fun iya(word: String) = word + if (hasFinal(word)) "이에요" else "예요"

    /** '으로/로': 받침이 없거나 ㄹ 받침이면 '로'. */
    fun euroRo(word: String) = word + if (hasFinal(word) && !hasRieulFinal(word)) "으로" else "로"

    /** 화면용 금액. 12340 → "12,340원". */
    fun won(amount: Double): String = "%,d원".format(amount.roundToLong())

    /** 말로 읽는 금액. 12340 → "1만 2천 원", 3460 → "3천 5백 원". */
    fun wonSpoken(amount: Double): String {
        val rounded = if (amount >= 10_000) (amount / 1000).roundToLong() * 1000 else (amount / 100).roundToLong() * 100
        if (rounded <= 0) return "0원"
        val man = rounded / 10_000
        val cheon = rounded % 10_000 / 1000
        val baek = rounded % 1000 / 100
        val parts = buildList {
            if (man > 0) add("${man}만")
            if (cheon > 0) add("${cheon}천")
            if (baek > 0) add("${baek}백")
        }
        return parts.joinToString(" ") + " 원"
    }

    /** 25.4 → "25km". */
    fun km(value: Double): String = "${value.roundToLong()}km"

    /** 47 → "47분", 65 → "1시간 5분", 120 → "2시간". */
    fun minutes(value: Double): String {
        val total = value.roundToLong().coerceAtLeast(0)
        val h = total / 60
        val m = total % 60
        return when {
            h == 0L -> "${m}분"
            m == 0L -> "${h}시간"
            else -> "${h}시간 ${m}분"
        }
    }

    private val dayNames = mapOf(
        DayOfWeek.MONDAY to "월요일", DayOfWeek.TUESDAY to "화요일", DayOfWeek.WEDNESDAY to "수요일",
        DayOfWeek.THURSDAY to "목요일", DayOfWeek.FRIDAY to "금요일", DayOfWeek.SATURDAY to "토요일",
        DayOfWeek.SUNDAY to "일요일",
    )

    fun dayName(day: DayOfWeek): String = dayNames.getValue(day)

    /** "11월 3일 화요일". */
    fun spokenDate(date: LocalDate): String = "${date.monthValue}월 ${date.dayOfMonth}일 ${dayName(date.dayOfWeek)}"

    /** "오후 3시", "오전 7시 30분", "밤 12시", "낮 12시". */
    fun spokenTime(time: LocalTime): String {
        val h = time.hour
        val clock = when (h) {
            0 -> "밤 12시"
            12 -> "낮 12시"
            in 1..11 -> "오전 ${h}시"
            else -> "오후 ${h - 12}시"
        }
        return if (time.minute == 0) clock else "$clock ${time.minute}분"
    }
}
