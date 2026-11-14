package kr.mobility.core.voice

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

/** 일정 문장에서 뽑은 결과. 빠진 값은 null이고 이유는 [issues]에 남긴다. */
data class ParsedSchedule(
    val date: LocalDate?,
    val time: LocalTime?,
    val placeQuery: String?,
    val purpose: String?,
    val issues: Set<ParseIssue>,
    /** "매주 화요일 …"처럼 매주 반복하는 일정. [date]는 첫 번째 날이다. */
    val weekly: Boolean = false,
) {
    val dateTime: LocalDateTime? get() = if (date != null && time != null) LocalDateTime.of(date, time) else null

    /** 날짜·시간·장소가 모두 있으면 읽어주고 "응"만 받으면 된다. */
    val isComplete: Boolean get() = date != null && time != null && placeQuery != null
}

enum class ParseIssue {
    NO_DATE,
    NO_TIME,
    NO_PLACE,

    /** 오전·오후 말이 없어서 시각을 추정했다(예: "3시" → 15시). 읽어줄 때 꼭 확인한다. */
    HOUR_ASSUMED,

    /** 날짜 말이 없어서 오늘(지났으면 내일)로 정했다. */
    DATE_ASSUMED,
}

/**
 * "다음 주 화요일 오후 3시에 대전 출장 있어" 같은 말을 날짜·시간·장소·목적으로 나눈다.
 *
 * LLM SDK를 쓸 수 없거나 응답이 없을 때 쓰는 규칙 기반 해석기다(서비스 정의서 15장 리스크 대응).
 * 결과는 항상 읽어주고 "응"으로 확인받으므로, 틀릴 수 있는 추정은 [ParseIssue]로 표시만 한다.
 */
class KoreanScheduleParser {

    fun parse(utterance: String, now: ZonedDateTime): ParsedSchedule {
        val issues = mutableSetOf<ParseIssue>()
        var text = normalize(utterance)
        val today = now.toLocalDate()

        // 0) 반복: "매주", "주마다". 요일 말은 그대로 두고 날짜 규칙이 첫 번째 날을 고른다.
        val repeat = weeklyRegex.find(text)
        val weekly = repeat != null
        if (repeat != null) text = cut(text, repeat.range)

        // 1) 날짜
        var date: LocalDate? = null
        for (rule in dateRules) {
            val match = rule.regex.find(text) ?: continue
            val resolved = rule.resolve(match, today) ?: continue
            date = resolved
            text = cut(text, match.range)
            break
        }

        // 2) 시간
        var time: LocalTime? = null
        var nextDay = false
        val noon = Regex("정오").find(text)
        val midnight = Regex("자정").find(text)
        val clock = Regex("(\\d{1,2}):(\\d{2})").find(text)
        val spoken = hourRegex.find(text)
        when {
            noon != null -> { time = LocalTime.NOON; text = cut(text, noon.range) }
            midnight != null -> { time = LocalTime.MIDNIGHT; nextDay = true; text = cut(text, midnight.range) }
            clock != null -> {
                val h = clock.groupValues[1].toInt()
                val m = clock.groupValues[2].toInt()
                if (h in 0..23 && m in 0..59) {
                    time = LocalTime.of(h, m)
                    text = cut(text, clock.range)
                }
            }
            spoken != null -> {
                val result = resolveSpokenHour(spoken)
                if (result != null) {
                    time = result.time
                    nextDay = result.nextDay
                    if (result.assumed) issues += ParseIssue.HOUR_ASSUMED
                    text = cut(text, spoken.range)
                }
            }
        }
        if (nextDay && date != null) date = date.plusDays(1)

        if (date == null) {
            if (time != null) {
                val candidate = today.atTime(time)
                date = if (candidate.isAfter(now.toLocalDateTime())) today else today.plusDays(1)
                if (nextDay) date = date.plusDays(1)
                issues += ParseIssue.DATE_ASSUMED
            } else {
                issues += ParseIssue.NO_DATE
            }
        }
        if (time == null) issues += ParseIssue.NO_TIME

        // 3) 장소·목적
        text = predicateTail.replace(text, " ").trim()
        var purpose: String? = null
        val placeTokens = mutableListOf<String>()
        for (raw in text.split(' ').filter { it.isNotBlank() }) {
            val token = stripParticle(raw)
            if (token.isEmpty() || token in fillerWords) continue
            if (token in purposeWords) {
                if (purpose == null) purpose = token
                continue
            }
            placeTokens += token
        }
        val place = placeTokens.joinToString(" ").ifBlank { null }
        if (place == null) issues += ParseIssue.NO_PLACE

        return ParsedSchedule(date, time, place, purpose, issues, weekly)
    }

