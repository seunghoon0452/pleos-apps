package kr.mobility.mirichaeum.app

import kr.mobility.core.geo.Geo
import kr.mobility.core.geo.GeoPoint
import kr.mobility.core.geo.Katec
import kr.mobility.core.time.KST
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 문서로 파악한 응답 형식 기준. 실제 키로 호출해 확인하기 전까지는 가정이다. */
class LiveParsersTest {
    private val now = ZonedDateTime.of(2026, 11, 2, 12, 0, 0, 0, KST)
    private val here = GeoPoint(37.40, 127.10)

    @Test
    fun `카카오 장소`() {
        val r = LiveParsers.kakaoPlaces("""{"documents":[{"place_name":"대전 정부청사","address_name":"대전 서구","x":"127.38","y":"36.35"}]}""")
        assertEquals("대전 정부청사", r.single().name)
        assertEquals(36.35, r.single().location.lat)
    }

    @Test
    fun `법정동 코드만 고른다`() {
        val body = """{"documents":[{"region_type":"H","code":"4113565000"},{"region_type":"B","code":"4113510900"}]}"""
        assertEquals("4113510900", LiveParsers.legalRegionCode(body))
        assertNull(LiveParsers.legalRegionCode("""{"documents":[]}"""))
    }

    @Test
    fun `충전기 줄을 충전소별로 묶는다`() {
        val body = """{"items":{"item":[
          {"statId":"A","statNm":"마트","lat":"37.4","lng":"127.1","output":"7","stat":"2","statUpdDt":"20261102115700","addr":"주소"},
          {"statId":"A","statNm":"마트","lat":"37.4","lng":"127.1","output":"7","stat":"3","statUpdDt":"20261102115000","addr":"주소"},
          {"statId":"B","statNm":"급속","lat":"37.5","lng":"127.2","output":"100","stat":"2","statUpdDt":"x","addr":""}
        ]}}"""
        val r = LiveParsers.chargers(body, now)
        val a = r.first { it.id == "A" }
        assertEquals(1, a.available)
        assertEquals(2, a.total)
        assertEquals(3, a.statusAgeMinutes)
        assertTrue(a.isSlow)
        assertNull(r.first { it.id == "B" }.statusAgeMinutes)
    }

    @Test
    fun `주유소 좌표를 위경도로 바꾼다`() {
        val target = GeoPoint(37.41, 127.11)
        val (x, y) = Katec.fromWgs84(target)
        val r = LiveParsers.stations("""{"RESULT":{"OIL":[{"UNI_ID":"U1","OS_NM":"새길","PRICE":1629,"GIS_X_COOR":$x,"GIS_Y_COOR":$y}]}}""", here)
        assertEquals("새길", r.single().name)
        assertTrue(Geo.distanceMeters(target, r.single().location) < 1.0)
    }

    @Test
    fun `형식이 다르면 빈 목록`() {
        assertTrue(LiveParsers.kakaoPlaces("에러").isEmpty())
        assertTrue(LiveParsers.chargers("""{"items":{}}""", now).isEmpty())
        assertTrue(LiveParsers.stations("""{}""", here).isEmpty())
    }
}
