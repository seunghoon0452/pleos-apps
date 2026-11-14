package kr.mobility.neighbor

import kr.mobility.core.time.KST
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NeighborTest {
    private val now = ZonedDateTime.of(2026, 11, 20, 14, 0, 0, 0, KST)
    private fun v(id: String, x: Double, y: Double, closing: Double = 10.0, grade: Grade? = Grade.CAUTION, signal: Side? = null) =
        NearbyVehicle(id, x, y, closing, grade, signal)

    @Test
    fun `8방향 이름`() {
        assertEquals(Position.FRONT, Position.of(0.0, 10.0))
        assertEquals(Position.RIGHT, Position.of(10.0, 0.0))
        assertEquals(Position.REAR_LEFT, Position.of(-3.5, -10.0))
        assertEquals(Position.LEFT, Position.of(-3.5, 2.0))
        assertEquals(Position.FRONT_RIGHT, Position.of(3.5, 12.0))
        assertEquals(Position.REAR, Position.of(0.0, -10.0))
        assertEquals("왼쪽 뒤", Position.of(-8.0, -8.0).label)
    }

    @Test
    fun `화면에는 50m 안, 동의한 차량만, 가까운 순`() {
        val items = NeighborDisplay().items(listOf(v("far", 0.0, 60.0), v("none", 3.0, 5.0, grade = null), v("b", 0.0, 20.0), v("a", 3.5, 5.0)))
        assertEquals(listOf("a", "b"), items.map { it.tempId })
        assertEquals(3, NeighborDisplay(showUnrated = true).items(listOf(v("none", 3.0, 5.0, grade = null), v("b", 0.0, 20.0), v("a", 3.5, 5.0))).size)
    }

    @Test
    fun `주의 차량이 30m 안에서 다가오면 1회 안내, 같은 차는 다시 말하지 않음`() {
        val policy = NeighborAlertPolicy()
        val (alert, state) = policy.decide(listOf(v("c1", 0.0, -20.0)), now, NeighborAlertPolicy.State())
        assertEquals("뒤 차량과 거리를 두세요.", alert?.line)
        val (again, _) = policy.decide(listOf(v("c1", 0.0, -15.0)), now.plusMinutes(3), state)
        assertNull(again)
    }

    @Test
    fun `보통·안전 등급, 멀어지는 차, 2분 안의 두 번째 안내는 말하지 않음`() {
        val policy = NeighborAlertPolicy()
        val empty = NeighborAlertPolicy.State()
        assertNull(policy.decide(listOf(v("n", 0.0, -10.0, grade = Grade.NORMAL)), now, empty).first)
        assertNull(policy.decide(listOf(v("away", 0.0, -10.0, closing = -5.0)), now, empty).first)
        val (_, state) = policy.decide(listOf(v("c1", 0.0, -20.0)), now, empty)
        assertNull(policy.decide(listOf(v("c2", 0.0, 15.0)), now.plusSeconds(90), state).first)
    }

    @Test
    fun `옆 차로 주의 차량이 내 쪽으로 깜빡이를 켜면 끼어들기 안내가 먼저`() {
        val policy = NeighborAlertPolicy()
        val (alert, _) = policy.decide(
            listOf(v("near", 0.0, -12.0), v("lc", -3.5, -40.0, signal = Side.RIGHT)),
            now,
            NeighborAlertPolicy.State(),
        )
        assertEquals(AlertReason.LANE_CHANGE, alert?.reason)
        assertEquals("왼쪽 뒤 차량이 들어올 수 있어요. 속도를 유지하고 거리를 두세요.", alert?.line)
    }

    @Test
    fun `시연 주행 - 왼쪽 뒤 주의 차량에 한 번만 안내`() {
        val policy = NeighborAlertPolicy()
        var state = NeighborAlertPolicy.State()
        val lines = DemoTraffic.frames().mapIndexedNotNull { i, frame ->
            val (alert, next) = policy.decide(frame, now.plusSeconds(i * 2L), state)
            state = next
            alert?.line
        }
        assertEquals(listOf("왼쪽 뒤 차량이 들어올 수 있어요. 속도를 유지하고 거리를 두세요."), lines)
    }

    @Test
    fun `내 방향지시등 쪽 주의 차량은 2분 제한의 예외`() {
        val policy = NeighborAlertPolicy()
        val (_, state) = policy.decide(listOf(v("c1", 0.0, -20.0)), now, NeighborAlertPolicy.State())
        val (alert, _) = policy.decide(listOf(v("c2", 3.5, -40.0)), now.plusSeconds(10), state, Side.RIGHT)
        assertEquals(AlertReason.MY_SIGNAL, alert?.reason)
        assertEquals("오른쪽 뒤 차가 빠르게 다가와요. 차선 변경은 천천히 하세요.", alert?.line)
    }

    @Test
    fun `내 방향지시등 안내는 차량마다 1회, 반대쪽과 같은 차로는 제외`() {
        val policy = NeighborAlertPolicy()
        val state = NeighborAlertPolicy.State()
        val (first, next) = policy.decide(listOf(v("c1", 3.5, -40.0, closing = -1.0)), now, state, Side.RIGHT)
        assertEquals("오른쪽 뒤 차와 거리를 두고 바꾸세요.", first?.line)
        assertNull(policy.decide(listOf(v("c1", 3.5, -30.0)), now.plusMinutes(5), next, Side.RIGHT).first)
        assertNull(policy.decide(listOf(v("left", -3.5, -40.0, closing = -1.0)), now, state, Side.RIGHT).first)
        assertNull(policy.decide(listOf(v("same", 0.0, -40.0, closing = -1.0)), now, state, Side.RIGHT).first)
    }

    @Test
    fun `5초 지난 값은 표시하지도 말하지도 않음`() {
        val stale = NearbyVehicle("s", 0.0, -10.0, 10.0, Grade.CAUTION, ageMillis = 6_000)
        assertEquals(0, NeighborDisplay().items(listOf(stale)).size)
        assertNull(NeighborAlertPolicy().decide(listOf(stale), now, NeighborAlertPolicy.State()).first)
    }

    @Test
    fun `한 칸 안에서는 위험한 등급이 앞`() {
        val items = NeighborDisplay(showUnrated = true).items(
            listOf(v("g", 3.5, 8.0, grade = Grade.GOOD), v("n", 3.5, 9.0, grade = null), v("c", 3.5, 12.0)),
        )
        assertEquals(listOf("c", "g", "n"), items.bySector().getValue(Position.FRONT_RIGHT).map { it.tempId })
    }

    @Test
    fun `시연 S1 - 방향지시등을 켠 장면에서 한 번 안내`() {
        val policy = NeighborAlertPolicy()
        var state = NeighborAlertPolicy.State()
        val lines = DemoTraffic.laneChange().mapIndexedNotNull { i, frame ->
            val signal = if (i >= DemoTraffic.S1_MY_SIGNAL_FROM_STEP) Side.RIGHT else null
            val (alert, next) = policy.decide(frame, now.plusSeconds(i * 2L), state, signal)
            state = next
            alert?.line?.let { i to it }
        }
        assertEquals(listOf(3 to "오른쪽 뒤 차가 빠르게 다가와요. 차선 변경은 천천히 하세요."), lines)
    }

    @Test
    fun `시연 접근 - 30m 안에 들어오면 한 번 안내`() {
        val policy = NeighborAlertPolicy()
        var state = NeighborAlertPolicy.State()
        val lines = DemoTraffic.closing().mapIndexedNotNull { i, frame ->
            val (alert, next) = policy.decide(frame, now.plusSeconds(i * 2L), state)
            state = next
            alert?.line
        }
        assertEquals(listOf("왼쪽 뒤 차량과 거리를 두세요."), lines)
    }

    @Test
    fun `동의 2단 구조`() {
        assertEquals(false, Consent(location = false, share = true).visibleToOthers)
        assertEquals(true, Consent(location = true, share = false).canViewNearby)
        assertEquals(false, Consent(location = true, share = false).visibleToOthers)
    }

    @Test
    fun `내 등급`() {
        assertEquals(Grade.GOOD, SelfGrade.of(80))
        assertEquals(Grade.NORMAL, SelfGrade.of(79))
        assertEquals(Grade.CAUTION, SelfGrade.of(59))
    }
}
