package kr.mobility.core.geo

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/** 위도·경도 한 점. 화면·SDK와 무관한 순수 값이다. */
data class GeoPoint(val lat: Double, val lng: Double) {
    init {
        require(lat in -90.0..90.0) { "lat out of range: $lat" }
        require(lng in -180.0..180.0) { "lng out of range: $lng" }
    }
}

object Geo {
    private const val EARTH_RADIUS_M = 6_371_008.8

    /** 두 점 사이의 직선(대권) 거리, 미터. */
    fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLng = Math.toRadians(b.lng - a.lng)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * EARTH_RADIUS_M * atan2(sqrt(h), sqrt(1 - h))
    }

    /** from에서 to를 바라보는 방위각. 0 = 북, 90 = 동. */
    fun bearingDegrees(from: GeoPoint, to: GeoPoint): Double {
        val lat1 = Math.toRadians(from.lat)
        val lat2 = Math.toRadians(to.lat)
        val dLng = Math.toRadians(to.lng - from.lng)
        val y = sin(dLng) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLng)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /**
     * 내비 경로 거리를 받을 수 없을 때 쓰는 도로 거리 추정치(km).
     * 서비스 정의서 R-06 ③: 직선거리 × 1.3.
     */
    fun estimatedRoadKm(a: GeoPoint, b: GeoPoint, detourFactor: Double = 1.3): Double =
        distanceMeters(a, b) / 1000.0 * detourFactor
}

/**
 * 위치를 약 [sizeMeters] 크기의 칸으로 묶은 값.
 * 정확한 좌표 대신 동네 단위로 저장하기 위해 쓴다(정의서 R-01, R-14).
 */
data class PlaceCell(val row: Int, val col: Int, val sizeMeters: Double) {
    val center: GeoPoint
        get() {
            val latStep = sizeMeters / METERS_PER_DEG_LAT
            val lat = (row + 0.5) * latStep
            val lngStep = sizeMeters / (METERS_PER_DEG_LAT * cos(Math.toRadians(lat)))
            return GeoPoint(lat, (col + 0.5) * lngStep)
        }

    companion object {
        private const val METERS_PER_DEG_LAT = 111_320.0
        const val DEFAULT_SIZE_METERS = 300.0

        fun of(point: GeoPoint, sizeMeters: Double = DEFAULT_SIZE_METERS): PlaceCell {
            val latStep = sizeMeters / METERS_PER_DEG_LAT
            val row = floor(point.lat / latStep).toInt()
            val rowCenterLat = (row + 0.5) * latStep
            val lngStep = sizeMeters / (METERS_PER_DEG_LAT * cos(Math.toRadians(rowCenterLat)))
            val col = floor(point.lng / lngStep).toInt()
            return PlaceCell(row, col, sizeMeters)
        }
    }
}
