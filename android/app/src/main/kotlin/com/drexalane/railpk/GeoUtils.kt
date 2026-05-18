package com.drexalane.railpk

import kotlin.math.*

object GeoUtils {
    private const val EARTH_RADIUS = 6371000.0

    /** Distance Haversine en mètres. */
    fun haversineM(lon1: Double, lat1: Double, lon2: Double, lat2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2).pow(2)
        return EARTH_RADIUS * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    /** Interpole le PK entre deux points. */
    fun interpolePk(prev: PkPoint, next: PkPoint, lon: Double, lat: Double): Double? {
        val dTotal = haversineM(prev.lon, prev.lat, next.lon, next.lat)
        if (dTotal < 0.001) return null
        val dToPrev = haversineM(lon, lat, prev.lon, prev.lat)
        val ratio = (dToPrev / dTotal).coerceIn(0.0, 1.0)
        return prev.pk + (next.pk - prev.pk) * ratio
    }
}

data class PkPoint(
    val lon: Double,
    val lat: Double,
    val pk: Double,
    val codeLigne: String
)

data class NearestResult(
    val point: PkPoint,
    val index: Int,
    val distanceM: Double
)

data class PkResult(
    val pk: Double,
    val codeLigne: String,
    val interpole: Boolean = false,
    val deadReckoning: Boolean = false
)
