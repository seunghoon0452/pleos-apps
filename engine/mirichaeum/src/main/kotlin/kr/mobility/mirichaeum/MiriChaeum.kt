package kr.mobility.mirichaeum

import kr.mobility.core.geo.PlaceCell
import kr.mobility.core.vehicle.DrivingState
import java.time.LocalDate
import java.time.ZonedDateTime
import kotlin.math.min

/** 한 번 판정하는 데 필요한 모든 입력. 앱은 SDK·저장소·외부 API 값을 모아 이것만 넘긴다. */
data class MiriInput(
    val now: ZonedDateTime,
    val state: DrivingState,
    val snapshot: EnergySnapshot,
    val trips: List<TripRecord> = emptyList(),
    val dwells: List<DwellRecord> = emptyList(),
    val voiceTrips: List<VoiceTrip> = emptyList(),
    val currentPlace: PlaceCell? = null,
    val placeNames: Map<PlaceCell, String> = emptyMap(),
    /** 체류 장소 주변 충전소(공공데이터). */
    val nearbyChargers: List<Charger> = emptyList(),
    /** 경로상·우회 충전소(내비 이탈 시간 포함). */
    val routeChargers: List<RouteCharger> = emptyList(),
    val stations: List<FuelStation> = emptyList(),
    /** 첫 사용 때 받은 "하루에 보통 몇 km" 답. */
    val userDailyKm: Double? = null,
    val odometerReadings: List<OdometerReading> = emptyList(),
    val sentAlerts: List<SentAlert> = emptyList(),
    val trigger: AlertTrigger? = AlertTrigger.TRIP_START,
    /** 설정의 '집 충전 가능' (A-36). */
    val homeCharging: Boolean = false,
    /** 캘린더에서 '이날은 평소대로 안 다녀요'로 고른 날 (F-25). */
    val skipDays: Set<LocalDate> = emptySet(),
)

data class MiriResult(
    val coldStart: Boolean,
    /** 첫 사용: "하루에 보통 몇 km 타세요?"를 물어야 한다 (R-15). */
    val askDailyKm: Boolean,
    val patterns: List<RoutinePattern>,
    val demand: Demand,
    val judgement: Judgement,
    val plan: ChargePlan?,
    /** 화면에 항상 보여주는 판정 이유. */
    val reason: String,
    /** 주행 중 한 줄. */
    val cardLine: String,
    /** 노랑·빨강일 때 읽어줄 문장. */
    val voiceLine: String?,
    /** 안내 끝에 "응"을 기다릴 해결책(R-13). 집 충전처럼 내비에 넣을 것이 없으면 null. */
    val askingSolution: Solution?,
    val alertKey: String,
    val alert: AlertDecision?,
    /** 지난 일정을 지운 이동 일정 목록 (R-05). 저장소에 다시 써야 한다. */
    val voiceTrips: List<VoiceTrip>,
    /** 48시간 안의 일정(목적지)마다 최소·권장 충전량과 충전 시간 (F-26). 앞선 이동을 함께 센 값. */
    val guides: List<TripGuide> = emptyList(),
    /** 등록한 일정마다 그 일정만 다녀올 때의 최소·권장 잔량 (일정 id → 안내). */
    val tripTargets: Map<String, TripGuide> = emptyMap(),
) {
    /** 화면·음성에서 먼저 보여줄 일정: 권장에 못 미치는 첫 일정, 없으면 가장 가까운 일정. */
    val focusGuide: TripGuide? get() = guides.firstOrNull { it.verdict != Verdict.ENOUGH } ?: guides.firstOrNull()

    /** "○○까지 충분해요"에 쓰는 일정: 권장에 못 미치는 첫 일정, 모두 충분하면 48시간 안의 마지막 일정. */
    val untilGuide: TripGuide? get() = guides.firstOrNull { it.verdict != Verdict.ENOUGH } ?: guides.lastOrNull()
}

