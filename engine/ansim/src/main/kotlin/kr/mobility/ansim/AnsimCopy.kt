package kr.mobility.ansim

import kr.mobility.core.text.Korean
import kotlin.math.abs
import kotlin.math.roundToInt

/** 관심 주행 문구. 주행 중 화면은 색 + 이유 한 줄 + 남은 거리만 (R-07). 예시는 정의서 5장. */
class AnsimCopy {
    /** 800 → "800m", 1200 → "1km", 1500 → "1.5km". 1km 이상은 0.5km 단위. */
    fun distance(meters: Int): String = when {
        meters < 100 -> "바로"
        meters < 1000 -> "${(meters / 100.0).roundToInt() * 100}m"
        else -> {
            val halfKm = (meters / 500.0).roundToInt() / 2.0
            if (halfKm % 1.0 == 0.0) "${halfKm.toInt()}km" else "${halfKm}km"
        }
    }

    fun noun(type: FactorType): String = when (type) {
        FactorType.WRONG_WAY -> "역주행 차량 경고"
        FactorType.ACCIDENT_PRONE -> "사고 잦은 구간"
        FactorType.SLIPPERY -> "미끄러운 도로"
        FactorType.FOG_AREA -> "안개 구간"
        FactorType.DOWNHILL -> "급경사 내리막"
        FactorType.ROAD_DAMAGE_AREA, FactorType.ROAD_DAMAGE -> "도로 파손"
        FactorType.CONSTRUCTION_AREA, FactorType.ROADWORK -> "공사 구간"
        FactorType.FALLING_ROCK -> "낙석 주의 구간"
        FactorType.WILDLIFE -> "야생동물 출몰 구간"
        FactorType.NARROW -> "좁은 길"
        FactorType.RAILROAD -> "철길 건널목"
        FactorType.SCHOOL_ZONE -> "어린이보호구역"
        FactorType.DISASTER -> "재난 상황"
        FactorType.ACCIDENT -> "사고 현장"
        FactorType.SUDDEN_JAM -> "급정체 구간"
        FactorType.RAIN -> "비"
        FactorType.NIGHT -> "야간"
        FactorType.ICY -> "결빙 주의"
        FactorType.FOG_LIGHTS -> "안개"
        FactorType.WASHER_LOW -> "와셔액 부족"
    }

    /** 앞 요인을 꾸미는 말과, 뒤에 붙일 짧은 이름. "사고가 잦은" + "내리막". */
    private val modifier = mapOf(
        FactorType.ACCIDENT_PRONE to "사고가 잦은",
        FactorType.SLIPPERY to "미끄러운",
        FactorType.FOG_AREA to "안개 낀",
    )
    private val baseNoun = mapOf(FactorType.DOWNHILL to "내리막", FactorType.NARROW to "좁은 길")

    private fun tip(a: Assessment, lead: RiskFactor): String = when (lead.type) {
        FactorType.FOG_AREA -> if (a.has(FactorType.FOG_LIGHTS)) "속도를 줄이세요" else "안개등을 켜 보세요"
        FactorType.WRONG_WAY -> "오른쪽 차로로 천천히"
        FactorType.SCHOOL_ZONE -> "시속 30km 이하"
        FactorType.RAILROAD -> "건너기 전 일단 멈춤"
        FactorType.SUDDEN_JAM -> "미리 속도를 줄이세요"
        else -> "속도를 줄이세요"
    }

    /** 주행 중 한 줄. 예: "앞 800m 안개 구간 · 안개등을 켜 보세요". */
    fun screenLine(a: Assessment): String {
        if (a.signal == Signal.GREEN) return "주의할 구간이 없어요"
        val lead = a.lead
        if (lead == null) {
            val env = a.factors.joinToString(" · ") { noun(it.type) }
            return "$env · 차간 거리를 넉넉히 두세요"
        }
        return "앞 ${distance(lead.distanceMeters ?: 0)} ${noun(lead.type)} · ${tip(a, lead)}"
    }

