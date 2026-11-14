package kr.mobility.neighbor

import java.time.Duration
import java.time.ZonedDateTime
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 작품 3 '안심 이웃'(가칭)의 판단 로직. 1차는 기획서만 내고, 통과하면 이 로직으로 시연판을 만든다.
 * 실제 등급 데이터는 내비 회사·공공기관 협력 후 연결한다(기획 메모 3장). 여기서는 받은 등급을 어떻게 보여줄지만 다룬다.
 */

/** 동의한 운전자의 안전운전 등급. 점수 원값·이력은 받지 않는다. '빨강' 대신 '주의'라고 부른다. */
enum class Grade(val label: String) {
    GOOD("안전"),
    NORMAL("보통"),
    CAUTION("주의"),
}

enum class Side { LEFT, RIGHT }

/**
 * 주변 차량 하나. 위치는 내 차 기준 상대 좌표(m): [x] 오른쪽이 +, [y] 앞쪽이 +.
 * [tempId]는 운행마다 바뀌는 임시 표식이다. 번호판 같은 식별 정보는 받지 않는다.
 */
data class NearbyVehicle(
    val tempId: String,
    val x: Double,
    val y: Double,
    /** 나와 가까워지는 속도(km/h). +면 가까워짐. */
    val closingKmh: Double,
    /** 등급 공유에 동의하지 않은 차량은 null → 회색으로 표시하거나 숨긴다. */
    val grade: Grade?,
    /** 차량 간 통신(V2X)으로 받은 방향지시등. 모르면 null. */
    val turnSignal: Side? = null,
    /** 값이 몇 밀리초 전 것인지(A-64). 5초가 넘으면 쓰지 않는다. */
    val ageMillis: Long = 0,
) {
    val distance: Double get() = hypot(x, y)
}

/** 화면·음성에서 쓰는 방향 이름. */
enum class Position(val label: String) {
    FRONT("앞"), FRONT_RIGHT("오른쪽 앞"), RIGHT("오른쪽"), REAR_RIGHT("오른쪽 뒤"),
    REAR("뒤"), REAR_LEFT("왼쪽 뒤"), LEFT("왼쪽"), FRONT_LEFT("왼쪽 앞");

    companion object {
        private const val HALF_LANE_METERS = 1.8
        private const val SIDE_ZONE_METERS = 5.0

        /** 운전자가 말하는 방식대로 차로 기준으로 나눈다: 같은 차로면 앞·뒤, 옆 차로면 왼쪽·오른쪽 + 앞·뒤. */
        fun of(x: Double, y: Double): Position {
            val ahead = y > SIDE_ZONE_METERS
            val behind = y < -SIDE_ZONE_METERS
            return when {
                abs(x) < HALF_LANE_METERS -> if (y >= 0) FRONT else REAR
                x < 0 -> if (ahead) FRONT_LEFT else if (behind) REAR_LEFT else LEFT
                else -> if (ahead) FRONT_RIGHT else if (behind) REAR_RIGHT else RIGHT
            }
        }
    }
}

data class DisplayItem(val tempId: String, val position: Position, val grade: Grade?, val x: Double, val y: Double)

/** 받은 지 5초가 넘은 값은 믿지 않는다 (A-64). */
const val MAX_AGE_MILLIS = 5_000L

/** 위험한 등급이 앞에 오도록 하는 순서: 주의 → 보통 → 안전 → 미동의. */
private fun DisplayItem.dangerRank(): Int = when (grade) {
    Grade.CAUTION -> 0
    Grade.NORMAL -> 1
    Grade.GOOD -> 2
    null -> 3
}

/** SC-04 8칸 표시: 구역별로 묶고, 한 칸 안에서는 위험한 등급이 먼저다 (A-63). */
fun List<DisplayItem>.bySector(): Map<Position, List<DisplayItem>> =
    groupBy { it.position }.mapValues { (_, list) -> list.sortedBy { it.dangerRank() } }

/** 화면에 그릴 차량: 50m 안, 가까운 순으로 최대 6대(가안). 동의하지 않은 차량은 설정에 따라 숨긴다. */
class NeighborDisplay(
    private val rangeMeters: Double = 50.0,
    private val maxItems: Int = 6,
    private val showUnrated: Boolean = false,
) {
    fun items(vehicles: List<NearbyVehicle>): List<DisplayItem> = vehicles
        .filter { it.ageMillis <= MAX_AGE_MILLIS && it.distance <= rangeMeters && (showUnrated || it.grade != null) }
        .sortedBy { it.distance }
        .take(maxItems)
        .map { DisplayItem(it.tempId, Position.of(it.x, it.y), it.grade, it.x, it.y) }
}

