package kr.mobility.mirichaeum.app

import android.content.Context
import android.util.Log
import kr.mobility.core.geo.GeoPoint
import kr.mobility.core.geo.PlaceCell
import kr.mobility.mirichaeum.DistanceSource
import kr.mobility.mirichaeum.DwellRecord
import kr.mobility.mirichaeum.OdometerReading
import kr.mobility.mirichaeum.OpenTrip
import kr.mobility.mirichaeum.ParkedInfo
import kr.mobility.mirichaeum.PrefillRecord
import kr.mobility.mirichaeum.RecorderState
import kr.mobility.mirichaeum.SentAlert
import kr.mobility.mirichaeum.SolutionKind
import kr.mobility.mirichaeum.TripEntry
import kr.mobility.mirichaeum.TripRecord
import kr.mobility.mirichaeum.VoiceTrip
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.ZonedDateTime

data class MiriSettings(
    /** 시연 모드(F-15). 공모전 빌드는 켜진 채로 시작한다. */
    val demo: Boolean = true,
    /** 시작·동의 화면(SC-01)을 지났는지. */
    val onboarded: Boolean = false,
    /** 첫 사용 질문 "하루에 보통 몇 km 타세요?"의 답 (R-15). */
    val dailyKm: Double? = null,
    val homeCharging: Boolean = false,
    /** 운행 기록 켜기·끄기 (R-14). */
    val recording: Boolean = true,
    /** 내연기관 탱크 용량(L). 차가 알려주지 않으므로 설정에서 받는다. */
    val tankLiters: Double = 50.0,
)

/**
 * 미리 채움 저장소. 모든 기록은 차 안 앱 저장 공간에만 둔다(R-14: 차량 기록은 차 밖으로 보내지 않음).
 * 위치는 PlaceCell(300m 칸)로만 저장한다. 파일 하나에 JSON 하나.
 */
class MiriStore(context: Context, name: String = "miri") {
    private val dir = File(context.filesDir, name).apply { mkdirs() }

    private fun read(key: String): String? = runCatching { File(dir, "$key.json").takeIf { it.exists() }?.readText() }.getOrNull()

    private fun write(key: String, value: String) {
        val tmp = File(dir, "$key.json.tmp")
        tmp.writeText(value)
        if (!tmp.renameTo(File(dir, "$key.json"))) {
            File(dir, "$key.json").writeText(value)
            tmp.delete()
        }
    }

    private fun <T> list(key: String, parse: (JSONObject) -> T): List<T> = runCatching {
        val arr = JSONArray(read(key) ?: return emptyList())
        (0 until arr.length()).mapNotNull { i -> runCatching { parse(arr.getJSONObject(i)) }.getOrNull() }
    }.onFailure { Log.w(TAG, "read $key failed", it) }.getOrDefault(emptyList())

    private fun <T> saveList(key: String, items: List<T>, toJson: (T) -> JSONObject) =
        write(key, JSONArray().apply { items.forEach { put(toJson(it)) } }.toString())

    var settings: MiriSettings
        get() = runCatching {
            val o = JSONObject(read("settings") ?: return MiriSettings())
            MiriSettings(
                demo = o.optBoolean("demo", true),
                onboarded = o.optBoolean("onboarded", false),
                dailyKm = o.optDoubleOrNull("dailyKm"),
                homeCharging = o.optBoolean("homeCharging", false),
                recording = o.optBoolean("recording", true),
                tankLiters = o.optDouble("tankLiters", 50.0),
            )
        }.getOrDefault(MiriSettings())
        set(s) = write(
            "settings",
            JSONObject()
                .put("demo", s.demo).put("onboarded", s.onboarded).putOpt("dailyKm", s.dailyKm)
                .put("homeCharging", s.homeCharging).put("recording", s.recording).put("tankLiters", s.tankLiters)
                .toString(),
        )

    var trips: List<TripRecord>
        get() = list("trips") { o ->
            TripRecord(time(o, "start"), time(o, "end"), cell(o, "from"), cell(o, "to"), o.getDouble("km"), o.optDoubleOrNull("temp"))
        }
        set(v) = saveList("trips", v) { t ->
            JSONObject().put("start", t.start.toString()).put("end", t.end.toString())
                .put("from", cellJson(t.from)).put("to", cellJson(t.to)).put("km", t.distanceKm).putOpt("temp", t.outsideTempC)
        }

    var dwells: List<DwellRecord>
        get() = list("dwells") { o -> DwellRecord(cell(o, "place"), time(o, "arrive"), time(o, "depart")) }
        set(v) = saveList("dwells", v) { d ->
            JSONObject().put("place", cellJson(d.place)).put("arrive", d.arrive.toString()).put("depart", d.depart.toString())
        }

    var voiceTrips: List<VoiceTrip>
        get() = list("voiceTrips") { o ->
            VoiceTrip(
                id = o.getString("id"),
                at = time(o, "at"),
                placeQuery = o.getString("query"),
                placeName = o.optStringOrNull("name"),
                destination = if (o.has("lat")) GeoPoint(o.getDouble("lat"), o.getDouble("lng")) else null,
                purpose = o.optStringOrNull("purpose"),
                oneWayKm = o.optDoubleOrNull("oneWayKm"),
                distanceSource = o.optStringOrNull("source")?.let { DistanceSource.valueOf(it) },
                weekly = o.optBoolean("weekly", false),
                entry = o.optStringOrNull("entry")?.let { TripEntry.valueOf(it) } ?: TripEntry.VOICE,
            )
        }
        set(v) = saveList("voiceTrips", v) { t ->
            JSONObject().put("id", t.id).put("at", t.at.toString()).put("query", t.placeQuery).putOpt("name", t.placeName)
                .putOpt("lat", t.destination?.lat).putOpt("lng", t.destination?.lng).putOpt("purpose", t.purpose)
                .putOpt("oneWayKm", t.oneWayKm).putOpt("source", t.distanceSource?.name)
                .put("weekly", t.weekly).put("entry", t.entry.name)
        }

