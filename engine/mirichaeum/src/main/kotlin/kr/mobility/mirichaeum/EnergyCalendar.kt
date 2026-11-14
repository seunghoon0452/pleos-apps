package kr.mobility.mirichaeum

import kr.mobility.core.geo.PlaceCell
import java.time.Duration
import java.time.LocalDate
import java.time.ZonedDateTime

/** 캘린더 하루 칸에 들어가는 항목의 종류. */
enum class EntryKind {
    /** 말 또는 터치로 등록한 이동 일정. */
    PLANNED,

    /** 운행 패턴으로 예측한 평소 이동. */
    ROUTINE,

    /** 기록이 2주 미만일 때의 하루 평균 (R-15). */
    ESTIMATE,

    /** 지난 운행 기록. */
    DRIVEN,

    /** 48시간 계획의 1순위 채우기 제안. */
    FILL,
}

data class CalendarEntry(
    val kind: EntryKind,
    val at: ZonedDateTime,
    /** 왕복(일정) 또는 편도(평소 이동·기록) 거리. 장소를 안 고른 일정은 null. */
    val km: Double?,
    val title: String,
    val trip: VoiceTrip? = null,
    val weeksSeen: Int? = null,
    val windowWeeks: Int? = null,
    /** '이날은 평소대로 안 다녀요'로 뺀 평소 이동. 화면에는 지운 줄로 남기고 거리는 세지 않는다. */
    val skipped: Boolean = false,
    /** 일정(목적지)이면 그 일정만 다녀올 때의 최소·권장 잔량 (F-26). 돌아오는 이동·기록에는 없다. */
    val guide: TripGuide? = null,
) {
    /** 예상 거리에 넣는 항목인지. */
    val counts: Boolean get() = km != null && !skipped && kind in setOf(EntryKind.PLANNED, EntryKind.ROUTINE, EntryKind.ESTIMATE)
}

data class CalendarDay(
    val date: LocalDate,
    val entries: List<CalendarEntry>,
    /** 지금부터 이날 안에 달릴 예상 거리(지난 날은 0). */
    val plannedKm: Double,
    /** 이날 실제로 달린 거리(기록). */
    val drivenKm: Double,
    /** 지금부터 이날 끝까지 누적 예상 거리. 전망 범위 밖이면 null. */
    val cumulativeKm: Double?,
    /** 채우지 않고 다닐 때 이날의 판정. 지난 날·전망 밖·채워야 하는 날 다음부터는 null. */
    val outlook: Verdict?,
    /** 처음으로 초록이 아니게 되는 날 = 이날 전에 채워야 함. */
    val needDay: Boolean,
    /** 이날 평소 이동을 뺐는지 (F-25). */
    val skipRoutine: Boolean,
    val isPast: Boolean,
    val isToday: Boolean,
) {
    val hasRoutine: Boolean get() = entries.any { it.kind == EntryKind.ROUTINE }
    val plannedCount: Int get() = entries.count { it.kind == EntryKind.PLANNED }
    val hasFill: Boolean get() = entries.any { it.kind == EntryKind.FILL }

    /** 채운 뒤에는 계획이 바뀌므로 전망을 보여주지 않는 날. */
    fun afterNeed(needDate: LocalDate?): Boolean = needDate != null && date.isAfter(needDate) && cumulativeKm != null
}

data class CalendarView(
    val days: List<CalendarDay>,
    val needDate: LocalDate?,
    val needVerdict: Verdict?,
    /** 맨 위 한 줄. "채우지 않으면 내일 빠듯해져요". */
    val line: String,
    /** 겨울 보정을 한 지금 갈 수 있는 거리. */
    val correctedRangeKm: Double,
    val outlookDays: Int,
) {
    fun day(date: LocalDate): CalendarDay? = days.firstOrNull { it.date == date }
}

/**
 * F-22 에너지 캘린더: 등록한 일정, 평소 이동, 지난 기록, 채우기 제안을 날짜별로 모으고
 * 오늘부터 [outlookDays]일 동안 '채우지 않고 다니면' 언제 모자라지는지 판정한다 (A-81).
 * 예상 거리는 48시간 판정과 같은 규칙(DemandPredictor)으로 센다.
 */