enum class AlertReason {
    /** 주의 차량이 30m 안에서 가까워지는 중. */
    CLOSING,

    /** 옆 차로의 주의 차량이 50m 안에서 내 쪽으로 방향지시등을 켬. */
    LANE_CHANGE,

    /** 내가 방향지시등을 켠 쪽 50m 안에 주의 차량이 있음 (F-04). */
    MY_SIGNAL,
}

data class NeighborAlert(val tempId: String, val reason: AlertReason, val line: String)

/**
 * 음성 안내 규칙(가안): 주의 차량이 가까울 때만, 차량마다 1회, 전체 2분에 1회.
 * 내 방향지시등 쪽 주의 차량은 안전에 직결되므로 2분 제한의 예외다(A-66). 차량마다 1회는 유지한다.
 * 안내는 "거리를 두세요" 같은 행동만 말하고 상대를 특정하지 않는다(보복운전 방지).
 */
class NeighborAlertPolicy(
    private val closingMeters: Double = 30.0,
    private val laneChangeMeters: Double = 50.0,
    private val cooldown: Duration = Duration.ofMinutes(2),
) {
    data class State(val lastAt: ZonedDateTime? = null, val alerted: Set<String> = emptySet())

    private fun isAdjacentLane(v: NearbyVehicle) = abs(v.x) in 2.0..5.5

    private fun signalsTowardMe(v: NearbyVehicle) =
        (v.x < 0 && v.turnSignal == Side.RIGHT) || (v.x > 0 && v.turnSignal == Side.LEFT)

    /** 내 방향지시등 쪽 차량인지: 같은 차로(앞·뒤)는 빼고, 켠 쪽 옆 차로만 본다. */
    private fun onMySignalSide(v: NearbyVehicle, mine: Side) =
        abs(v.x) >= LANE_HALF_METERS && if (mine == Side.RIGHT) v.x > 0 else v.x < 0

    fun decide(
        vehicles: List<NearbyVehicle>,
        now: ZonedDateTime,
        state: State,
        /** 내 차 방향지시등(Vehicle SDK TurnSignal). */
        mySignal: Side? = null,
    ): Pair<NeighborAlert?, State> {
        val candidates = vehicles.filter { it.grade == Grade.CAUTION && it.tempId !in state.alerted && it.ageMillis <= MAX_AGE_MILLIS }
        val mine = mySignal?.let { side ->
            candidates.filter { it.distance <= laneChangeMeters && onMySignalSide(it, side) }.minByOrNull { it.distance }
        }
        if (mine != null) return alert(mine, AlertReason.MY_SIGNAL, now, state)
        val last = state.lastAt
        if (last != null && Duration.between(last, now) < cooldown) return null to state
        val laneChange = candidates
            .filter { it.distance <= laneChangeMeters && isAdjacentLane(it) && signalsTowardMe(it) }
            .minByOrNull { it.distance }
        if (laneChange != null) return alert(laneChange, AlertReason.LANE_CHANGE, now, state)
        val closing = candidates
            .filter { it.distance <= closingMeters && it.closingKmh > 0 }
            .minByOrNull { it.distance }
        return if (closing != null) alert(closing, AlertReason.CLOSING, now, state) else null to state
    }

    private fun alert(target: NearbyVehicle, reason: AlertReason, now: ZonedDateTime, state: State): Pair<NeighborAlert, State> {
        val where = Position.of(target.x, target.y).label
        val line = when (reason) {
            AlertReason.MY_SIGNAL -> if (target.closingKmh > 0) {
                "$where 차가 빠르게 다가와요. 차선 변경은 천천히 하세요."
            } else {
                "$where 차와 거리를 두고 바꾸세요."
            }
            AlertReason.LANE_CHANGE -> "$where 차량이 들어올 수 있어요. 속도를 유지하고 거리를 두세요."
            AlertReason.CLOSING -> "$where 차량과 거리를 두세요."
        }
        return NeighborAlert(target.tempId, reason, line) to State(now, state.alerted + target.tempId)
    }

    private companion object {
        const val LANE_HALF_METERS = 1.8
    }
}

/**
 * 내 등급 보기(참여 동기). 협력사가 주는 안전운전 점수(0~100)를 3등급으로 바꾼다.
 * 기준은 가안: 80 이상 안전, 60~79 보통, 60 미만 주의.
 */
