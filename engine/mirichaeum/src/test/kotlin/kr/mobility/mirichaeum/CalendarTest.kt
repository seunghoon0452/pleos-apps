package kr.mobility.mirichaeum

import kr.mobility.core.geo.GeoPoint
import kr.mobility.core.time.KST
import kr.mobility.core.voice.KoreanScheduleParser
import kr.mobility.mirichaeum.demo.DemoPersona
import kr.mobility.mirichaeum.demo.DemoPlaces
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 캘린더 기능 (F-22~F-25, A-79~A-88). */
class CalendarTest {
    private val engine = MiriChaeum()
    private fun kst(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) = ZonedDateTime.of(y, mo, d, h, mi, 0, 0, KST)

    private fun calendarOf(input: MiriInput, from: LocalDate, to: LocalDate): CalendarView =
        engine.calendar(input, engine.evaluate(input), from, to)

    private val s1 = DemoPersona.s1FridayEvening().copy(voiceTrips = DemoPersona.calendarSeeds())

    @Test
    fun `예시 일정은 세 장면의 48시간 판정을 바꾸지 않는다`() {
        val scenes = listOf(DemoPersona.s1FridayEvening(), DemoPersona.s3MondayMorning(), DemoPersona.s4IceThursday())
        for (scene in scenes) {
            val plain = engine.evaluate(scene)
            val seeded = engine.evaluate(scene.copy(voiceTrips = DemoPersona.calendarSeeds()))
            assertEquals(plain.demand.totalKm, seeded.demand.totalKm, 1e-9, scene.now.toString())
            assertEquals(plain.judgement.verdict, seeded.judgement.verdict)
        }
    }

    @Test
    fun `S1 캘린더 - 내일 빠듯해지고 오늘 마트 충전 제안이 붙는다`() {
        val view = calendarOf(s1, LocalDate.of(2026, 10, 25), LocalDate.of(2026, 11, 14))
        assertEquals(LocalDate.of(2026, 10, 31), view.needDate)
        assertEquals(Verdict.TIGHT, view.needVerdict)
        assertEquals("채우지 않으면 내일 빠듯해져요", view.line)

        val friday = assertNotNull(view.day(LocalDate.of(2026, 10, 30)))
        assertTrue(friday.isToday)
        assertEquals(Verdict.ENOUGH, friday.outlook)
        assertEquals(19.0, friday.plannedKm, 1e-9)
        assertEquals("동네 마트에서 40분 충전", friday.entries.single { it.kind == EntryKind.FILL }.title)
        // 오늘 이미 다녀온 출근은 기록으로 보인다.
        assertEquals(19.0, friday.drivenKm, 1e-9)

        val saturday = assertNotNull(view.day(LocalDate.of(2026, 10, 31)))
        assertTrue(saturday.needDay)
        assertEquals(139.0, saturday.cumulativeKm!!, 1e-9)
        assertEquals(listOf("집 → 양평", "양평 → 집"), saturday.entries.filter { it.kind == EntryKind.ROUTINE }.map { it.title })

        // 채워야 하는 날 다음부터는 전망을 보여주지 않는다.
        val sunday = assertNotNull(view.day(LocalDate.of(2026, 11, 1)))
        assertNull(sunday.outlook)
        assertTrue(sunday.afterNeed(view.needDate))
    }

    @Test
    fun `지난 날은 실제로 달린 거리만 보인다`() {
        val view = calendarOf(s1, LocalDate.of(2026, 10, 29), LocalDate.of(2026, 10, 29))
        val thursday = view.days.single()
        assertTrue(thursday.isPast)
        assertEquals(38.0, thursday.drivenKm, 1e-9)
        assertEquals(0.0, thursday.plannedKm, 1e-9)
        assertTrue(thursday.entries.all { it.kind == EntryKind.DRIVEN })
    }

