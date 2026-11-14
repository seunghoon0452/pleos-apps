package kr.mobility.core.geo

import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * 오피넷 API가 쓰는 KATEC 좌표(베셀 타원체, 횡메르카토르, 중앙 128E·38N, 축척 0.9999, 가산 400000·600000)와
 * 위경도(WGS84)를 바꾼다. 3변수 근사라 오차가 수 미터 있을 수 있지만 반경 검색에는 충분하다.
 */
object Katec {
    private const val BESSEL_A = 6377397.155
    private const val BESSEL_F = 1 / 299.1528128
    private const val WGS_A = 6378137.0
    private const val WGS_F = 1 / 298.257223563
    private const val K0 = 0.9999
    private const val FALSE_E = 400000.0
    private const val FALSE_N = 600000.0
    private val LAT0 = Math.toRadians(38.0)
    private val LON0 = Math.toRadians(128.0)
    private const val DX = -146.43
    private const val DY = 507.89
    private const val DZ = 681.46

    private val e2 = 2 * BESSEL_F - BESSEL_F * BESSEL_F
    private val ep2 = e2 / (1 - e2)

    private fun toEcef(latRad: Double, lonRad: Double, a: Double, f: Double): Triple<Double, Double, Double> {
        val es = 2 * f - f * f
        val n = a / sqrt(1 - es * sin(latRad).pow(2))
        return Triple(n * cos(latRad) * cos(lonRad), n * cos(latRad) * sin(lonRad), n * (1 - es) * sin(latRad))
    }

    private fun fromEcef(x: Double, y: Double, z: Double, a: Double, f: Double): Pair<Double, Double> {
        val es = 2 * f - f * f
        val p = sqrt(x * x + y * y)
        var lat = atan2(z, p * (1 - es))
        repeat(6) {
            val n = a / sqrt(1 - es * sin(lat).pow(2))
            lat = atan2(z + es * n * sin(lat), p)
        }
        return lat to atan2(y, x)
    }

    private fun meridian(lat: Double): Double {
        val e4 = e2 * e2
        val e6 = e4 * e2
        return BESSEL_A * (
            (1 - e2 / 4 - 3 * e4 / 64 - 5 * e6 / 256) * lat -
                (3 * e2 / 8 + 3 * e4 / 32 + 45 * e6 / 1024) * sin(2 * lat) +
                (15 * e4 / 256 + 45 * e6 / 1024) * sin(4 * lat) -
                (35 * e6 / 3072) * sin(6 * lat)
            )
    }

    /** 위경도 → KATEC (x, y). */
    fun fromWgs84(p: GeoPoint): Pair<Double, Double> {
        val (ex, ey, ez) = toEcef(Math.toRadians(p.lat), Math.toRadians(p.lng), WGS_A, WGS_F)
        val (lat, lon) = fromEcef(ex - DX, ey - DY, ez - DZ, BESSEL_A, BESSEL_F)
        val n = BESSEL_A / sqrt(1 - e2 * sin(lat).pow(2))
        val t = tan(lat).pow(2)
        val c = ep2 * cos(lat).pow(2)
        val a = (lon - LON0) * cos(lat)
        val x = K0 * n * (a + (1 - t + c) * a.pow(3) / 6 + (5 - 18 * t + t * t + 72 * c - 58 * ep2) * a.pow(5) / 120)
        val y = K0 * (
            meridian(lat) - meridian(LAT0) + n * tan(lat) * (
                a * a / 2 + (5 - t + 9 * c + 4 * c * c) * a.pow(4) / 24 +
                    (61 - 58 * t + t * t + 600 * c - 330 * ep2) * a.pow(6) / 720
                )
            )
        return x + FALSE_E to y + FALSE_N
    }

    /** KATEC (x, y) → 위경도. */
    fun toWgs84(x: Double, y: Double): GeoPoint {
        val e4 = e2 * e2
        val e6 = e4 * e2
        val m = meridian(LAT0) + (y - FALSE_N) / K0
        val mu = m / (BESSEL_A * (1 - e2 / 4 - 3 * e4 / 64 - 5 * e6 / 256))
        val e1 = (1 - sqrt(1 - e2)) / (1 + sqrt(1 - e2))
        val lat1 = mu + (3 * e1 / 2 - 27 * e1.pow(3) / 32) * sin(2 * mu) +
            (21 * e1 * e1 / 16 - 55 * e1.pow(4) / 32) * sin(4 * mu) +
            (151 * e1.pow(3) / 96) * sin(6 * mu) + (1097 * e1.pow(4) / 512) * sin(8 * mu)
        val c1 = ep2 * cos(lat1).pow(2)
        val t1 = tan(lat1).pow(2)
        val n1 = BESSEL_A / sqrt(1 - e2 * sin(lat1).pow(2))
        val r1 = BESSEL_A * (1 - e2) / (1 - e2 * sin(lat1).pow(2)).pow(1.5)
        val d = (x - FALSE_E) / (n1 * K0)
        val lat = lat1 - (n1 * tan(lat1) / r1) * (
            d * d / 2 - (5 + 3 * t1 + 10 * c1 - 4 * c1 * c1 - 9 * ep2) * d.pow(4) / 24 +
                (61 + 90 * t1 + 298 * c1 + 45 * t1 * t1 - 252 * ep2 - 3 * c1 * c1) * d.pow(6) / 720
            )
        val lon = LON0 + (d - (1 + 2 * t1 + c1) * d.pow(3) / 6 + (5 - 2 * c1 + 28 * t1 - 3 * c1 * c1 + 8 * ep2 + 24 * t1 * t1) * d.pow(5) / 120) / cos(lat1)
        val (ex, ey, ez) = toEcef(lat, lon, BESSEL_A, BESSEL_F)
        val (wLat, wLon) = fromEcef(ex + DX, ey + DY, ez + DZ, WGS_A, WGS_F)
        return GeoPoint(Math.toDegrees(wLat), Math.toDegrees(wLon))
    }
}
