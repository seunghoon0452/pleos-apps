package kr.mobility.mirichaeum

import kr.mobility.core.geo.GeoPoint
import kr.mobility.core.geo.PlaceCell
import kr.mobility.core.time.KST
import kr.mobility.core.time.WeekSlot
import kr.mobility.core.vehicle.DrivingState
import java.time.DayOfWeek
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun kst(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) = ZonedDateTime.of(y, mo, d, h, mi, 0, 0, KST)

private val A = PlaceCell.of(GeoPoint(37.25, 127.07))
private val B = PlaceCell.of(GeoPoint(37.40, 127.10))
private val C = PlaceCell.of(GeoPoint(37.30, 127.08))

private fun ev(range: Double, level: Double = 50.0, temp: Double? = 20.0, at: ZonedDateTime = kst(2026, 10, 30, 9)) =
    EnergySnapshot(FuelType.EV, range, level, efficiency = 5.0, capacity = 60.0, outsideTempC = temp, at = at)

/** R-07 ~ R-09 */
class RangeJudgeTest {
    private val judge = RangeJudge()

    @Test
    fun `겨울 보정 - 전기차만, 0도 미만 0_8, 0~10도 0_9`() {
        assertEquals(0.8, judge.winterFactor(FuelType.EV, -0.1))
        assertEquals(0.9, judge.winterFactor(FuelType.EV, 0.0))
        assertEquals(0.9, judge.winterFactor(FuelType.EV, 10.0))
        assertEquals(1.0, judge.winterFactor(FuelType.EV, 10.1))
        assertEquals(1.0, judge.winterFactor(FuelType.ICE, -5.0))
        assertEquals(1.0, judge.winterFactor(FuelType.EV, null))
    }

    @Test
    fun `판정 경계 - 권장(필요+15%+15%) 이상 충분, 최소(필요+10%) 이상 빠듯, 미만 부족`() {
        // 여유분 = 100 × 15% + 60kWh × 15% × 5km/kWh = 15 + 45 = 60
        assertEquals(Verdict.ENOUGH, judge.judge(ev(160.0), 100.0).verdict)
        assertEquals(Verdict.TIGHT, judge.judge(ev(159.9), 100.0).verdict)
        assertEquals(Verdict.TIGHT, judge.judge(ev(130.0), 100.0).verdict)
        assertEquals(Verdict.SHORT, judge.judge(ev(129.9), 100.0).verdict)
        assertEquals(60.0, judge.judge(ev(100.0), 100.0).shortfallKm, 1e-9)
    }

    @Test
    fun `내연기관 최소 잔량 50km`() {
        val ice = EnergySnapshot(FuelType.ICE, 165.0, 30.0, 12.0, 50.0, null, kst(2026, 10, 30, 9))
        val j = judge.judge(ice, 100.0)
        assertEquals(50.0, j.reserveKm)
        assertEquals(Verdict.ENOUGH, j.verdict)
    }

    @Test
    fun `추운 날은 같은 배터리로도 판정이 내려간다`() {
        assertEquals(Verdict.ENOUGH, judge.judge(ev(170.0, temp = 15.0), 100.0).verdict)
        assertEquals(Verdict.TIGHT, judge.judge(ev(170.0, temp = -3.0), 100.0).verdict)
    }
}

/** R-03 */
class PatternLearnerTest {
    private val learner = PatternLearner()
    private val now = kst(2026, 10, 30, 12) // 금요일

    private fun trip(start: ZonedDateTime, to: PlaceCell = B, km: Double = 20.0) =
        TripRecord(start, start.plusMinutes(30), A, to, km)

    @Test
    fun `4주 중 3주 나타나면 평소 이동, 확신도 0_75`() {
        val trips = listOf(1L, 2L, 3L).map { trip(kst(2026, 10, 27, 8).minusWeeks(it - 1)) } // 화요일 8시
        val p = learner.learnTrips(trips, now).single()
        assertEquals(WeekSlot(DayOfWeek.TUESDAY, 4), p.slot)
        assertEquals(0.75, p.confidence)
    }

    @Test
    fun `2주만 나타나면 평소 이동이 아니다`() {
        val trips = listOf(trip(kst(2026, 10, 27, 8)), trip(kst(2026, 10, 20, 8)))
        assertTrue(learner.learnTrips(trips, now).isEmpty())
    }