    @Test
    fun `매주 일정은 매주 같은 요일에 들어가고 시각이 지나도 지우지 않는다`() {
        val weekly = DemoPersona.calendarSeeds().first { it.weekly }
        val now = kst(2026, 10, 30, 18, 30)
        assertEquals(
            listOf(kst(2026, 11, 5, 14), kst(2026, 11, 12, 14)),
            weekly.occurrences(now, now.plusDays(14)),
        )
        assertEquals(kst(2026, 11, 5, 14), weekly.nextAt(now))
        assertEquals(listOf(weekly), VoiceTripBook.prune(listOf(weekly), now))

        val view = calendarOf(s1, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30))
        val visits = view.days.flatMap { d -> d.entries.filter { it.trip?.id == weekly.id } }
        assertEquals(listOf(5, 12, 19, 26), visits.map { it.at.dayOfMonth })
    }

    @Test
    fun `이날은 평소대로 안 다녀요 - 그날 평소 이동을 48시간 판정에서도 뺀다`() {
        val saturday = LocalDate.of(2026, 10, 31)
        val input = s1.copy(skipDays = setOf(saturday))
        val r = engine.evaluate(input)
        assertEquals(19.0, r.demand.totalKm, 1e-9)
        assertEquals(Verdict.ENOUGH, r.judgement.verdict)

        val day = assertNotNull(engine.calendar(input, r, saturday, saturday).day(saturday))
        assertTrue(day.skipRoutine)
        assertTrue(day.entries.filter { it.kind == EntryKind.ROUTINE }.all { it.skipped })
        assertEquals(0.0, day.plannedKm, 1e-9)
    }

    @Test
    fun `같은 날 같은 곳을 등록하면 평소 이동과 겹쳐 세지 않는다`() {
        val trip = VoiceTrip(
            "yp", kst(2026, 10, 31, 10), "양평", "양평", DemoPersona.leisurePoint,
            oneWayKm = 60.0, distanceSource = DistanceSource.NAVI,
        )
        val r = engine.evaluate(DemoPersona.s1FridayEvening().copy(voiceTrips = listOf(trip)))
        assertEquals(19.0 + 120.0, r.demand.totalKm, 1e-9)
        assertEquals(1, r.demand.items.count { it.source == DemandSource.VOICE })
        assertTrue(r.demand.items.none { it.source == DemandSource.ROUTINE && it.at.toLocalDate() == trip.at.toLocalDate() })
    }

    @Test
    fun `S3 2주 전망 - 금요일에 빠듯해진다`() {
        val input = DemoPersona.s3MondayMorning().copy(voiceTrips = DemoPersona.calendarSeeds())
        val view = calendarOf(input, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30))
        assertEquals(LocalDate.of(2026, 11, 6), view.needDate)
        assertEquals("채우지 않으면 11월 6일 금요일에 빠듯해져요", view.line)
        assertEquals(Verdict.ENOUGH, view.day(LocalDate.of(2026, 11, 5))!!.outlook)
    }

    @Test
    fun `전망 기간 동안 충분하면 그렇게 말한다`() {
        val input = DemoPersona.s3MondayMorning().copy(
            snapshot = DemoPersona.s3MondayMorning().snapshot.copy(rangeKm = 2000.0),
        )
        val view = calendarOf(input, LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 2))
        assertNull(view.needDate)
        assertEquals("앞으로 2주 동안 채우지 않아도 충분해요", view.line)
    }

    @Test
    fun `기록이 2주가 안 되면 날마다 하루 평균으로 센다`() {
        val now = kst(2026, 11, 2, 12)
        val input = MiriInput(
            now = now,
            state = kr.mobility.core.vehicle.DrivingState.PARKED,
            snapshot = EnergySnapshot(FuelType.EV, 300.0, 80.0, 5.2, 72.6, 15.0, now),
            userDailyKm = 40.0,
        )
        val view = calendarOf(input, LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 3))
        assertEquals(20.0, view.day(LocalDate.of(2026, 11, 2))!!.plannedKm, 1e-9)
        assertEquals(40.0, view.day(LocalDate.of(2026, 11, 3))!!.plannedKm, 1e-9)
    }

    @Test
    fun `말로 매주 일정을 등록한다`() {
        val now = kst(2026, 10, 30, 18, 30)
        val parsed = KoreanScheduleParser().parse("매주 목요일 오후 7시 수영장", now)
        assertTrue(parsed.weekly)
        assertEquals(LocalDate.of(2026, 11, 5), parsed.date)
        assertEquals(LocalTime.of(19, 0), parsed.time)
        assertEquals("수영장", parsed.placeQuery)

        val step = VoiceTripRegistrar(DemoPlaces, idFactory = { "w" }).interpret("매주 목요일 오후 7시 수영장", now, DemoPersona.homePoint)
        assertIs<VoiceStep.Confirm>(step)
        assertEquals("매주 목요일 오후 7시, 동네 수영장 맞나요?", step.prompt)
        assertTrue(step.draft.weekly)
        val km = Copywriter().approxKm(step.draft.roundTripKm!!)
        assertEquals("등록했어요. 매주 목요일 왕복 약 ${km}예요.", Copywriter().voiceTripRegistered(step.draft, null))
    }

    @Test
    fun `캘린더에서 터치로 넣은 일정 - 고쳐도 번호와 거리가 유지된다`() {
        val registrar = VoiceTripRegistrar(DemoPlaces, idFactory = { "t1" })
        val place = DemoPlaces.search("대전", null).first()
        val origin = GeoPoint(37.4020, 127.1086)
        val created = registrar.fromForm(kst(2026, 11, 3, 15), place, "대전", weekly = false, origin = origin)
        assertEquals(TripEntry.TOUCH, created.entry)
        assertEquals("t1", created.id)
        assertEquals(DistanceSource.STRAIGHT, created.distanceSource)
        assertNotNull(created.roundTripKm)

        val moved = registrar.fromForm(kst(2026, 11, 4, 9), place, "대전", weekly = true, origin = null, existing = created)
        assertEquals("t1", moved.id)
        assertEquals(created.oneWayKm, moved.oneWayKm)
        assertTrue(moved.weekly)
    }
}
