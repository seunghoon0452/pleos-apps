package kr.mobility.mirichaeum

import kr.mobility.core.text.Korean
import kr.mobility.core.time.KoreanTimeWords
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * 화면 한 줄·음성 안내 문구. 주행 중 화면은 1~2초 안에 읽혀야 하므로 한 줄은 짧게 쓴다.
 * 문구 예시는 서비스 정의서 5장(S1~S5)을 따른다.
 */
class Copywriter {
    private val longTripKm = 80.0

    /** 100km 이상은 10km 단위로 말한다. "약 310km". */
    fun approxKm(km: Double): String =
        if (km >= 100) "${((km / 10).roundToLong() * 10)}km" else "${km.roundToLong()}km"

    /** 잔량 %. 출발 전에 맞출 값이라 올림한다. 100%를 넘으면 한 번에 채울 수 없다. */
    fun levelText(t: ChargeTarget): String = if (t.overFull) "100% 이상" else "${ceil(t.levelPercent - 1e-9).toInt()}%"

    /** 채우는 시간. 채울 것이 있으면 최소 1분으로 말한다. */
    fun fillTime(minutes: Double): String = Korean.minutes(if (minutes > 0) maxOf(1.0, ceil(minutes)) else 0.0)

    /** 일정을 부르는 말. 예: "주말 양평", "일요일 강릉역". */
    fun guideWhen(g: TripGuide, now: ZonedDateTime): String = "${dayPhrase(g.at.toLocalDate(), now)} ${g.title}"

    /** 최소·권장 한 줄 설명. 예: "+5%p · 급속 약 3분 · 완속 약 32분", "지금으로 충분", "가는 길에 충전 필요". */
    fun targetDetail(g: TripGuide, t: ChargeTarget): String = when {
        t.overFull -> if (g.fuelType == FuelType.ICE) "한 번 주유로 모자라요 · 가는 길에 주유 필요" else "한 번 충전으로 모자라요 · 가는 길에 충전 필요"
        t.reached -> "지금으로 충분"
        g.fuelType == FuelType.ICE -> "+${ceil(t.addPercent).toInt()}%p · 약 ${ceil(t.addAmount).toInt()}L · 주유 약 ${fillTime(t.fastMinutes)}"
        else -> "+${ceil(t.addPercent).toInt()}%p · 급속 약 ${fillTime(t.fastMinutes)} · 완속 약 ${fillTime(t.slowMinutes ?: 0.0)}"
    }

    /**
     * 일정별 안내의 앞부분 (F-26): 어떤 일정 전에 최소·권장 몇 %가 필요한지, 지금 잔량, 채우는 시간.
     * 예: "주말 양평 왕복 약 120km 전까지 최소 52%, 권장 63%가 필요해요. 지금 58%라 권장량에 조금 못 미쳐요. 권장량까지 급속 약 3분, 완속 약 35분이에요."
     */
    fun guideOpening(g: TripGuide, now: ZonedDateTime): String {
        val need = "${guideWhen(g, now)} 왕복 약 ${approxKm(g.roundTripKm)} 전까지 최소 ${levelText(g.minimum)}, 권장 ${levelText(g.recommended)}가 필요해요."
        val state = "지금 ${g.currentPercent.roundToInt()}%라 ${if (g.verdict == Verdict.SHORT) "최소량도 모자라요" else "권장량에 조금 못 미쳐요"}."
        val time = when {
            g.recommended.overFull -> if (g.fuelType == FuelType.ICE) "한 번 주유로는 모자라 가는 길에 한 번 더 주유해야 해요." else "한 번 충전으로는 모자라 가는 길에 한 번 더 충전해야 해요."
            g.fuelType == FuelType.ICE -> "권장량까지 약 ${ceil(g.recommended.addAmount).toInt()}L, 주유는 약 ${fillTime(g.recommended.fastMinutes)}이면 돼요."
            g.minimum.reached -> "권장량까지 급속 약 ${fillTime(g.recommended.fastMinutes)}, 완속 약 ${fillTime(g.recommended.slowMinutes ?: 0.0)}이에요."
            else -> "최소량까지 급속 약 ${fillTime(g.minimum.fastMinutes)}, 권장량까지 급속 약 ${fillTime(g.recommended.fastMinutes)}이에요."
        }
        return "$need $state $time"
    }