/** 미리 채움 엔진의 입구. 순수 계산이라 차·에뮬레이터 없이 테스트할 수 있다. */
class MiriChaeum(
    private val learner: PatternLearner = PatternLearner(),
    private val predictor: DemandPredictor = DemandPredictor(),
    private val judge: RangeJudge = RangeJudge(),
    private val planner: ChargePlanner = ChargePlanner(judge),
    private val policy: AlertPolicy = AlertPolicy(),
    private val copy: Copywriter = Copywriter(),
    private val guidePlanner: TripGuidePlanner = TripGuidePlanner(judge),
    private val calendar: EnergyCalendar = EnergyCalendar(predictor, judge, copy, guidePlanner = guidePlanner),
) {
    fun evaluate(input: MiriInput): MiriResult {
        val now = input.now
        val voiceTrips = VoiceTripBook.prune(input.voiceTrips, now)
        val cold = ColdStart.isColdStart(input.trips, now)
        val patterns = if (cold) emptyList() else learner.learnTrips(input.trips, now)
        val demand = if (cold) {
            val daily = input.userDailyKm ?: OdometerFallback.dailyAverage(input.odometerReadings) ?: ColdStart.DEFAULT_DAILY_KM
            predictor.estimate(now, daily, voiceTrips)
        } else {
            predictor.predict(now, patterns, voiceTrips, input.trips, input.skipDays)
        }
        val judgement = judge.judge(input.snapshot, demand.totalKm)
        val dwellPatterns = if (cold) emptyList() else learner.learnDwells(input.dwells, now)
        val dwells = planner.upcomingDwells(dwellPatterns, now, demand.until, input.placeNames)
        val homeCell = if (input.homeCharging) PlaceRoles.infer(input.dwells).entries.firstOrNull { it.value == PlaceRole.HOME }?.key else null
        val plan = planner.plan(
            input.snapshot, judgement, demand, dwells, input.nearbyChargers, input.routeChargers, input.stations, homeCell,
        )
        val roles = PlaceRoles.infer(input.dwells)
        val guides = guidePlanner.guides(input.snapshot, demand, roles, input.placeNames)
        val focus = guides.firstOrNull { it.verdict != Verdict.ENOUGH } ?: guides.firstOrNull()
        val reason = copy.reason(demand)
        val voiceLine = copy.voiceAlert(
            judgement, plan, demand, now, focus,
            routeWord = routeWord(input),
            stationAveragePrice = input.stations.map { it.pricePerLiter }.takeIf { it.isNotEmpty() }?.average(),
        )
        val key = "${judgement.verdict}:$reason"
        return MiriResult(
            coldStart = cold,
            askDailyKm = cold && input.userDailyKm == null,
            patterns = patterns,
            demand = demand,
            judgement = judgement,
            plan = plan,
            reason = reason,
            cardLine = copy.cardLine(judgement, focus, now),
            voiceLine = voiceLine,
            askingSolution = plan?.best?.takeIf { it.kind != SolutionKind.HOME },
            alertKey = key,
            alert = input.trigger?.let { policy.decide(it, input.state, judgement.verdict, key, now, input.sentAlerts) },
            voiceTrips = voiceTrips,
            guides = guides,
            tripTargets = tripTargets(input.snapshot, voiceTrips, now),
        )
    }

    /** 등록한 일정 하나만 다녀올 때 필요한 최소·권장 잔량 (말로 등록한 뒤 안내, 일정 목록). */
    fun tripTarget(snapshot: EnergySnapshot, trip: VoiceTrip, now: ZonedDateTime): TripGuide? {
        val km = trip.roundTripKm ?: return null
        val at = trip.nextAt(now) ?: trip.at
        return guidePlanner.standalone(snapshot, TripEvent(GuideSource.PLANNED, at, trip.label, km, km, trip = trip))
    }

    private fun tripTargets(snapshot: EnergySnapshot, trips: List<VoiceTrip>, now: ZonedDateTime): Map<String, TripGuide> =
        trips.mapNotNull { t -> tripTarget(snapshot, t, now)?.let { t.id to it } }.toMap()

    /** F-22 캘린더: [from]~[to] 날짜 칸과 오늘부터 2주 전망. [result]는 같은 [input]으로 판정한 값. */
    fun calendar(input: MiriInput, result: MiriResult, from: LocalDate, to: LocalDate): CalendarView =
        calendar.build(input, result, from, to)

    /** 체류 충전 후보 장소에 도착했을 때 한 번 더 알려줄지 (R-10 ②). */
    fun onArrival(
        result: MiriResult,
        place: PlaceCell,
        state: DrivingState,
        now: ZonedDateTime,
        sent: List<SentAlert>,
    ): Pair<AlertDecision, String>? {
        val solution = result.plan?.solutions?.firstOrNull { it.kind == SolutionKind.DWELL && it.dwell?.place == place }
            ?: return null
        val key = arrivalKey(place)
        val decision = policy.decide(AlertTrigger.ARRIVED_AT_DWELL, state, result.judgement.verdict, key, now, sent)
        val until = result.untilGuide?.let { copy.guideWhen(it, now) } ?: copy.busiestDay(result.demand)?.first?.let { copy.dayPhrase(it, now) } ?: "48시간"
        return decision to copy.arrivalLine(solution, until)
    }

    /**
     * 충전 중 화면 (S5): 목표 %, 남은 시간, 목표까지 채우면 충분한지.
     * [fixedTarget]은 충전을 시작할 때 정한 목표(충전이 진행되면 계획이 바뀌므로 고정한다).
     * [powerKnown]이 false면 출력을 추정한 것이라 시간에 '약'을 붙인다.
     */
    fun chargingStatus(
        snapshot: EnergySnapshot,
        result: MiriResult,
        chargerPowerKw: Double,
        now: ZonedDateTime,
        fixedTarget: Double? = null,
        powerKnown: Boolean = true,
    ): ChargingStatus {
        val target = fixedTarget ?: result.plan?.let { min(100.0, snapshot.levelPercent + it.percent) }
            ?: maxOf(snapshot.levelPercent, DEFAULT_TARGET_PERCENT)
        val kwh = (target - snapshot.levelPercent).coerceAtLeast(0.0) / 100.0 * snapshot.capacity
        val factor = if (chargerPowerKw < Tariff.FAST_MIN_KW) 0.9 else 0.85
        val minutes = kwh / (chargerPowerKw * factor) * 60.0
        val atTarget = if (snapshot.levelPercent > 0) {
            snapshot.copy(rangeKm = snapshot.rangeKm / snapshot.levelPercent * target, levelPercent = target)
        } else {
            snapshot
        }
        val j = judge.judge(atTarget, result.judgement.needKm)
        val focus = result.untilGuide
        val until = focus?.let { copy.guideWhen(it, now) } ?: copy.busiestDay(result.demand)?.first?.let { copy.dayPhrase(it, now) } ?: "48시간"
        val enough = j.verdict == Verdict.ENOUGH
        val reached = snapshot.levelPercent >= target - 0.01
        val line = if (reached) {
            copy.chargingReached(enough, until, j.shortfallKm)
        } else {
            copy.chargingTarget(target, minutes, enough, until, j.shortfallKm, approximate = !powerKnown, minimum = focus?.minimum, levelNow = snapshot.levelPercent, minutesPerPercent = if (target > snapshot.levelPercent) minutes / (target - snapshot.levelPercent) else 0.0)
        }
        return ChargingStatus(target, minutes, enough, line, reached)
    }

    private fun routeWord(input: MiriInput): String {
        val role = input.currentPlace?.let { PlaceRoles.infer(input.dwells)[it] }
        val hour = input.now.hour
        return when {
            role == PlaceRole.WORK && hour >= 15 -> "퇴근길"
            role == PlaceRole.HOME && hour < 12 && input.now.dayOfWeek.value <= 5 -> "출근길"
            else -> "가는 길"
        }
    }

    companion object {
        /** 도착 안내 기록에 쓰는 이름. 앱이 저장할 때도 같은 값을 쓴다. */
        fun arrivalKey(place: PlaceCell): String = "arrive:${place.row}:${place.col}"

        /** 계획이 없을 때 충전 목표(가안). */
        const val DEFAULT_TARGET_PERCENT = 80.0
    }
}

data class ChargingStatus(val targetPercent: Double, val minutesLeft: Double, val enough: Boolean, val line: String, val reached: Boolean = false)