    @Test
    fun `4주보다 오래된 기록은 세지 않는다`() {
        val trips = listOf(0L, 1L, 4L, 5L).map { trip(kst(2026, 10, 27, 8).minusWeeks(it)) }
        assertTrue(learner.learnTrips(trips, now).isEmpty())
    }

    @Test
    fun `같은 주에 두 번 가도 1주로 센다, 거리는 중앙값`() {
        val trips = listOf(
            trip(kst(2026, 10, 27, 8), km = 18.0),
            trip(kst(2026, 10, 27, 9), km = 30.0),
            trip(kst(2026, 10, 20, 8), km = 20.0),
            trip(kst(2026, 10, 13, 8), km = 22.0),
        )
        val p = learner.learnTrips(trips, now).single()
        assertEquals(3, p.weeksSeen)
        assertEquals(21.0, p.typicalKm)
    }

    @Test
    fun `집과 회사 추정`() {
        val dwells = (0L..9L).flatMap { d ->
            val day = kst(2026, 10, 19, 0).plusDays(d)
            if (day.dayOfWeek.value > 5) {
                listOf(DwellRecord(A, day.minusHours(5), day.plusHours(8)))
            } else {
                listOf(
                    DwellRecord(A, day.minusHours(5), day.plusHours(8)),
                    DwellRecord(B, day.plusHours(9), day.plusHours(18)),
                    DwellRecord(C, day.plusHours(19), day.plusHours(20)),
                )
            }
        }
        val roles = PlaceRoles.infer(dwells)
        assertEquals(PlaceRole.HOME, roles[A])
        assertEquals(PlaceRole.WORK, roles[B])
        assertEquals(PlaceRole.OTHER, roles[C])
    }
}

/** R-04, R-05, R-15 */
class DemandPredictorTest {
    private val predictor = DemandPredictor()
    private val now = kst(2026, 10, 30, 12) // 금요일 정오

    private fun pattern(day: DayOfWeek, slot: Int, km: Double, weeks: Int = 3) =
        RoutinePattern(WeekSlot(day, slot), A, B, km, weeks, 4)

    @Test
    fun `48시간 안의 확신도 75퍼센트 이상 패턴만 더한다`() {
        val patterns = listOf(
            pattern(DayOfWeek.FRIDAY, 9, 20.0),
            pattern(DayOfWeek.SATURDAY, 5, 60.0),
            pattern(DayOfWeek.SATURDAY, 8, 60.0, weeks = 2),
            pattern(DayOfWeek.MONDAY, 4, 19.0),
        )
        assertEquals(80.0, predictor.predict(now, patterns, emptyList()).totalKm)
    }

    @Test
    fun `음성 일정은 왕복으로 더하고, 장소 미정이면 따로 둔다`() {
        val v1 = VoiceTrip("1", kst(2026, 10, 31, 15), "대전", "대전 정부청사", null, oneWayKm = 150.0)
        val v2 = VoiceTrip("2", kst(2026, 10, 31, 18), "카페", null, null)
        val v3 = VoiceTrip("3", kst(2026, 11, 5, 9), "부산", "부산역", null, oneWayKm = 400.0)
        val d = predictor.predict(now, emptyList(), listOf(v1, v2, v3))
        assertEquals(300.0, d.totalKm)
        assertEquals(listOf(v2), d.unresolvedVoiceTrips)
        assertEquals(DemandSource.VOICE, d.mainItem?.source)
    }

    @Test
    fun `진행 중인 시간대에 이미 다녀온 이동은 빼고, 아직이면 넣는다`() {
        val p = pattern(DayOfWeek.FRIDAY, 6, 20.0) // 12~14시
        val done = TripRecord(kst(2026, 10, 30, 12, 0).minusMinutes(0), kst(2026, 10, 30, 12, 30), A, B, 20.0)
        val later = now.plusMinutes(40)
        assertEquals(0.0, predictor.predict(later, listOf(p), emptyList(), listOf(done)).totalKm)
        assertEquals(20.0, predictor.predict(now, listOf(p), emptyList(), emptyList()).totalKm)
    }