    /** 그날을 부르는 말: 오늘, 내일, 주말, 또는 요일. */
    fun dayPhrase(target: LocalDate, now: ZonedDateTime): String {
        val today = now.toLocalDate()
        val days = ChronoUnit.DAYS.between(today, target)
        val weekend = target.dayOfWeek == DayOfWeek.SATURDAY || target.dayOfWeek == DayOfWeek.SUNDAY
        val todayIsWeekday = today.dayOfWeek.value <= 5
        return when {
            days == 0L -> "오늘"
            days == 1L && !(weekend && todayIsWeekday) -> "내일"
            weekend && todayIsWeekday && days <= 2 -> "주말"
            else -> KoreanTimeWords.dayName(target.dayOfWeek)
        }
    }

    /** 가장 많이 달릴 날과 그날 항목. */
    fun busiestDay(demand: Demand): Pair<LocalDate, List<DemandItem>>? =
        demand.items.groupBy { it.at.toLocalDate() }.maxByOrNull { (_, list) -> list.sumOf { it.km } }?.toPair()

    private fun tripWord(km: Double) = if (km >= longTripKm) "장거리" else "이동"

    /** 판정 이유. 예: "최근 4주 중 3주, 토요일 오전 장거리(약 120km)". */
    fun reason(demand: Demand): String {
        val (date, items) = busiestDay(demand) ?: return "48시간 안에 평소 이동이 없어요"
        val dayKm = items.sumOf { it.km }
        items.firstOrNull { it.source == DemandSource.VOICE }?.let { item ->
            val v = item.voiceTrip!!
            val what = listOfNotNull(v.label, v.purpose).joinToString(" ")
            return "${item.at.monthValue}월 ${item.at.dayOfMonth}일 $what(왕복 약 ${approxKm(v.roundTripKm ?: 0.0)})"
        }
        items.firstOrNull { it.source == DemandSource.ESTIMATE }?.let {
            return "아직 기록이 적어 하루 평균 ${approxKm(it.km / 2)}로 계산했어요"
        }
        val routine = items.mapNotNull { it.pattern }
        val weeks = routine.minOf { it.weeksSeen }
        val window = routine.first().windowWeeks
        val part = KoreanTimeWords.partOfDay(items.minOf { it.at }.hour)
        return "최근 ${window}주 중 ${weeks}주, ${KoreanTimeWords.dayName(date.dayOfWeek)} $part ${tripWord(dayKm)}(약 ${approxKm(dayKm)})"
    }

    /** 주행 중 한 줄 (SC-05). 권장에 못 미치는 일정이 있으면 그 일정 전의 최소·권장 잔량을 말한다. */
    fun cardLine(j: Judgement, focus: TripGuide?, now: ZonedDateTime): String {
        if (focus != null && focus.verdict != Verdict.ENOUGH) {
            return "${guideWhen(focus, now)} 전 최소 ${levelText(focus.minimum)} · 권장 ${levelText(focus.recommended)}"
        }
        val day = focus?.let { dayPhrase(it.at.toLocalDate(), now) }
        return when (j.verdict) {
            Verdict.ENOUGH -> "앞으로 48시간 충분해요"
            Verdict.TIGHT -> "${day ?: "48시간"}까지 여유 ${approxKm(j.shortfallKm)} 모자라요"
            Verdict.SHORT -> "${day ?: "48시간"}까지 ${approxKm(j.needKm - j.correctedRangeKm)} 부족해요"
        }
    }

