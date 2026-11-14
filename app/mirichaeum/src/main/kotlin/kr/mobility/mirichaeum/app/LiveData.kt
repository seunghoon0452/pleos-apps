package kr.mobility.mirichaeum.app

import android.util.Log
import kr.mobility.core.geo.Geo
import kr.mobility.core.geo.GeoPoint
import kr.mobility.core.geo.Katec
import kr.mobility.mirichaeum.Charger
import kr.mobility.mirichaeum.FuelStation
import kr.mobility.mirichaeum.PlaceCandidate
import kr.mobility.mirichaeum.PlaceSearch
import kr.mobility.mirichaeum.RouteCharger
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * 실제 데이터 연결(정의서 11장). 키가 없으면 쓰지 않고 시연 목록으로 동작한다.
 * 호출은 모두 차 앱에서 바로 한다(A-07). 보내는 값은 검색어와 검색에 필요한 좌표뿐이다(R-14).
 * 실패하면 빈 목록을 돌려주고 앱은 '찾지 못했어요'로 안내한다.
 */
class LiveData(private val kakaoKey: String, private val dataGoKey: String, private val opinetKey: String) {
    val hasPlaces: Boolean get() = kakaoKey.isNotBlank()
    val hasChargers: Boolean get() = kakaoKey.isNotBlank() && dataGoKey.isNotBlank()
    val hasStations: Boolean get() = opinetKey.isNotBlank()

    val places = PlaceSearch { query, near ->
        val center = near?.let { "&x=${it.lng}&y=${it.lat}&sort=distance" } ?: ""
        get("https://dapi.kakao.com/v2/local/search/keyword.json?query=${enc(query)}&size=5$center", mapOf("Authorization" to "KakaoAK $kakaoKey"))
            ?.let(LiveParsers::kakaoPlaces) ?: emptyList()
    }

    /** 지금 위치의 시·군·구 충전소. 체류 충전(완속)과 경로상 후보(5km 안)의 재료. */
    fun chargers(here: GeoPoint, now: ZonedDateTime): Pair<List<Charger>, List<RouteCharger>> {
        val region = get(
            "https://dapi.kakao.com/v2/local/geo/coord2regioncode.json?x=${here.lng}&y=${here.lat}",
            mapOf("Authorization" to "KakaoAK $kakaoKey"),
        )?.let(LiveParsers::legalRegionCode) ?: return emptyList<Charger>() to emptyList()
        val body = get(
            "https://apis.data.go.kr/B552584/EvCharger/getChargerInfo?serviceKey=${enc(dataGoKey)}&numOfRows=2000&pageNo=1" +
                "&zcode=${region.take(2)}&zscode=${region.take(5)}&dataType=JSON",
        ) ?: return emptyList<Charger>() to emptyList()
        val all = LiveParsers.chargers(body, now)
        return all to all.filter { Geo.distanceMeters(here, it.location) <= NEAR_METERS }
            .map { RouteCharger(it, LiveParsers.detourMinutes(here, it.location)) }
    }

    fun stations(here: GeoPoint): List<FuelStation> {
        val (x, y) = Katec.fromWgs84(here)
        val body = get(
            "https://www.opinet.co.kr/api/aroundAll.do?code=${enc(opinetKey)}&out=json&x=$x&y=$y&radius=$NEAR_METERS&sort=1&prodcd=B027",
        ) ?: return emptyList()
        return LiveParsers.stations(body, here)
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun get(url: String, headers: Map<String, String> = emptyMap()): String? = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 5_000
        c.readTimeout = 8_000
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        try {
            if (c.responseCode !in 200..299) {
                Log.w(TAG, "HTTP ${c.responseCode} from ${URL(url).host}")
                null
            } else {
                c.inputStream.bufferedReader().use { it.readText() }
            }
        } finally {
            c.disconnect()
        }
    }.onFailure { Log.w(TAG, "request to ${runCatching { URL(url).host }.getOrNull()} failed: ${it.javaClass.simpleName}") }.getOrNull()

    private companion object {
        const val TAG = "LiveData"
        const val NEAR_METERS = 5000
    }
}

/** API 응답 해석. 형식이 다르면 빈 값을 돌려준다. 네트워크 없이 테스트한다. */
object LiveParsers {
    private val statTime = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")

    /** 평소 경로를 모르므로 왕복 거리 × 1.3 ÷ 시속 30km로 이탈 시간을 어림한다 (A-48). */
    fun detourMinutes(here: GeoPoint, target: GeoPoint): Double =
        Geo.estimatedRoadKm(here, target) * 2 / 30.0 * 60.0

    fun kakaoPlaces(body: String): List<PlaceCandidate> = runCatching {
        val docs = JSONObject(body).getJSONArray("documents")
        (0 until docs.length()).map { i ->
            val d = docs.getJSONObject(i)
            PlaceCandidate(d.getString("place_name"), GeoPoint(d.getString("y").toDouble(), d.getString("x").toDouble()), d.optString("address_name").takeIf { it.isNotBlank() })
        }
    }.getOrDefault(emptyList())

    /** 법정동 코드(앞 2자리 시도, 앞 5자리 시군구). */
    fun legalRegionCode(body: String): String? = runCatching {
        val docs = JSONObject(body).getJSONArray("documents")
        (0 until docs.length()).map { docs.getJSONObject(it) }.first { it.getString("region_type") == "B" }.getString("code")
    }.getOrNull()

    /** 한 줄이 충전기 1대이므로 충전소별로 묶는다. stat 2 = 충전 대기. */
    fun chargers(body: String, now: ZonedDateTime): List<Charger> = runCatching {
        val items = JSONObject(body).getJSONObject("items").getJSONArray("item")
        val rows = (0 until items.length()).map { items.getJSONObject(it) }
        rows.groupBy { it.getString("statId") }.map { (id, list) ->
            val first = list.first()
            val updated = list.mapNotNull { r ->
                runCatching { LocalDateTime.parse(r.getString("statUpdDt"), statTime).atZone(now.zone) }.getOrNull()
            }.maxOrNull()
            Charger(
                id = id,
                name = first.getString("statNm"),
                location = GeoPoint(first.getString("lat").toDouble(), first.getString("lng").toDouble()),
                powerKw = list.maxOf { it.optString("output").toDoubleOrNull() ?: 7.0 },
                available = list.count { it.optString("stat") == "2" },
                total = list.size,
                address = first.optString("addr").takeIf { it.isNotBlank() },
                statusAgeMinutes = updated?.let { Duration.between(it, now).toMinutes().toInt() },
            )
        }
    }.getOrDefault(emptyList())

    fun stations(body: String, here: GeoPoint): List<FuelStation> = runCatching {
        val oil = JSONObject(body).getJSONObject("RESULT").getJSONArray("OIL")
        (0 until oil.length()).map { i ->
            val o = oil.getJSONObject(i)
            val at = Katec.toWgs84(o.getDouble("GIS_X_COOR"), o.getDouble("GIS_Y_COOR"))
            FuelStation(o.getString("UNI_ID"), o.getString("OS_NM"), at, o.getDouble("PRICE"), detourMinutes(here, at))
        }
    }.getOrDefault(emptyList())
}