    @Test
    fun `기록이 적을 때는 하루 평균 x 2일`() {
        val d = predictor.estimate(now, dailyKm = 35.0)
        assertEquals(70.0, d.totalKm)
        assertEquals(DemandSource.ESTIMATE, d.items.single().source)
    }

    @Test
    fun `지난 음성 일정은 자동 삭제`() {
        val past = VoiceTrip("p", now.minusMinutes(1), "x", null, null)
        val future = VoiceTrip("f", now.plusDays(3), "y", null, null)
        assertEquals(listOf(future), VoiceTripBook.prune(listOf(past, future), now))
    }
}

/** R-11, R-12 */
class ChargePlannerTest {
    private val planner = ChargePlanner()
    private val judge = RangeJudge()
    private val now = kst(2026, 10, 30, 18)
    private val dwellPlace = PlaceCell.of(GeoPoint(37.3, 127.08))
    private val slow = Charger("s", "마트 완속", dwellPlace.center, 7.0, available = 1)
    private val fastNear = RouteCharger(Charger("f1", "경로상 급속", GeoPoint(37.35, 127.09), 100.0, 1), 2.0)
    private val fastCheap = RouteCharger(Charger("f2", "경로상 급속 50", GeoPoint(37.36, 127.09), 50.0, 1), 3.0)
    private val detour = RouteCharger(Charger("f3", "우회 초급속", GeoPoint(37.2, 127.2), 200.0, 2), 12.0)

    private fun demand(km: Double, at: ZonedDateTime = now.plusHours(16)) =
        Demand(now, now.plusHours(48), listOf(DemandItem(DemandSource.ROUTINE, at, km)))

    private fun dwell(minutes: Double, at: ZonedDateTime = now.plusMinutes(30)) =
        UpcomingDwell(dwellPlace, "마트", at, minutes, 3)

    @Test
    fun `필요 충전량은 5퍼센트 단위로 올림`() {
        val snap = ev(100.0, level = 40.0, at = now)
        val j = judge.judge(snap, 100.0) // 여유분 60 → 60km 부족 → 12kWh → 20%
        val plan = assertNotNull(planner.plan(snap, j, demand(100.0), routeChargers = listOf(fastNear)))
        assertEquals(20.0, plan.percent, 1e-9)
        assertEquals(12.0, plan.amount, 1e-9)
    }

    @Test
    fun `체류 충전이 먼저, 그다음 경로상 가격순, 마지막 우회`() {
        val snap = ev(150.0, level = 50.0, at = now)
        val j = judge.judge(snap, 100.0) // 10km 부족 → 2kWh → 5% = 3kWh, 7kW × 40분 × 0.9 = 4.2kWh
        val plan = assertNotNull(
            planner.plan(snap, j, demand(100.0), listOf(dwell(40.0)), listOf(slow), listOf(detour, fastNear, fastCheap)),
        )
        assertEquals(listOf("마트", "경로상 급속 50", "경로상 급속", "우회 초급속"), plan.solutions.map { it.placeName })
        assertEquals(0.0, plan.solutions.first().extraMinutes)
    }

    @Test
    fun `30분 미만 체류, 300m 밖 충전기, 사용 중 충전기, 마감 뒤 체류는 제외`() {
        val snap = ev(150.0, level = 50.0, at = now)
        val j = judge.judge(snap, 100.0)
        fun dwellKinds(d: UpcomingDwell, c: Charger) =
            planner.plan(snap, j, demand(100.0), listOf(d), listOf(c))!!.solutions.map { it.kind }
        assertTrue(dwellKinds(dwell(25.0), slow).isEmpty())
        assertTrue(dwellKinds(dwell(40.0), slow.copy(location = GeoPoint(37.31, 127.08))).isEmpty())
        assertTrue(dwellKinds(dwell(40.0), slow.copy(available = 0)).isEmpty())
        assertTrue(dwellKinds(dwell(40.0, at = now.plusHours(20)), slow).isEmpty())
    }

    @Test
    fun `체류 시간 동안 다 못 채우면 체류 충전을 내지 않는다`() {
        val snap = ev(100.0, level = 40.0, at = now)
        val j = judge.judge(snap, 100.0) // 12kWh 필요
        val plan = assertNotNull(planner.plan(snap, j, demand(100.0), listOf(dwell(60.0)), listOf(slow)))
        assertTrue(plan.solutions.none { it.kind == SolutionKind.DWELL })
    }