    /** 캘린더에서 '이날은 평소대로 안 다녀요'로 고른 날 (F-25). 날짜만 저장한다. */
    var skipDays: Set<LocalDate>
        get() = list("skipDays") { o -> LocalDate.parse(o.getString("date")) }.toSet()
        set(v) = saveList("skipDays", v.sorted()) { d -> JSONObject().put("date", d.toString()) }

    var sentAlerts: List<SentAlert>
        get() = list("sentAlerts") { o -> SentAlert(time(o, "at"), o.getString("key")) }
        set(v) = saveList("sentAlerts", v.takeLast(20)) { a -> JSONObject().put("at", a.at.toString()).put("key", a.key) }

    var prefills: List<PrefillRecord>
        get() = list("prefills") { o ->
            PrefillRecord(
                time(o, "at"), SolutionKind.valueOf(o.getString("kind")), o.getDouble("amount"), o.getDouble("price"),
                o.getDouble("extra"), o.optDoubleOrNull("baseline"),
            )
        }
        set(v) = saveList("prefills", v) { p ->
            JSONObject().put("at", p.at.toString()).put("kind", p.kind.name).put("amount", p.amount).put("price", p.unitPrice)
                .put("extra", p.extraMinutes).putOpt("baseline", p.baselineUnitPrice)
        }

    var odometer: List<OdometerReading>
        get() = list("odometer") { o -> OdometerReading(time(o, "at"), o.getDouble("km")) }
        set(v) = saveList("odometer", v.takeLast(60)) { r -> JSONObject().put("at", r.at.toString()).put("km", r.km) }

    var recorder: RecorderState
        get() = runCatching {
            val o = JSONObject(read("recorder") ?: return RecorderState())
            RecorderState(
                open = o.optJSONObject("open")?.let(::openTrip),
                parked = o.optJSONObject("parked")?.let { p ->
                    ParkedInfo(
                        at = time(p, "at"),
                        place = p.optJSONObject("place")?.let(::cellOf),
                        point = point(p),
                        odometerKm = p.optDoubleOrNull("odo"),
                        pending = p.optJSONObject("pending")?.let(::openTrip),
                    )
                },
            )
        }.getOrDefault(RecorderState())
        set(s) = write(
            "recorder",
            JSONObject().putOpt("open", s.open?.let(::openTripJson)).putOpt(
                "parked",
                s.parked?.let { p ->
                    JSONObject().put("at", p.at.toString()).putOpt("place", p.place?.let(::cellJson))
                        .putOpt("lat", p.point?.lat).putOpt("lng", p.point?.lng).putOpt("odo", p.odometerKm)
                        .putOpt("pending", p.pending?.let(::openTripJson))
                },
            ).toString(),
        )

    /** 설정의 '기록 모두 지우기'. 설정 자체는 남긴다. */
    fun clearRecords() {
        listOf("trips", "dwells", "voiceTrips", "skipDays", "sentAlerts", "prefills", "odometer", "recorder").forEach { File(dir, "$it.json").delete() }
    }

    private fun openTrip(o: JSONObject) = OpenTrip(
        start = time(o, "start"),
        from = o.optJSONObject("from")?.let(::cellOf),
        fromPoint = point(o),
        startOdometerKm = o.optDoubleOrNull("odo"),
        outsideTempC = o.optDoubleOrNull("temp"),
    )

    private fun openTripJson(t: OpenTrip) = JSONObject().put("start", t.start.toString()).putOpt("from", t.from?.let(::cellJson))
        .putOpt("lat", t.fromPoint?.lat).putOpt("lng", t.fromPoint?.lng).putOpt("odo", t.startOdometerKm).putOpt("temp", t.outsideTempC)

    // 정확한 좌표는 거리 계산용으로 기록기 상태에만 잠시 들고, 확정된 운행에는 칸만 남긴다.
    private fun point(o: JSONObject): GeoPoint? = if (o.has("lat")) GeoPoint(o.getDouble("lat"), o.getDouble("lng")) else null

    private fun time(o: JSONObject, key: String): ZonedDateTime = ZonedDateTime.parse(o.getString(key))
    private fun cell(o: JSONObject, key: String): PlaceCell = cellOf(o.getJSONObject(key))
    private fun cellOf(o: JSONObject) = PlaceCell(o.getInt("r"), o.getInt("c"), o.getDouble("s"))
    private fun cellJson(c: PlaceCell) = JSONObject().put("r", c.row).put("c", c.col).put("s", c.sizeMeters)

    private companion object {
        const val TAG = "MiriStore"
    }
}

private fun JSONObject.optDoubleOrNull(key: String): Double? = if (has(key) && !isNull(key)) optDouble(key).takeIf { !it.isNaN() } else null
private fun JSONObject.optStringOrNull(key: String): String? = if (has(key) && !isNull(key)) optString(key) else null
