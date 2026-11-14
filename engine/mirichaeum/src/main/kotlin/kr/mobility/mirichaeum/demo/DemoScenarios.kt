package kr.mobility.mirichaeum.demo

import kr.mobility.core.geo.Geo
import kr.mobility.core.geo.GeoPoint
import kr.mobility.core.geo.PlaceCell
import kr.mobility.core.time.KST
import kr.mobility.core.vehicle.DrivingState
import kr.mobility.mirichaeum.Charger
import kr.mobility.mirichaeum.DistanceSource
import kr.mobility.mirichaeum.DwellRecord
import kr.mobility.mirichaeum.EnergySnapshot
import kr.mobility.mirichaeum.FuelStation
import kr.mobility.mirichaeum.FuelType
import kr.mobility.mirichaeum.MiriInput
import kr.mobility.mirichaeum.PlaceCandidate
import kr.mobility.mirichaeum.PlaceSearch
import kr.mobility.mirichaeum.PrefillRecord
import kr.mobility.mirichaeum.RouteCharger
import kr.mobility.mirichaeum.SolutionKind
import kr.mobility.mirichaeum.TripEntry
import kr.mobility.mirichaeum.TripRecord
import kr.mobility.mirichaeum.VoiceTrip
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

/**
 * 시연 모드(F-15)와 테스트에 쓰는 가상의 운전자 한 명.
 * "3주 쓴 뒤"를 바로 보여주기 위해 최근 3주 + 이번 주의 운행 기록을 만든다.
 * 장소·이름은 모두 가상이며 실제 개인 정보가 아니다.
 *
 * 평소 생활: 평일 집↔회사 19km, 수요일 저녁 헬스장 왕복 12km,
 * 금요일 퇴근길 마트(40분) 들렀다 귀가, 토요일 오전 양평 왕복 120km.
 */
object DemoPersona {
    val homePoint = GeoPoint(37.2517, 127.0710)
    val workPoint = GeoPoint(37.4020, 127.1086)
    val martPoint = GeoPoint(37.3000, 127.0800)
    val gymPoint = GeoPoint(37.2600, 127.0600)
    val leisurePoint = GeoPoint(37.4917, 127.4875)

    val home = PlaceCell.of(homePoint)
    val work = PlaceCell.of(workPoint)
    val mart = PlaceCell.of(martPoint)
    val gym = PlaceCell.of(gymPoint)
    val leisure = PlaceCell.of(leisurePoint)

    val placeNames = mapOf(home to "집", work to "회사", mart to "동네 마트", gym to "헬스장", leisure to "양평")

    /** 마트 주차장 완속 충전기(가상). */
    val martCharger = Charger("demo-slow-mart", "동네 마트 주차장 완속", GeoPoint(37.3003, 127.0804), 7.0, available = 2, total = 4, address = "동네 마트 지하 1층 주차장", statusAgeMinutes = 3)

    val routeChargers = listOf(
        RouteCharger(Charger("demo-fast-1", "퇴근길 급속충전소", GeoPoint(37.3800, 127.1000), 100.0, available = 1, total = 2, address = "퇴근길 도로변", statusAgeMinutes = 3), 2.0),
        RouteCharger(Charger("demo-ultra-1", "고속도로 휴게소 초급속", GeoPoint(37.3300, 127.1500), 200.0, available = 3, total = 6, address = "고속도로 휴게소", statusAgeMinutes = 15), 9.0),
    )

    val stations = listOf(
        FuelStation("demo-oil-1", "새길 셀프주유소", GeoPoint(37.3500, 127.1000), 1629.0, 1.0, address = "퇴근길 도로변"),
        FuelStation("demo-oil-2", "중앙 주유소", GeoPoint(37.3200, 127.0900), 1689.0, 2.0, address = "중앙로"),
        FuelStation("demo-oil-3", "외곽 주유소", GeoPoint(37.3000, 127.1300), 1690.0, 6.0, address = "외곽 순환도로"),
    )

    private data class Leg(val depart: LocalTime, val minutes: Long, val from: PlaceCell, val to: PlaceCell, val km: Double)