    @Test
    fun `충분하면 계획이 없다`() {
        val snap = ev(300.0, at = now)
        assertNull(planner.plan(snap, judge.judge(snap, 100.0), demand(100.0)))
    }

    @Test
    fun `마감은 누적 거리가 여유를 넘는 첫 이동`() {
        val snap = ev(150.0, level = 50.0, at = now)
        val d = Demand(
            now, now.plusHours(48),
            listOf(
                DemandItem(DemandSource.ROUTINE, now.plusHours(1), 20.0),
                DemandItem(DemandSource.ROUTINE, now.plusHours(16), 80.0),
            ),
        )
        val j = judge.judge(snap, d.totalKm)
        assertEquals(now.plusHours(16), planner.deadline(d, j))
    }
}

/** R-10 */
class AlertPolicyTest {
    private val policy = AlertPolicy()
    private val now = kst(2026, 10, 30, 18)

    @Test
    fun `초록은 말하지 않고, 주행 중에는 새 안내를 시작하지 않는다`() {
        assertEquals(AlertChannel.CARD_ONLY, policy.decide(AlertTrigger.TRIP_START, DrivingState.PARKED, Verdict.ENOUGH, "k", now, emptyList()).channel)
        assertEquals(AlertChannel.CARD_ONLY, policy.decide(AlertTrigger.TRIP_START, DrivingState.DRIVING, Verdict.SHORT, "k", now, emptyList()).channel)
        assertEquals(AlertChannel.CARD_ONLY, policy.decide(AlertTrigger.TRIP_START, DrivingState.STOPPED, Verdict.SHORT, "k", now, emptyList()).channel)
        assertEquals(AlertChannel.VOICE, policy.decide(AlertTrigger.TRIP_START, DrivingState.PARKED, Verdict.TIGHT, "k", now, emptyList()).channel)
    }

    @Test
    fun `하루 2회, 같은 안내 반복 금지, 다음 날은 다시 가능`() {
        val sent = listOf(SentAlert(now.minusHours(9), "a"), SentAlert(now.minusHours(2), "b"))
        assertEquals(AlertChannel.CARD_ONLY, policy.decide(AlertTrigger.TRIP_START, DrivingState.PARKED, Verdict.SHORT, "c", now, sent).channel)
        assertEquals(AlertChannel.CARD_ONLY, policy.decide(AlertTrigger.TRIP_START, DrivingState.PARKED, Verdict.SHORT, "b", now, sent.takeLast(1)).channel)
        assertEquals(AlertChannel.VOICE, policy.decide(AlertTrigger.TRIP_START, DrivingState.PARKED, Verdict.SHORT, "b", now.plusDays(1), sent).channel)
    }
}

/** R-02 */
class OdometerFallbackTest {
    @Test
    fun `총 주행거리 차이를 날짜에 고르게 나눈다`() {
        val readings = listOf(
            OdometerReading(kst(2026, 10, 26, 0), 10_000.0),
            OdometerReading(kst(2026, 10, 28, 0), 10_080.0),
        )
        val byDate = OdometerFallback.kmByDate(readings)
        assertEquals(40.0, byDate.getValue(kst(2026, 10, 26, 0).toLocalDate()), 1e-9)
        assertEquals(40.0, byDate.getValue(kst(2026, 10, 27, 0).toLocalDate()), 1e-9)
        assertEquals(40.0, OdometerFallback.dailyAverage(readings)!!, 1e-9)
        assertEquals(40.0, OdometerFallback.weekdayAverages(readings).getValue(DayOfWeek.MONDAY), 1e-9)
    }

    @Test
    fun `기록이 2주 미만이면 첫 사용 상태, 하루 거리를 묻는다`() {
        val now = kst(2026, 10, 30, 9)
        val trips = listOf(TripRecord(now.minusDays(5), now.minusDays(5).plusMinutes(30), A, B, 20.0))
        assertTrue(ColdStart.isColdStart(trips, now))
        val r = MiriChaeum().evaluate(MiriInput(now, DrivingState.PARKED, ev(300.0, at = now), trips = trips))
        assertTrue(r.coldStart)
        assertTrue(r.askDailyKm)
        assertEquals(80.0, r.demand.totalKm, 1e-9)
    }
}
