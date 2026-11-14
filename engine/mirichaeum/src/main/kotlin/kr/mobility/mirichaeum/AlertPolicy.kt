package kr.mobility.mirichaeum

import kr.mobility.core.vehicle.DrivingState
import java.time.ZonedDateTime

enum class AlertTrigger {
    /** 운행 시작: 시동을 걸었고 아직 주차 상태. */
    TRIP_START,

    /** 체류 충전 후보 장소에 도착. */
    ARRIVED_AT_DWELL,
}

/** 이미 보낸 음성 안내. [key]가 같으면 같은 안내로 본다. */
data class SentAlert(val at: ZonedDateTime, val key: String)

enum class AlertChannel {
    /** 음성 안내 1회 + 카드. */
    VOICE,

    /** 카드만 바꾸고 말하지 않음. */
    CARD_ONLY,
}

data class AlertDecision(val channel: AlertChannel, val why: String)

/** R-10: 주차 상태에서만, 하루 최대 2회, 같은 안내 반복 금지. 초록은 말하지 않는다(S3). */
class AlertPolicy(private val maxPerDay: Int = 2) {
    fun decide(
        trigger: AlertTrigger,
        state: DrivingState,
        verdict: Verdict,
        key: String,
        now: ZonedDateTime,
        sent: List<SentAlert>,
    ): AlertDecision {
        if (verdict == Verdict.ENOUGH) return AlertDecision(AlertChannel.CARD_ONLY, "충분하면 말하지 않음")
        if (state != DrivingState.PARKED && state != DrivingState.CHARGING) {
            return AlertDecision(AlertChannel.CARD_ONLY, "주행 중에는 새 안내를 시작하지 않음")
        }
        val today = sent.filter { it.at.withZoneSameInstant(now.zone).toLocalDate() == now.toLocalDate() }
        if (today.size >= maxPerDay) return AlertDecision(AlertChannel.CARD_ONLY, "하루 최대 ${maxPerDay}회")
        if (today.any { it.key == key }) return AlertDecision(AlertChannel.CARD_ONLY, "같은 안내 반복 금지")
        return AlertDecision(AlertChannel.VOICE, if (trigger == AlertTrigger.TRIP_START) "운행 시작" else "체류 장소 도착")
    }
}