class EnergyCalendar(
    private val predictor: DemandPredictor = DemandPredictor(),
    private val judge: RangeJudge = RangeJudge(),
    private val copy: Copywriter = Copywriter(),
    private val outlookDays: Int = OUTLOOK_DAYS,
    private val guidePlanner: TripGuidePlanner = TripGuidePlanner(judge),
) {
    fun build(input: MiriInput, result: MiriResult, from: LocalDate, to: LocalDate): CalendarView {
        val now = input.now
        val zone = now.zone
        val today = now.toLocalDate()
        val outlookEnd = today.plusDays(outlookDays.toLong())
        val lastDay = maxOf(to, outlookEnd.minusDays(1))
        val until = lastDay.plusDays(1).atStartOfDay(zone)
        val roles = PlaceRoles.infer(input.dwells)
        fun name(cell: PlaceCell): String = input.placeNames[cell] ?: when (roles[cell]) {
            PlaceRole.HOME -> "집"
            PlaceRole.WORK -> "회사"
            PlaceRole.OTHER -> "자주 가는 곳"
            null -> "다른 곳"
        }

        val byDay = mutableMapOf<LocalDate, MutableList<CalendarEntry>>()
        fun add(e: CalendarEntry) {
            byDay.getOrPut(e.at.toLocalDate()) { mutableListOf() } += e
        }

        // 앞으로: 48시간 판정과 같은 규칙. 뺀 날의 평소 이동도 지운 줄로 보여주려고 여기서는 빼지 않고 센다.
        val future = predictor.between(now, until, result.patterns, result.voiceTrips, input.trips)
        // 일정(목적지)마다 그 일정만 다녀올 때의 최소·권장 잔량 (F-26).
        // 가는 이동과 돌아오는 이동이 같은 2시간 칸에 있을 수 있어 칸이 아니라 패턴(출발지·목적지)으로 구분한다.
        val guides: Map<Pair<ZonedDateTime, Any?>, TripGuide> = TripEvents.find(future, roles, input.placeNames).associate { e ->
            Pair(e.at, e.trip?.id ?: e.pattern) to guidePlanner.standalone(input.snapshot, e)
        }
        for (item in future.items) {
            when (item.source) {
                DemandSource.VOICE -> add(
                    CalendarEntry(
                        EntryKind.PLANNED, item.at, item.km, title(item.voiceTrip!!), trip = item.voiceTrip,
                        guide = guides[Pair(item.at, item.voiceTrip.id)],
                    ),
                )
                DemandSource.ROUTINE -> {
                    val p = item.pattern!!
                    add(
                        CalendarEntry(
                            EntryKind.ROUTINE, item.at, item.km, "${name(p.from)} → ${name(p.to)}",
                            weeksSeen = p.weeksSeen, windowWeeks = p.windowWeeks, skipped = item.at.toLocalDate() in input.skipDays,
                            guide = guides[Pair(item.at, p)],
                        ),
                    )
                }
                DemandSource.ESTIMATE -> Unit
            }
        }
        for (v in future.unresolvedVoiceTrips) {
            v.occurrences(now, until).forEach { add(CalendarEntry(EntryKind.PLANNED, it, null, title(v), trip = v)) }
        }
        if (result.coldStart) {
            val daily = input.userDailyKm ?: OdometerFallback.dailyAverage(input.odometerReadings) ?: ColdStart.DEFAULT_DAILY_KM
            var d = today
            while (!d.isAfter(lastDay)) {
                val start = if (d == today) now else d.atStartOfDay(zone)
                val share = Duration.between(start, d.plusDays(1).atStartOfDay(zone)).toMinutes() / MINUTES_PER_DAY
                add(CalendarEntry(EntryKind.ESTIMATE, start, daily * share, "하루 평균 (기록이 2주가 안 돼요)"))
                d = d.plusDays(1)
            }
        }
        for (t in input.trips) {
            val date = t.start.toLocalDate()
            if (t.start.isBefore(now) && !date.isBefore(from) && !date.isAfter(to)) {
                add(CalendarEntry(EntryKind.DRIVEN, t.start, t.distanceKm, "${name(t.from)} → ${name(t.to)}"))
            }
        }
        val plan = result.plan
        plan?.best?.let { best ->
            val at = when (best.kind) {
                SolutionKind.DWELL -> best.dwell?.arriveAt ?: now
                SolutionKind.HOME -> best.startsAt ?: now
                else -> now
            }
            add(CalendarEntry(EntryKind.FILL, at, null, copy.fillTitle(best, plan)))
        }

        // 전망: 오늘부터 하루 끝마다 누적 거리를 판정 규칙(R-07~R-09)에 넣는다.
        val cumulative = mutableMapOf<LocalDate, Double>()
        val outlook = mutableMapOf<LocalDate, Verdict>()
        var needDate: LocalDate? = null
        var needVerdict: Verdict? = null
        var sum = 0.0
        var d = today
        while (d.isBefore(outlookEnd)) {
            sum += byDay[d].orEmpty().filter { it.counts }.sumOf { it.km!! }
            cumulative[d] = sum
            if (needDate == null) {
                val verdict = judge.judge(input.snapshot, sum).verdict
                outlook[d] = verdict
                if (verdict != Verdict.ENOUGH) {
                    needDate = d
                    needVerdict = verdict
                }
            }
            d = d.plusDays(1)
        }

        val days = generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.map { date ->
            val entries = byDay[date].orEmpty().sortedBy { it.at }
            CalendarDay(
                date = date,
                entries = entries,
                plannedKm = entries.filter { it.counts }.sumOf { it.km!! },
                drivenKm = entries.filter { it.kind == EntryKind.DRIVEN }.sumOf { it.km ?: 0.0 },
                cumulativeKm = cumulative[date],
                outlook = outlook[date],
                needDay = date == needDate,
                skipRoutine = date in input.skipDays,
                isPast = date.isBefore(today),
                isToday = date == today,
            )
        }.toList()

        return CalendarView(
            days = days,
            needDate = needDate,
            needVerdict = needVerdict,
            line = copy.outlookLine(needDate, needVerdict, now, outlookDays),
            correctedRangeKm = judge.judge(input.snapshot, 0.0).correctedRangeKm,
            outlookDays = outlookDays,
        )
    }

    private fun title(v: VoiceTrip): String = listOfNotNull(v.label, v.purpose).joinToString(" ")

    companion object {
        /** 에너지 전망 기간(A-81). 패턴은 주 단위라 2주가 넘으면 믿기 어렵다. */
        const val OUTLOOK_DAYS = 14
        private const val MINUTES_PER_DAY = 24.0 * 60.0
    }
}