    private val weeklyRegex = Regex("매\\s?주(?=\\s|[월화수목금토일])|주마다")

    // ---------- 날짜 규칙 ----------

    private class DateRule(val regex: Regex, val resolve: (MatchResult, LocalDate) -> LocalDate?)

    private val dayChars = mapOf(
        "월" to DayOfWeek.MONDAY, "화" to DayOfWeek.TUESDAY, "수" to DayOfWeek.WEDNESDAY,
        "목" to DayOfWeek.THURSDAY, "금" to DayOfWeek.FRIDAY, "토" to DayOfWeek.SATURDAY, "일" to DayOfWeek.SUNDAY,
    )

    private fun weekOffset(word: String): Int = when (word) {
        "이번", "이" -> 0
        "다음", "담" -> 1
        "다다음" -> 2
        else -> 0
    }

    private fun dayInWeek(today: LocalDate, offset: Int, day: DayOfWeek): LocalDate {
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val target = monday.plusWeeks(offset.toLong()).plusDays((day.value - 1).toLong())
        // "이번 주 월요일"인데 이미 지났다면 다가오는 그 요일로 본다.
        return if (offset == 0 && target.isBefore(today)) target.plusWeeks(1) else target
    }

    private val dateRules = listOf(
        DateRule(Regex("(\\d{1,2})\\s*월\\s*(\\d{1,2})\\s*일")) { m, today ->
            val month = m.groupValues[1].toInt()
            val day = m.groupValues[2].toInt()
            runCatching { LocalDate.of(today.year, month, day) }.getOrNull()
                ?.let { if (it.isBefore(today)) it.plusYears(1) else it }
        },
        DateRule(Regex("(다다음|다음|담|이번)\\s*주\\s*([월화수목금토일])요일")) { m, today ->
            dayInWeek(today, weekOffset(m.groupValues[1]), dayChars.getValue(m.groupValues[2]))
        },
        DateRule(Regex("(다다음|다음|담|이번)\\s*주말")) { m, today ->
            dayInWeek(today, weekOffset(m.groupValues[1]), DayOfWeek.SATURDAY)
        },
        DateRule(Regex("(\\d+)\\s*일\\s*(뒤|후)")) { m, today -> today.plusDays(m.groupValues[1].toLong()) },
        DateRule(Regex("(\\d+)\\s*주\\s*(뒤|후)")) { m, today -> today.plusWeeks(m.groupValues[1].toLong()) },
        DateRule(Regex("(하루|이틀|사흘|나흘)\\s*(뒤|후)")) { m, today ->
            val n = when (m.groupValues[1]) { "하루" -> 1L; "이틀" -> 2L; "사흘" -> 3L; else -> 4L }
            today.plusDays(n)
        },
        DateRule(Regex("오늘|금일")) { _, today -> today },
        DateRule(Regex("내일")) { _, today -> today.plusDays(1) },
        DateRule(Regex("모레")) { _, today -> today.plusDays(2) },
        DateRule(Regex("글피")) { _, today -> today.plusDays(3) },
        DateRule(Regex("([월화수목금토일])요일")) { m, today ->
            today.with(TemporalAdjusters.nextOrSame(dayChars.getValue(m.groupValues[1])))
        },
        DateRule(Regex("(\\d{1,2})\\s*일")) { m, today ->
            val day = m.groupValues[1].toInt()
            val thisMonth = runCatching { today.withDayOfMonth(day) }.getOrNull()
            when {
                thisMonth != null && !thisMonth.isBefore(today) -> thisMonth
                else -> runCatching { today.plusMonths(1).withDayOfMonth(day) }.getOrNull()
            }
        },
    )

    // ---------- 시간 규칙 ----------

    private val hourWords = linkedMapOf(
        "열한" to 11, "열두" to 12, "열" to 10, "한" to 1, "두" to 2, "세" to 3, "네" to 4,
        "다섯" to 5, "여섯" to 6, "일곱" to 7, "여덟" to 8, "아홉" to 9,
    )

    private val hourRegex = Regex(
        "(새벽|아침|오전|낮|점심|오후|저녁|밤)?\\s*(\\d{1,2}|${hourWords.keys.joinToString("|")})\\s*시(?!간)" +
            "(?:\\s*(\\d{1,2})\\s*분|\\s*(반))?",
    )

