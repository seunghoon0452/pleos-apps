package kr.mobility.mirichaeum

import java.time.YearMonth
import java.time.ZonedDateTime
import kotlin.math.max

/** 미리 채움 안내대로 채운 기록 1건. */
data class PrefillRecord(
    val at: ZonedDateTime,
    val kind: SolutionKind,
    /** 채운 양(kWh 또는 L). */
    val amount: Double,
    val unitPrice: Double,
    /** 실제로 더 든 시간(분). 체류 충전은 0. */
    val extraMinutes: Double,
    /** 비교 기준 단가. null이면 우회 초급속 요금. 주유는 주변 평균 가격을 넣는다. */
    val baselineUnitPrice: Double? = null,
)

data class SavingsReport(
    val month: YearMonth,
    val count: Int,
    /** 우회 급속 대신 체류·경로상에서 채운 횟수. */
    val avoidedDetours: Int,
    val savedMinutes: Double,
    val savedWon: Double,
)

/**
 * 월간 절약 리포트 (F-13). 기준(가안): 미리 채움이 없었다면 출발 직전에
 * 우회 초급속 충전을 했을 것으로 본다 — 우회 10분 + 100kW × 0.85로 충전, 393.1원/kWh.
 */
class SavingsCalculator(
    private val baselinePrice: Double = Tariff.ULTRA,
    private val baselineDetourMinutes: Double = 10.0,
    private val baselinePowerKw: Double = 100.0,
    private val fastChargeFactor: Double = 0.85,
) {
    fun baselineMinutes(amountKwh: Double): Double =
        baselineDetourMinutes + amountKwh / (baselinePowerKw * fastChargeFactor) * 60.0

    fun savedMinutes(r: PrefillRecord): Double {
        // 주유는 시간 기준을 따로 두지 않는다(가격 차이만 센다).
        if (r.baselineUnitPrice != null) return 0.0
        return max(0.0, baselineMinutes(r.amount) - r.extraMinutes)
    }

    fun savedWon(r: PrefillRecord): Double = max(0.0, ((r.baselineUnitPrice ?: baselinePrice) - r.unitPrice) * r.amount)

    fun monthly(records: List<PrefillRecord>, month: YearMonth): SavingsReport {
        val inMonth = records.filter { YearMonth.from(it.at) == month }
        return SavingsReport(
            month = month,
            count = inMonth.size,
            avoidedDetours = inMonth.count { it.kind != SolutionKind.DETOUR && it.baselineUnitPrice == null },
            savedMinutes = inMonth.sumOf { savedMinutes(it) },
            savedWon = inMonth.sumOf { savedWon(it) },
        )
    }
}


/** 기록 보관 기간 (A-44): 운행·체류·주행거리는 8주, 채움 기록은 12개월. */
object RecordRetention {
    fun cutoffLogs(now: ZonedDateTime): ZonedDateTime = now.minusWeeks(8)
    fun cutoffPrefills(now: ZonedDateTime): ZonedDateTime = now.minusMonths(12)
}
