package kr.mobility.mirichaeum

import kotlin.math.max

/** 판정 결과와 그 근거 숫자. 화면의 "필요 vs 갈 수 있는 거리"에 그대로 쓴다. */
data class Judgement(
    val verdict: Verdict,
    val needKm: Double,
    /** 차가 알려준 주행가능거리. */
    val rangeKm: Double,
    /** 겨울 보정을 한 주행가능거리. */
    val correctedRangeKm: Double,
    val winterFactor: Double,
    /** 필요 거리의 15%. */
    val bufferKm: Double,
    /** 권장 잔량을 거리로 바꾼 값(전기차 15%, 내연기관 50km). */
    val reserveKm: Double,
    /** 최소 잔량을 거리로 바꾼 값(전기차 10%, 내연기관 30km). 최소 충전량 기준. */
    val minReserveKm: Double,
) {
    val marginKm: Double get() = bufferKm + reserveKm

    /** 충분(초록) = 권장 충전량 기준 거리 = 필요 + 15% + 권장 잔량. */
    val safeKm: Double get() = needKm + marginKm

    /** 빠듯(노랑) = 최소 충전량 기준 거리 = 필요 + 최소 잔량. 이보다 적으면 부족(빨강). */
    val minimumKm: Double get() = needKm + minReserveKm

    /** 충분(초록)이 되려면 더 채워야 하는 거리. */
    val shortfallKm: Double get() = max(0.0, safeKm - correctedRangeKm)
}

/** R-07 겨울 보정, R-08 최소·권장 기준, R-09 판정. */
class RangeJudge(
    private val bufferRatio: Double = 0.15,
    private val evReservePercent: Double = 15.0,
    private val iceReserveKm: Double = 50.0,
    private val evMinReservePercent: Double = 10.0,
    private val iceMinReserveKm: Double = 30.0,
) {
    /** 전기차만 보정한다. 0℃ 미만 × 0.8, 0~10℃ × 0.9. 기온을 모르면 보정하지 않는다. */
    fun winterFactor(fuelType: FuelType, outsideTempC: Double?): Double {
        if (fuelType != FuelType.EV || outsideTempC == null) return 1.0
        return when {
            outsideTempC < 0.0 -> 0.8
            outsideTempC <= 10.0 -> 0.9
            else -> 1.0
        }
    }

    /** 겨울 보정을 한 전비·연비. 필요 충전량 계산(R-12)에 쓴다. */
    fun correctedEfficiency(snapshot: EnergySnapshot): Double =
        snapshot.efficiency * winterFactor(snapshot.fuelType, snapshot.outsideTempC)

    fun reserveKm(snapshot: EnergySnapshot): Double = when (snapshot.fuelType) {
        FuelType.EV -> snapshot.capacity * evReservePercent / 100.0 * correctedEfficiency(snapshot)
        FuelType.ICE -> iceReserveKm
    }

    fun minReserveKm(snapshot: EnergySnapshot): Double = when (snapshot.fuelType) {
        FuelType.EV -> snapshot.capacity * evMinReservePercent / 100.0 * correctedEfficiency(snapshot)
        FuelType.ICE -> iceMinReserveKm
    }

    fun judge(snapshot: EnergySnapshot, needKm: Double): Judgement {
        require(needKm >= 0) { "needKm < 0" }
        val factor = winterFactor(snapshot.fuelType, snapshot.outsideTempC)
        val corrected = snapshot.rangeKm * factor
        val buffer = needKm * bufferRatio
        val reserve = reserveKm(snapshot)
        val minReserve = minReserveKm(snapshot)
        val verdict = when {
            corrected >= needKm + buffer + reserve -> Verdict.ENOUGH
            corrected >= needKm + minReserve -> Verdict.TIGHT
            else -> Verdict.SHORT
        }
        return Judgement(verdict, needKm, snapshot.rangeKm, corrected, factor, buffer, reserve, minReserve)
    }
}