    private fun legsFor(day: DayOfWeek): List<Leg> = when (day) {
        DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.THURSDAY -> listOf(
            Leg(LocalTime.of(8, 0), 40, home, work, 19.0),
            Leg(LocalTime.of(18, 30), 40, work, home, 19.0),
        )
        DayOfWeek.WEDNESDAY -> listOf(
            Leg(LocalTime.of(8, 0), 40, home, work, 19.0),
            Leg(LocalTime.of(18, 30), 40, work, home, 19.0),
            Leg(LocalTime.of(19, 40), 15, home, gym, 6.0),
            Leg(LocalTime.of(20, 55), 15, gym, home, 6.0),
        )
        DayOfWeek.FRIDAY -> listOf(
            Leg(LocalTime.of(8, 0), 40, home, work, 19.0),
            Leg(LocalTime.of(18, 30), 25, work, mart, 12.0),
            Leg(LocalTime.of(19, 35), 20, mart, home, 7.0),
        )
        DayOfWeek.SATURDAY -> listOf(
            Leg(LocalTime.of(10, 0), 70, home, leisure, 60.0),
            Leg(LocalTime.of(16, 30), 70, leisure, home, 60.0),
        )
        DayOfWeek.SUNDAY -> emptyList()
    }

    /** [now] 직전까지의 기록. 이번 주 월요일 기준 3주 전부터 만든다. */
    fun history(now: ZonedDateTime): Pair<List<TripRecord>, List<DwellRecord>> {
        val firstDay = now.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(3)
        val trips = generateSequence(firstDay) { it.plusDays(1) }
            .takeWhile { !it.isAfter(now.toLocalDate()) }
            .flatMap { date -> legsFor(date.dayOfWeek).map { leg -> toTrip(date, leg) } }
            .filter { it.start.isBefore(now) }
            .toList()
        val dwells = trips.zipWithNext()
            .filter { (a, b) -> a.to == b.from }
            .map { (a, b) -> DwellRecord(a.to, a.end, b.start) }
        return trips to dwells
    }

    private fun toTrip(date: LocalDate, leg: Leg): TripRecord {
        val start = date.atTime(leg.depart).atZone(KST)
        return TripRecord(start, start.plusMinutes(leg.minutes), leg.from, leg.to, leg.km, outsideTempC = 3.0)
    }

    /** S1: 금요일 18:30 회사에서 퇴근하려고 탑승. 전기차, 3℃. → 노랑, 마트 체류 충전. */
    fun s1FridayEvening(now: ZonedDateTime = ZonedDateTime.of(2026, 10, 30, 18, 30, 0, 0, KST)): MiriInput {
        val (trips, dwells) = history(now)
        return MiriInput(
            now = now,
            state = DrivingState.PARKED,
            snapshot = EnergySnapshot(FuelType.EV, rangeKm = 218.0, levelPercent = 58.0, efficiency = 5.2, capacity = 72.6, outsideTempC = 3.0, at = now),
            trips = trips,
            dwells = dwells,
            currentPlace = work,
            placeNames = placeNames,
            nearbyChargers = listOf(martCharger),
            routeChargers = routeChargers,
        )
    }

    /** S3: 월요일 아침 출근. 충분 → 초록 카드만, 말하지 않음. */
    fun s3MondayMorning(now: ZonedDateTime = ZonedDateTime.of(2026, 11, 2, 7, 50, 0, 0, KST)): MiriInput {
        val (trips, dwells) = history(now)
        return MiriInput(
            now = now,
            state = DrivingState.PARKED,
            snapshot = EnergySnapshot(FuelType.EV, rangeKm = 300.0, levelPercent = 80.0, efficiency = 5.2, capacity = 72.6, outsideTempC = 12.0, at = now),
            trips = trips,
            dwells = dwells,
            currentPlace = home,
            placeNames = placeNames,
            nearbyChargers = listOf(martCharger),
            routeChargers = routeChargers,
        )
    }

    /** S4: 내연기관, 목요일 퇴근길. 주말 장거리 대비 오늘 주유 권유. */
    fun s4IceThursday(now: ZonedDateTime = ZonedDateTime.of(2026, 10, 29, 18, 30, 0, 0, KST)): MiriInput {
        val (trips, dwells) = history(now)
        return MiriInput(
            now = now,
            state = DrivingState.PARKED,
            snapshot = EnergySnapshot(FuelType.ICE, rangeKm = 230.0, levelPercent = 40.0, efficiency = 12.5, capacity = 50.0, outsideTempC = 3.0, at = now),
            trips = trips,
            dwells = dwells,
            currentPlace = work,
            placeNames = placeNames,
            stations = stations,
        )
    }

