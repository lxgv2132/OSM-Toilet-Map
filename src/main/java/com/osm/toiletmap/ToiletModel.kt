package com.osm.toiletmap

enum class ToiletStatus {
    OPEN,
    CLOSED,
    UNKNOWN
}

enum class TransportMode(val id: String, val displayName: String) {
    WALK("foot", "Walk"),
    BIKE("bike", "Bike"),
    CAR("car", "Drive")
}

data class Toilet(
    val id: Long,
    val lat: Double,
    val lon: Double,
    val fee: String? = null,
    val charge: String? = null,
    val openingHours: String? = null,
    val name: String? = null
) {
    val feeDisplay: String
        get() = if (fee.isNullOrBlank()) "unknown" else fee

    val chargeDisplay: String
        get() = if (charge.isNullOrBlank()) "unknown" else charge

    val openingHoursDisplay: String
        get() = if (openingHours.isNullOrBlank()) "unknown" else openingHours
}

data class RouteResult(
    val coordinates: List<Pair<Double, Double>>, // List of (lat, lon)
    val distanceMeters: Double,
    val durationSeconds: Double,
    val mode: TransportMode
)

data class SearchResult(
    val name: String,
    val subtitle: String,
    val lat: Double,
    val lon: Double
)