object SelfGrade {
    fun of(score: Int): Grade {
        require(score in 0..100) { "score out of range: $score" }
        return when {
            score >= 80 -> Grade.GOOD
            score >= 60 -> Grade.NORMAL
            else -> Grade.CAUTION
        }
    }

    fun line(score: Int): String = when (of(score)) {
        Grade.GOOD -> "안전 운전 배지를 받았어요. 주변 차량에 초록으로 보여요."
        Grade.NORMAL -> "보통 등급이에요. 급가속·급제동을 줄이면 초록이 돼요."
        Grade.CAUTION -> "주의 등급이에요. 등급이 맞지 않으면 이의 신청을 할 수 있어요."
    }
}

/** 시연판용 가상 주변 차량(F-12). 실제 데이터는 협력 후 연결한다. */
object DemoTraffic {
    /** 왼쪽 뒤의 주의 차량이 다가오면서 오른쪽 방향지시등을 켠다(F-05, 차량 간 통신 전제). */
    fun frames(): List<List<NearbyVehicle>> = (0 until 8).map { step ->
        listOf(
            NearbyVehicle("t1", x = 0.0, y = 25.0, closingKmh = -3.0, grade = Grade.GOOD),
            NearbyVehicle("t2", x = 3.5, y = 10.0, closingKmh = 0.0, grade = Grade.NORMAL),
            NearbyVehicle("t3", x = 3.5, y = -18.0, closingKmh = 0.0, grade = null),
            NearbyVehicle(
                "t4",
                x = -3.5,
                y = -45.0 + step * 6.0,
                closingKmh = 22.0,
                grade = Grade.CAUTION,
                turnSignal = if (step >= 2) Side.RIGHT else null,
            ),
        )
    }

    /** 주변 보기와 접근 안내(F-02, F-03): 왼쪽 뒤의 주의 차량이 30m 안으로 들어온다. */
    fun closing(): List<List<NearbyVehicle>> = (0 until 8).map { step ->
        listOf(
            NearbyVehicle("a1", x = 0.0, y = 25.0, closingKmh = -3.0, grade = Grade.GOOD),
            NearbyVehicle("a2", x = 3.5, y = 12.0, closingKmh = 0.0, grade = Grade.NORMAL),
            NearbyVehicle("a3", x = 3.5, y = -18.0, closingKmh = 0.0, grade = null),
            NearbyVehicle("a4", x = -3.5, y = -48.0 + step * 6.0, closingKmh = 22.0, grade = Grade.CAUTION),
        )
    }

    /** S1 차선 변경 직전(F-04): 오른쪽 뒤에 주의 차량이 있고, 4번째 장면부터 내가 오른쪽 방향지시등을 켠다. */
    const val S1_MY_SIGNAL_FROM_STEP = 3

    fun laneChange(): List<List<NearbyVehicle>> = (0 until 8).map { step ->
        listOf(
            NearbyVehicle("b1", x = 0.0, y = 28.0, closingKmh = -2.0, grade = Grade.GOOD),
            NearbyVehicle("b2", x = 3.5, y = -44.0 + step * 4.0, closingKmh = 30.0, grade = Grade.CAUTION),
        )
    }
}

/** 시연판의 내 등급 화면(S2) 값. 실제로는 협력사가 준다. */
data class MyGradeDemo(val score: Int, val changeLine: String, val benefitLine: String?, val evidence: List<Pair<String, String>>, val measuredKm: Int)

object DemoMyGrade {
    val value = MyGradeDemo(
        score = 86,
        changeLine = "이번 달도 초록 등급이에요. 급제동이 지난달보다 30% 줄었어요.",
        benefitLine = "보험사 안전운전 혜택 조건을 충족했어요.",
        evidence = listOf("급가속" to "안전", "급제동" to "안전", "과속" to "보통"),
        measuredKm = 420,
    )
}

/** 이의 신청 상태 (F-08). 확인 중에는 내 등급을 다른 차에 보이지 않는다. */
enum class AppealStatus(val label: String, val hidesGrade: Boolean) {
    NONE("", false),
    RECEIVED("접수", true),
    CHECKING("확인 중", true),
    DONE("완료", false),
}

/** 동의 2단 구조 (A-62): 위치 제공은 보기에 필수, 내 등급 공유는 선택. */
data class Consent(val location: Boolean, val share: Boolean) {
    val canViewNearby: Boolean get() = location
    val visibleToOthers: Boolean get() = location && share
}
