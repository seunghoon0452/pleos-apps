package kr.mobility.ansim

import kr.mobility.core.vehicle.DrivingState
import java.time.Duration
import java.time.ZonedDateTime

/** 운행 중에만 메모리에 두는 음성 기록. 오래된 것은 잊고, 운행이 끝나면 버린다(R-09). */
data class VoiceHistory(
    val lastSpokenAt: ZonedDateTime? = null,
    /** 말한 구간 표식 → 말한 시각. */
    val spoken: Map<String, ZonedDateTime> = emptyMap(),
    /** 빨강으로 바뀌었지만 내비 음성·신호 대기 때문에 아직 말하지 못함. */
    val pending: Boolean = false,
)

enum class VoiceAction {
    SPEAK,

    /** 내비 음성이 끝나거나 다시 출발하면 말한다. */
    WAIT,
    SILENT,
}

data class VoiceDecision(val action: VoiceAction, val why: String, val history: VoiceHistory)

/** R-06: 빨강으로 바뀔 때만 1회, 같은 구간 반복 금지, 5분에 최대 1회, 내비 음성은 기다림. 신호 대기 중엔 새 안내 없음. */
class SignalAlertPolicy(
    private val cooldown: Duration = Duration.ofMinutes(5),
    /** 이 시간이 지나면 이미 지나간 구간으로 보고 잊는다. */
    private val memory: Duration = Duration.ofMinutes(30),
    private val voiceEnabled: Boolean = true,
) {
    fun decide(
        previous: Assessment?,
        current: Assessment,
        history: VoiceHistory,
        state: DrivingState,
        naviSpeaking: Boolean = false,
    ): VoiceDecision {
        val now = current.at
        val fresh = history.copy(spoken = history.spoken.filterValues { Duration.between(it, now) < memory })
        if (current.signal != Signal.RED) return VoiceDecision(VoiceAction.SILENT, "빨강 아님", fresh.copy(pending = false))
        if (!voiceEnabled) return VoiceDecision(VoiceAction.SILENT, "음성 꺼짐", fresh)
        val becameRed = previous?.signal != Signal.RED
        if (!becameRed && !fresh.pending) return VoiceDecision(VoiceAction.SILENT, "이미 빨강", fresh)

        val key = current.lead?.key ?: "car"
        if (key in fresh.spoken) return VoiceDecision(VoiceAction.SILENT, "같은 구간 반복 안 함", fresh.copy(pending = false))
        val last = fresh.lastSpokenAt
        if (last != null && Duration.between(last, now) < cooldown) {
            return VoiceDecision(VoiceAction.SILENT, "5분 안에 이미 안내함", fresh.copy(pending = false))
        }
        if (state == DrivingState.PARKED || state == DrivingState.CHARGING) {
            return VoiceDecision(VoiceAction.SILENT, "주차 중", fresh.copy(pending = false))
        }
        if (naviSpeaking || state == DrivingState.STOPPED) {
            return VoiceDecision(VoiceAction.WAIT, if (naviSpeaking) "내비 음성 기다림" else "신호 대기 중", fresh.copy(pending = true))
        }
        return VoiceDecision(
            VoiceAction.SPEAK,
            "빨강으로 바뀜",
            VoiceHistory(lastSpokenAt = now, spoken = fresh.spoken + (key to now), pending = false),
        )
    }
}
