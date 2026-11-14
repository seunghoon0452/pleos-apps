package kr.mobility.mirichaeum

import kr.mobility.core.geo.Geo
import kr.mobility.core.geo.GeoPoint
import kr.mobility.core.geo.PlaceCell
import kr.mobility.core.text.Korean
import kr.mobility.core.time.KoreanTimeWords
import kr.mobility.core.voice.KoreanScheduleParser
import kr.mobility.core.voice.ParseIssue
import kr.mobility.core.voice.ParsedSchedule
import java.time.ZonedDateTime
import java.util.UUID

data class PlaceCandidate(val name: String, val location: GeoPoint, val address: String? = null)

/** 장소 이름 → 위치. 앱에서는 카카오 로컬 검색 등으로 구현하고, 시연·테스트는 고정 목록을 쓴다. */
fun interface PlaceSearch {
    fun search(query: String, near: GeoPoint?): List<PlaceCandidate>
}

/** 도로 거리(km). R-06 ①NaviHelper ②카카오 길찾기. 둘 다 없으면 null을 돌려준다. */
fun interface RoadDistance {
    fun km(from: GeoPoint, to: GeoPoint): Pair<Double, DistanceSource>?
}

sealed interface VoiceStep {
    /** 다 알아들었다. 읽어주고 "응"을 기다린다. */
    data class Confirm(val draft: VoiceTrip, val prompt: String, val needsHourCheck: Boolean) : VoiceStep

    /** 날짜·시간·장소 중 빠진 것을 묻는다. */
    data class AskMissing(val missing: ParseIssue, val prompt: String, val parsed: ParsedSchedule) : VoiceStep

    /** 장소 후보가 여러 곳이라 정차 후 화면에서 고른다 (S2-4). 일정은 장소 없이 먼저 저장한다. */
    data class PickPlaceLater(val draft: VoiceTrip, val prompt: String, val candidates: List<PlaceCandidate>) : VoiceStep
}

enum class Answer { YES, NO, UNKNOWN }

/** "응/아니" 대답 구분. 아니가 섞이면 아니로 본다. */
object YesNo {
    private val no = listOf("아니", "아뇨", "틀려", "틀렸", "취소", "싫어", "됐어")
    private val yes = setOf("응", "어", "네", "예", "웅", "넵", "넹", "그래", "그래요", "좋아", "좋아요", "오케이", "맞아", "맞아요", "맞습니다", "그렇지", "그럼")

    fun classify(text: String): Answer {
        val tokens = text.replace(Regex("[.,!?~]"), " ").split(' ').filter { it.isNotBlank() }
        if (tokens.any { t -> no.any { t.startsWith(it) } }) return Answer.NO
        if (tokens.any { it in yes || it.startsWith("맞") }) return Answer.YES
        return Answer.UNKNOWN
    }
}

/**
 * F-04 음성 이동 일정 등록: 말 → 해석 → 장소 찾기 → 거리 → 확인 문장.
 * 주행 중에도 쓰므로 질문은 한 번에 하나, 짧게.
 */