    /**
     * 운행 시작 음성 안내 (S1, S4). [routeWord]는 "퇴근길"처럼 지금 가는 길을 부르는 말.
     * [stationAveragePrice]는 주유소 가격 비교용(주변 평균).
     */
    fun voiceAlert(
        j: Judgement,
        plan: ChargePlan?,
        demand: Demand,
        now: ZonedDateTime,
        focus: TripGuide? = null,
        routeWord: String = "가는 길",
        stationAveragePrice: Double? = null,
    ): String? {
        if (j.verdict == Verdict.ENOUGH) return null
        val busiest = busiestDay(demand)
        val day = busiest?.first?.let { dayPhrase(it, now) } ?: "48시간"
        val dayKm = busiest?.second?.sumOf { it.km } ?: 0.0
        val verdictWord = if (j.verdict == Verdict.TIGHT) "빠듯해요" else "부족해요"
        val voiceTrip = busiest?.second?.firstOrNull { it.source == DemandSource.VOICE }?.voiceTrip
        val isIce = plan?.fuelType == FuelType.ICE

        val opening = if (focus != null && focus.verdict != Verdict.ENOUGH) guideOpening(focus, now) else when {
            isIce -> "$day ${tripWord(dayKm)}까지 생각하면 오늘 채우시는 게 좋아요."
            voiceTrip != null -> "$day ${voiceTrip.label} 일정까지 생각하면 $verdictWord."
            busiest?.second?.any { it.source == DemandSource.ESTIMATE } == true -> "평소처럼 다니시면 $verdictWord."
            else -> "${day}에 평소처럼 ${Korean.eulReul(tripWord(dayKm))} 가시면 $verdictWord."
        }
        val best = plan?.best ?: return "$opening 가까운 충전소를 찾지 못했어요. 차를 세운 뒤 화면에서 확인해 주세요."
        val percent = "${plan.percent.roundToInt()}%"
        val action = when (best.kind) {
            SolutionKind.HOME -> {
                val night = best.startsAt?.let { dayPhrase(it.toLocalDate(), now) } ?: "오늘"
                "$night 밤 집에서 $percent 충전하면 권장량이 돼요."
            }
            SolutionKind.DWELL -> {
                val d = best.dwell!!
                val dwellDay = KoreanTimeWords.dayName(d.arriveAt.dayOfWeek)
                "${dwellDay}마다 들르시는 ${best.placeName}에 완속 충전기가 있어요. " +
                    "머무시는 ${Korean.minutes(d.expectedMinutes)} 동안 충전하면 권장량까지 채워져요. 그곳으로 안내할까요?"
            }
            SolutionKind.ON_ROUTE -> if (isIce) {
                val diff = stationAveragePrice?.let { (it - best.unitPrice).roundToInt() } ?: 0
                val cheaper = if (diff >= 10) "${Korean.iGa(best.placeName)} L당 ${diff}원 저렴해요." else "${best.placeName}에서 채우시면 돼요."
                "$routeWord $cheaper 경유지로 넣을까요?"
            } else {
                "$routeWord ${best.placeName}에서 $percent 채우면 권장량이 돼요. " +
                    "${best.powerKw?.roundToInt()}kW로 약 ${Korean.minutes(best.fillMinutes)}이에요. 경유지로 넣을까요?"
            }
            SolutionKind.DETOUR ->
                "가까운 ${best.placeName}에서 ${if (isIce) "주유" else "$percent 충전"}가 필요해요. " +
                    "약 ${Korean.minutes(best.extraMinutes)} 더 걸려요. 경유지로 넣을까요?"
        }
        return "$opening $action"
    }

    /** 체류 충전 후보 장소에 도착했을 때 (R-10 ②). */
    fun arrivalLine(solution: Solution, untilPhrase: String): String =
        "${solution.placeName}에 도착했어요. 근처 완속 충전기에서 ${Korean.minutes(solution.dwell?.expectedMinutes ?: solution.fillMinutes)} 충전하면 ${untilPhrase} 일정 권장량까지 채워져요."

    /** 음성 일정 등록 후 (S2-3). */
    fun voiceTripRegistered(trip: VoiceTrip, reminder: Reminder?, target: TripGuide? = null): String {
        val km = trip.roundTripKm ?: return "등록했어요. 장소를 고르면 거리를 계산할게요."
        val distance = "왕복 약 ${approxKm(km)}"
        val need = target?.let { g ->
            if (g.recommended.overFull) " 한 번 충전으로는 모자라 가는 길에 충전이 필요해요."
            else " 출발할 때 최소 ${levelText(g.minimum)}, 권장 ${levelText(g.recommended)}가 필요해요."
        } ?: ""
        return when {
            trip.weekly -> "등록했어요. 매주 ${Korean.dayName(trip.at.dayOfWeek)} ${Korean.iya(distance)}.$need"
            reminder != null -> "등록했어요. ${Korean.iya(distance)}.$need ${reminder.phrase} 다시 알려드릴게요."
            else -> "등록했어요. ${Korean.iya(distance)}.$need 출발 전에 다시 알려드릴게요."
        }
    }