    private data class HourResult(val time: LocalTime, val assumed: Boolean, val nextDay: Boolean)

    private fun resolveSpokenHour(m: MatchResult): HourResult? {
        val period = m.groupValues[1]
        val hourToken = m.groupValues[2]
        val raw = hourToken.toIntOrNull() ?: hourWords[hourToken] ?: return null
        val minute = when {
            m.groupValues[3].isNotEmpty() -> m.groupValues[3].toInt()
            m.groupValues[4].isNotEmpty() -> 30
            else -> 0
        }
        if (minute !in 0..59 || raw !in 0..24) return null
        var assumed = false
        var nextDay = false
        val hour = when (period) {
            "오후", "저녁" -> if (raw in 1..11) raw + 12 else raw
            "밤" -> when (raw) {
                12, 24 -> { nextDay = true; 0 }
                in 1..4 -> { nextDay = true; raw }
                in 5..11 -> raw + 12
                else -> raw
            }
            "낮", "점심" -> if (raw in 1..5) raw + 12 else raw
            "새벽", "아침", "오전" -> if (raw == 12) 0 else raw
            else -> when (raw) {
                // 오전·오후 없이 "3시"라고 하면 일정 맥락상 오후로 본다(확인 질문으로 바로잡는다).
                in 1..6 -> { assumed = true; raw + 12 }
                in 7..11 -> { assumed = true; raw }
                24 -> { nextDay = true; 0 }
                else -> raw
            }
        }
        if (hour !in 0..23) return null
        return HourResult(LocalTime.of(hour, minute), assumed, nextDay)
    }

    // ---------- 장소·목적 ----------

    private val purposeWords = setOf(
        "출장", "미팅", "회의", "약속", "여행", "골프", "라운드", "라운딩", "병원", "진료", "면접", "모임",
        "행사", "결혼식", "장례식", "데이트", "외근", "방문", "픽업", "등원", "하원", "쇼핑", "캠핑", "성묘",
        "귀성", "출근", "퇴근", "수업", "레슨", "현장",
    )

    private val fillerWords = setOf(
        "있어", "있어요", "있음", "있다", "있고", "있는데", "가야", "돼", "해", "돼요", "해요", "가", "가요",
        "갈", "거야", "거예요", "예정", "일정", "등록", "등록해", "잡아", "좀", "꼭", "나", "저", "나는",
        "저는", "우리", "같이", "그리고", "가족", "친구", "동료", "아이", "애들", "에", "은", "는", "때문에",
    )

    private val predicateTail = Regex(
        "(가야\\s?돼요?|가야\\s?해요?|갈\\s?거야|갈\\s?거예요|등록해\\s?줘|잡아\\s?줘|알려\\s?줘|기억해\\s?줘|" +
            "예정이야|예정이에요|있어요|있어|있음)\\s*$",
    )

    /** 받침이 있는 글자인지. '로'(받침 없음·ㄹ 뒤)와 '으로'를 구분하는 데 쓴다. */
    private fun hasFinalConsonant(ch: Char): Boolean {
        if (ch !in '가'..'힣') return false
        return (ch - '가') % 28 != 0
    }

    private fun hasRieulFinal(ch: Char): Boolean = ch in '가'..'힣' && (ch - '가') % 28 == 8

    private val roadNames = setOf("종로", "을지로", "세종로", "대학로", "퇴계로", "테헤란로", "도산대로", "강남대로")

    private fun stripParticle(token: String): String {
        for (p in listOf("에서는", "에서", "으로는", "으로", "까지", "에는", "에", "엔")) {
            if (token.length > p.length && token.endsWith(p)) return token.dropLast(p.length)
        }
        if (token.length >= 3 && token.endsWith("로") && token !in roadNames && !token.endsWith("대로")) {
            val before = token[token.length - 2]
            if (!hasFinalConsonant(before) || hasRieulFinal(before)) return token.dropLast(1)
        }
        return token
    }

    private fun normalize(s: String): String =
        s.replace(Regex("[.,!?~\"'“”‘’]"), " ").replace(Regex("\\s+"), " ").trim()

    private fun cut(s: String, range: IntRange): String =
        (s.substring(0, range.first) + " " + s.substring(range.last + 1)).replace(Regex("\\s+"), " ").trim()
}