class VoiceTripRegistrar(
    private val search: PlaceSearch,
    private val road: RoadDistance = RoadDistance { _, _ -> null },
    private val parser: KoreanScheduleParser = KoreanScheduleParser(),
    /** 후보들이 이 거리 안에 모여 있으면 어느 곳이든 에너지 계획에는 차이가 없으므로 첫 후보를 쓴다. */
    private val sameAreaMeters: Double = 10_000.0,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) {
    fun interpret(utterance: String, now: ZonedDateTime, origin: GeoPoint?): VoiceStep {
        val parsed = parser.parse(utterance, now)
        val date = parsed.date ?: return VoiceStep.AskMissing(ParseIssue.NO_DATE, "언제 가세요?", parsed)
        val time = parsed.time ?: return VoiceStep.AskMissing(ParseIssue.NO_TIME, "몇 시 일정인가요?", parsed)
        val query = parsed.placeQuery ?: return VoiceStep.AskMissing(ParseIssue.NO_PLACE, "어디로 가세요?", parsed)
        val at = date.atTime(time).atZone(now.zone)
        val candidates = search.search(query, origin)
        val chosen = choose(candidates)
        val base = VoiceTrip(
            id = idFactory(),
            at = at,
            placeQuery = query,
            placeName = null,
            destination = null,
            purpose = parsed.purpose,
            weekly = parsed.weekly,
        )
        val whenText = whenText(base)
        if (chosen == null) {
            return VoiceStep.PickPlaceLater(
                draft = base,
                prompt = "$whenText ${Korean.iya(query + " 일정")}. 장소는 차를 세운 뒤에 골라주세요.",
                candidates = candidates,
            )
        }
        val draft = withPlace(base, chosen, origin)
        val prompt = "$whenText, ${chosen.name} 맞나요?"
        return VoiceStep.Confirm(draft, prompt, needsHourCheck = ParseIssue.HOUR_ASSUMED in parsed.issues)
    }

    /** 빠진 항목을 한 번 되물은 뒤의 대답을 처음 말과 합쳐 다시 해석한다 (A-33). */
    fun interpretFollowUp(first: String, answer: String, now: ZonedDateTime, origin: GeoPoint?): VoiceStep =
        interpret("$first $answer", now, origin)

    /**
     * F-23 캘린더에서 터치로 넣은 일정. 장소는 이미 골랐으므로 바로 거리를 계산한다.
     * 고치는 경우 [existing]의 번호·목적·입력 방식을 그대로 둔다.
     */
    fun fromForm(
        at: ZonedDateTime,
        place: PlaceCandidate,
        query: String,
        weekly: Boolean,
        origin: GeoPoint?,
        existing: VoiceTrip? = null,
    ): VoiceTrip {
        val base = VoiceTrip(
            id = existing?.id ?: idFactory(),
            at = at,
            placeQuery = query.ifBlank { place.name },
            placeName = null,
            destination = null,
            purpose = existing?.purpose,
            weekly = weekly,
            entry = existing?.entry ?: TripEntry.TOUCH,
        )
        // 장소를 바꾸지 않았으면 처음 계산한 거리를 그대로 쓴다(지금 위치가 달라도 같은 일정).
        val keep = existing?.takeIf { it.destination == place.location && it.oneWayKm != null }
        return if (keep != null) {
            base.copy(placeName = place.name, destination = place.location, oneWayKm = keep.oneWayKm, distanceSource = keep.distanceSource)
        } else {
            withPlace(base, place, origin)
        }
    }

    /** 정차 후 목록에서 장소를 고르면 거리를 다시 계산한다. */
    fun withPlace(trip: VoiceTrip, place: PlaceCandidate, origin: GeoPoint?): VoiceTrip {
        val distance = origin?.let { o ->
            road.km(o, place.location) ?: (Geo.estimatedRoadKm(o, place.location) to DistanceSource.STRAIGHT)
        }
        return trip.copy(
            placeName = place.name,
            destination = place.location,
            oneWayKm = distance?.first,
            distanceSource = distance?.second,
        )
    }

    /** 읽어줄 때 쓰는 날짜·시각. 매주 일정은 "매주 화요일 오후 7시". */
    private fun whenText(trip: VoiceTrip): String {
        val time = Korean.spokenTime(trip.at.toLocalTime())
        return if (trip.weekly) "매주 ${Korean.dayName(trip.at.dayOfWeek)} $time" else "${Korean.spokenDate(trip.at.toLocalDate())} $time"
    }

    private fun choose(candidates: List<PlaceCandidate>): PlaceCandidate? {
        val first = candidates.firstOrNull() ?: return null
        val spread = candidates.drop(1).maxOfOrNull { Geo.distanceMeters(first.location, it.location) } ?: 0.0
        return if (spread <= sameAreaMeters) first else null
    }
}

/** R-05: 일정 시각이 지나면 자동 삭제(매주 일정은 지우지 않음, A-83). 목록은 바꾸지 않고 새 목록을 돌려준다. */
object VoiceTripBook {
    fun prune(trips: List<VoiceTrip>, now: ZonedDateTime): List<VoiceTrip> = trips.filter { it.weekly || it.at.isAfter(now) }

    /** 화면 목록 순서: 다음 일정 시각순. */
    fun upcoming(trips: List<VoiceTrip>, now: ZonedDateTime): List<VoiceTrip> =
        trips.sortedBy { it.nextAt(now) ?: it.at }

    fun upsert(trips: List<VoiceTrip>, trip: VoiceTrip): List<VoiceTrip> =
        (trips.filterNot { it.id == trip.id } + trip).sortedBy { it.at }

    fun remove(trips: List<VoiceTrip>, id: String): List<VoiceTrip> = trips.filterNot { it.id == id }
}

/** "월요일 퇴근할 때 다시 알려드릴게요"의 시점. */
data class Reminder(val at: ZonedDateTime, val phrase: String)

/**
 * 이동 일정 전에 다시 알려줄 운행을 고른다: 일정 12~48시간 전의 평소 출발 중 가장 늦은 것.
 * 밤새 충전할 시간을 남기기 위해 12시간 전을 기준으로 한다(가안). 없으면 1시간 전까지 넓힌다.
 */
object ReminderPlanner {
    fun plan(trip: VoiceTrip, patterns: List<RoutinePattern>, roles: Map<PlaceCell, PlaceRole>): Reminder? {
        // 매주 일정은 늘 판정에 들어가므로 따로 다시 알려줄 운행을 고르지 않는다.
        if (trip.weekly) return null
        val windowStart = trip.at.minusHours(48)
        fun latestBefore(limit: ZonedDateTime): Pair<ZonedDateTime, RoutinePattern>? = patterns
            .map { it.slot.nextStart(windowStart) to it }
            .filter { (at, _) -> !at.isBefore(windowStart) && !at.isAfter(limit) }
            .maxByOrNull { it.first }
        val (at, p) = latestBefore(trip.at.minusHours(12)) ?: latestBefore(trip.at.minusHours(1)) ?: return null
        val day = Korean.dayName(at.dayOfWeek)
        val phrase = when {
            roles[p.from] == PlaceRole.WORK && roles[p.to] == PlaceRole.HOME -> "$day 퇴근할 때"
            roles[p.from] == PlaceRole.HOME && roles[p.to] == PlaceRole.WORK -> "$day 출근할 때"
            else -> "$day ${KoreanTimeWords.partOfDay(at.hour)}에 출발할 때"
        }
        return Reminder(at, phrase)
    }
}