    /**
     * 빨강 전환 음성 (F-06). 이번에 새로 생긴 요인을 주인공으로, 환경 한 문장, 행동 한 문장.
     * 예(S1): "1km 앞 사고가 잦은 내리막이에요. 비가 와서 미끄러워요. 속도를 줄이고 차간 거리를 넉넉히 두세요."
     */
    fun voiceLine(current: Assessment, previous: Assessment?): String {
        val before = previous?.factors?.map { it.key }?.toSet() ?: emptySet()
        val ahead = current.factors.filter { it.group != FactorGroup.CAR }
        val lead = ahead.filter { it.key !in before }
            .sortedWith(compareByDescending<RiskFactor> { it.points }.thenBy { it.distanceMeters })
            .firstOrNull() ?: current.lead
        val parts = mutableListOf<String>()
        if (lead != null) {
            val d = distance(lead.distanceMeters ?: 0)
            parts += when (lead.type) {
                FactorType.ACCIDENT -> "$d 앞 사고가 났어요."
                FactorType.DISASTER -> "$d 앞 재난 상황이 있어요."
                FactorType.ROADWORK, FactorType.CONSTRUCTION_AREA -> "$d 앞 공사 중이에요."
                FactorType.ROAD_DAMAGE, FactorType.ROAD_DAMAGE_AREA -> "$d 앞 도로가 파손됐어요."
                FactorType.SUDDEN_JAM -> "앞에 갑자기 막히는 구간이 있어요."
                FactorType.WRONG_WAY -> "$d 앞 역주행 차량 경고가 있어요."
                else -> "$d 앞 ${Korean.iya(combinedNoun(lead, ahead))}."
            }
        }
        envSentence(current)?.let { parts += it }
        lead?.let { actionSentence(it.type) }?.let { parts += it }
        return parts.joinToString(" ")
    }

    /** 근처(500m 안)에 내리막·좁은 길이 함께 있으면 "사고가 잦은 내리막"처럼 묶는다. */
    private fun combinedNoun(lead: RiskFactor, ahead: List<RiskFactor>): String {
        val mod = modifier[lead.type] ?: return noun(lead.type)
        val partner = ahead.firstOrNull {
            it.type in baseNoun && it.distanceMeters != null && lead.distanceMeters != null &&
                abs(it.distanceMeters - lead.distanceMeters) <= 500
        } ?: return noun(lead.type)
        return "$mod ${baseNoun.getValue(partner.type)}"
    }

    private fun envSentence(a: Assessment): String? = when {
        a.has(FactorType.RAIN) -> "비가 와서 미끄러워요."
        a.has(FactorType.ICY) -> "노면이 얼었을 수 있어요."
        a.has(FactorType.FOG_LIGHTS) -> "안개로 앞이 잘 안 보여요."
        a.has(FactorType.NIGHT) -> "어두우니 더 조심하세요."
        else -> null
    }

    private fun actionSentence(type: FactorType): String? = when (type) {
        FactorType.ACCIDENT_PRONE, FactorType.DOWNHILL, FactorType.SLIPPERY, FactorType.FOG_AREA,
        FactorType.ROAD_DAMAGE_AREA, FactorType.FALLING_ROCK -> "속도를 줄이고 차간 거리를 넉넉히 두세요."
        FactorType.ACCIDENT, FactorType.DISASTER, FactorType.ROADWORK, FactorType.ROAD_DAMAGE,
        FactorType.CONSTRUCTION_AREA -> "미리 속도를 줄이세요."
        FactorType.WRONG_WAY -> "속도를 줄이고 오른쪽 차로로 가세요."
        FactorType.SCHOOL_ZONE -> "시속 30km 이하로 가세요."
        FactorType.RAILROAD -> "건너기 전에 일단 멈추세요."
        FactorType.WILDLIFE, FactorType.NARROW -> "속도를 줄이세요."
        else -> null
    }

    /** 안내 직후 들은 말이 "왜?"인지 (F-07). */
    fun isWhyQuestion(text: String?): Boolean {
        val t = text?.trim() ?: return false
        return t.startsWith("왜") || t.contains("이유") || t.contains("뭐 때문")
    }

    /** "왜?"라고 물으면 읽어주는 이유 (F-07). 점수 계산을 그대로 보여준다. */
    fun whyLine(a: Assessment): String {
        if (a.factors.isEmpty()) return "지금은 주의할 요인이 없어요. 초록이에요."
        val items = a.factors.joinToString(", ") { f ->
            val where = f.distanceMeters?.let { "${distance(it)} 앞 " } ?: ""
            "$where${noun(f.type)} ${f.points}점"
        }
        val color = when (a.signal) { Signal.GREEN -> "초록"; Signal.YELLOW -> "노랑"; Signal.RED -> "빨강" }
        val cap = if (a.capped) " 앞길에 주의 지점이 없어서 노랑까지만 올려요." else ""
        return "$items. 합계 ${a.score}점이라 ${Korean.iya(color)}.$cap"
    }
}