    /**
     * 캘린더 맨 위 한 줄 (F-22): 충전·주유를 하지 않고 다니면 언제 모자라지는지.
     * [needDate]가 없으면 전망 기간 동안 충분하다.
     */
    fun outlookLine(needDate: LocalDate?, verdict: Verdict?, now: ZonedDateTime, outlookDays: Int): String {
        if (needDate == null || verdict == null) return "앞으로 ${outlookDays / 7}주 동안 채우지 않아도 충분해요"
        val days = ChronoUnit.DAYS.between(now.toLocalDate(), needDate)
        val day = when (days) {
            0L -> "오늘"
            1L -> "내일"
            else -> "${Korean.spokenDate(needDate)}에"
        }
        return "채우지 않으면 $day ${if (verdict == Verdict.SHORT) "부족해져요" else "빠듯해져요"}"
    }

    /** 캘린더에 표시하는 채우기 제안 한 줄. 예: "동네 마트에서 40분 충전". */
    fun fillTitle(best: Solution, plan: ChargePlan): String {
        if (plan.fuelType == FuelType.ICE) return "${best.placeName}에서 주유"
        val percent = "${plan.percent.roundToInt()}%"
        return when (best.kind) {
            SolutionKind.HOME -> "집에서 밤사이 $percent 충전"
            SolutionKind.DWELL -> "${best.placeName}에서 ${Korean.minutes(best.dwell?.expectedMinutes ?: best.fillMinutes)} 충전"
            else -> "${best.placeName}에서 $percent 충전"
        }
    }

    /** 월간 리포트 (SC-09). */
    fun monthly(report: SavingsReport): String {
        if (report.count == 0) return "이번 달은 아직 미리 채움 기록이 없어요."
        val won = Korean.wonSpoken(report.savedWon)
        val avoided = if (report.avoidedDetours > 0) "급속 우회 ${report.avoidedDetours}번을 줄여 " else ""
        return "이번 달 미리 채움으로 $avoided${Korean.minutes(report.savedMinutes)}, ${Korean.eulReul(won)} 아꼈어요."
    }

    /** 충전 중 화면 (S5, SC-08). [approximate]면 출력을 추정한 값이라 '약'을 붙인다. */
    fun chargingTarget(
        targetPercent: Double,
        minutesLeft: Double,
        enough: Boolean,
        untilPhrase: String,
        missingKm: Double = 0.0,
        approximate: Boolean = false,
        minimum: ChargeTarget? = null,
        levelNow: Double = 0.0,
        minutesPerPercent: Double = 0.0,
    ): String {
        val about = if (approximate) "약 " else ""
        val head = "권장 ${targetPercent.roundToInt()}%까지 $about${Korean.minutes(minutesLeft)}."
        val min = minimum?.takeIf { !it.overFull && it.levelPercent > levelNow && it.levelPercent < targetPercent }?.let {
            " 최소 ${levelText(it)}까지는 $about${fillTime((it.levelPercent - levelNow) * minutesPerPercent)}."
        } ?: ""
        return if (enough) "$head$min 이대로면 $untilPhrase 일정까지 충분해요."
        else "$head$min $untilPhrase 일정에는 ${approxKm(missingKm)} 모자라요."
    }

    /** 목표에 도달했을 때 (A-46). */
    fun chargingReached(enough: Boolean, untilPhrase: String, missingKm: Double = 0.0): String =
        if (enough) "권장량에 도달했어요. ${untilPhrase}까지 충분해요."
        else "권장량에 도달했어요. $untilPhrase 일정에는 ${approxKm(missingKm)} 모자라요."
}
