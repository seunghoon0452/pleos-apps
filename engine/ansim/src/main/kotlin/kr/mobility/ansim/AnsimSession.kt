package kr.mobility.ansim

import kr.mobility.core.vehicle.DrivingState

/** 한 순간의 결과. 앱은 [screenLine]을 그리고, [voiceLine]이 있으면 TTS로 읽는다. */
data class Frame(
    val assessment: Assessment,
    val screenLine: String,
    val voiceLine: String?,
    val decision: VoiceDecision,
)

/** 주차 후 요약 (S6). 지나간 구간의 표식만 운행 중에 들고 있다가, 끝나면 개수만 남긴다(R-09). */
data class TripSummary(val cautionKeys: Set<String> = emptySet(), val redTransitions: Int = 0) {
    val passedCount: Int get() = cautionKeys.size

    fun record(current: Assessment, previous: Assessment?): TripSummary {
        if (current.signal == Signal.GREEN) return this
        val keys = current.factors.filter { it.group != FactorGroup.CAR }.map { it.key }
        val red = if (current.signal == Signal.RED && previous?.signal != Signal.RED) 1 else 0
        return TripSummary(cautionKeys + keys, redTransitions + red)
    }

    fun line(): String =
        if (passedCount == 0) "오늘은 주의 구간 없이 다녀왔어요." else "오늘 주의 구간 ${passedCount}곳을 안전하게 지났어요."
}

/**
 * 운행 한 번 동안의 상태를 들고 있는 입구. SDK 이벤트가 올 때마다 [update]를 부른다.
 * 계산은 모두 순수 함수라 이 클래스도 차 없이 테스트할 수 있다.
 */
class AnsimSession(
    private val judge: SignalJudge = SignalJudge(),
    private val policy: SignalAlertPolicy = SignalAlertPolicy(),
    private val copy: AnsimCopy = AnsimCopy(),
) {
    private var previous: Assessment? = null
    private var history = VoiceHistory()

    var summary = TripSummary()
        private set

    fun update(snapshot: RoadSnapshot, state: DrivingState, naviSpeaking: Boolean = false): Frame {
        val current = judge.assess(snapshot)
        val decision = policy.decide(previous, current, history, state, naviSpeaking)
        val voice = if (decision.action == VoiceAction.SPEAK) copy.voiceLine(current, previous) else null
        history = decision.history
        summary = summary.record(current, previous)
        // 기다리는 중에는 '빨강으로 바뀐 순간'의 비교 기준을 유지한다.
        if (decision.action != VoiceAction.WAIT) previous = current
        return Frame(current, copy.screenLine(current), voice, decision)
    }

    /** "왜?" 질문 (F-07). */
    fun why(): String = previous?.let { copy.whyLine(it) } ?: "아직 판단한 내용이 없어요."

    /** 주차하면 요약 문장을 돌려주고 운행 기록을 버린다. */
    fun endTrip(): String {
        val line = summary.line()
        previous = null
        history = VoiceHistory()
        summary = TripSummary()
        return line
    }
}
