package com.osm.toiletmap

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.*

class OsmRoutingService {

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    suspend fun calculateRoute(
        startLat: Double,
        startLon: Double,
        destLat: Double,
        destLon: Double,
        mode: TransportMode
    ): RouteResult? = withContext(Dispatchers.IO) {
        val urls = when (mode) {
            TransportMode.WALK -> listOf(
                "https://routing.openstreetmap.de/routed-foot/route/v1/driving/%f,%f;%f,%f?overview=full&geometries=geojson",
                "https://router.project-osrm.org/route/v1/foot/%f,%f;%f,%f?overview=full&geometries=geojson"
            )
            TransportMode.BIKE -> listOf(
                "https://routing.openstreetmap.de/routed-bike/route/v1/driving/%f,%f;%f,%f?overview=full&geometries=geojson",
                "https://router.project-osrm.org/route/v1/bicycle/%f,%f;%f,%f?overview=full&geometries=geojson"
            )
            TransportMode.CAR -> listOf(
                "https://router.project-osrm.org/route/v1/driving/%f,%f;%f,%f?overview=full&geometries=geojson",
                "https://routing.openstreetmap.de/routed-car/route/v1/driving/%f,%f;%f,%f?overview=full&geometries=geojson"
            )
        }

        for (urlTemplate in urls) {
            try {
                val url = String.format(Locale.US, urlTemplate, startLon, startLat, destLon, destLat)
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Android; OSM Toilet Map)")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: return@use
                        val root = gson.fromJson(body, JsonObject::class.java)
                        val code = root.get("code")?.asString
                        if (code == "Ok") {
                            val routes = root.getAsJsonArray("routes")
                            if (routes != null && routes.size() > 0) {
                                val route = routes[0].asJsonObject
                                val distance = route.get("distance").asDouble
                                val duration = route.get("duration").asDouble

                                val geometry = route.getAsJsonObject("geometry")
                                val coordinatesArray = geometry.getAsJsonArray("coordinates")
                                val points = mutableListOf<Pair<Double, Double>>()
                                for (j in 0 until coordinatesArray.size()) {
                                    val pt = coordinatesArray[j].asJsonArray
                                    val lon = pt[0].asDouble
                                    val lat = pt[1].asDouble
                                    points.add(Pair(lat, lon))
                                }
                                return@withContext RouteResult(points, distance, duration, mode)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // Try next endpoint
            }
        }

        // Offline / Geodesic fallback calculation
        val distMeters = calculateHaversineDistance(startLat, startLon, destLat, destLon)
        val speedMps = when (mode) {
            TransportMode.WALK -> 1.38 // ~5 km/h
            TransportMode.BIKE -> 4.16 // ~15 km/h
            TransportMode.CAR -> 11.11 // ~40 km/h
        }
        val estimatedDuration = distMeters / speedMps
        val directLine = listOf(Pair(startLat, startLon), Pair(destLat, destLon))
        return@withContext RouteResult(directLine, distMeters, estimatedDuration, mode)
    }

    private fun calculateHaversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0 // meters
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val deltaPhi = Math.toRadians(lat2 - lat1)
        val deltaLambda = Math.toRadians(lon2 - lon1)

        val a = sin(deltaPhi / 2.0).pow(2) +
                cos(phi1) * cos(phi2) * sin(deltaLambda / 2.0).pow(2)
        val c = 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
        return r * c
    }
}