    val gwanggyoPoint = GeoPoint(37.2850, 127.0450)
    val gangneungPoint = GeoPoint(37.7645, 128.8996)

    /**
     * 캘린더 시연용 예시 일정 (A-86). 장면을 고를 때마다 이 상태로 돌아간다.
     * S1·S3·S4의 48시간 판정 수치가 바뀌지 않도록 세 장면의 48시간 밖에 있는 날짜로 골랐다.
     */
    fun calendarSeeds(): List<VoiceTrip> = listOf(
        VoiceTrip(
            id = "demo-weekly-gwanggyo",
            at = ZonedDateTime.of(2026, 10, 22, 14, 0, 0, 0, KST),
            placeQuery = "광교",
            placeName = "광교 비즈니스센터",
            destination = gwanggyoPoint,
            purpose = "방문",
            oneWayKm = Geo.estimatedRoadKm(workPoint, gwanggyoPoint),
            distanceSource = DistanceSource.STRAIGHT,
            weekly = true,
            entry = TripEntry.TOUCH,
        ),
        VoiceTrip(
            id = "demo-gangneung",
            at = ZonedDateTime.of(2026, 11, 8, 9, 0, 0, 0, KST),
            placeQuery = "강릉",
            placeName = "강릉역",
            destination = gangneungPoint,
            purpose = "여행",
            oneWayKm = Geo.estimatedRoadKm(homePoint, gangneungPoint),
            distanceSource = DistanceSource.STRAIGHT,
        ),
    )

    /** 월간 리포트 시연용 기록. */
    fun prefillRecords(month: java.time.YearMonth): List<PrefillRecord> {
        fun at(day: Int, hour: Int) = month.atDay(day).atTime(hour, 0).atZone(KST)
        return listOf(
            PrefillRecord(at(3, 18), SolutionKind.DWELL, amount = 16.0, unitPrice = 295.0, extraMinutes = 0.0),
            PrefillRecord(at(10, 18), SolutionKind.DWELL, amount = 20.0, unitPrice = 295.0, extraMinutes = 0.0),
            PrefillRecord(at(21, 8), SolutionKind.ON_ROUTE, amount = 16.0, unitPrice = 348.4, extraMinutes = 13.3),
        )
    }
}

/** 시연용 장소 검색. 실제 앱에서는 장소 검색 API로 바꾼다. */
object DemoPlaces : PlaceSearch {
    private val directory = mapOf(
        "대전" to listOf(PlaceCandidate("대전 정부청사", GeoPoint(36.3587, 127.3848))),
        "부산" to listOf(PlaceCandidate("부산역", GeoPoint(35.1151, 129.0418))),
        "강남역" to listOf(PlaceCandidate("강남역", GeoPoint(37.4979, 127.0276))),
        "인천공항" to listOf(PlaceCandidate("인천국제공항 제1터미널", GeoPoint(37.4492, 126.4510))),
        "양평" to listOf(PlaceCandidate("양평", DemoPersona.leisurePoint)),
        "강릉" to listOf(PlaceCandidate("강릉역", DemoPersona.gangneungPoint)),
        "광교" to listOf(PlaceCandidate("광교 비즈니스센터", DemoPersona.gwanggyoPoint)),
        "수영장" to listOf(PlaceCandidate("동네 수영장", GeoPoint(37.2550, 127.0750))),
        "카페" to listOf(
            PlaceCandidate("판교 카페", GeoPoint(37.3950, 127.1110)),
            PlaceCandidate("수원 카페", GeoPoint(37.2700, 127.0000)),
            PlaceCandidate("강남 카페", GeoPoint(37.5000, 127.0300)),
        ),
    )

    override fun search(query: String, near: GeoPoint?): List<PlaceCandidate> =
        directory.entries.firstOrNull { (key, _) -> query.contains(key) || key.contains(query) }?.value ?: emptyList()
}
