package com.osm.toiletmap

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class GeocodingService {

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    suspend fun search(query: String): List<SearchResult> = withContext(Dispatchers.IO) {
        if (query.trim().length < 2) return@withContext emptyList()

        val results = mutableListOf<SearchResult>()
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")

        // 1. Try Photon (Fast OSM geocoder by Komoot)
        try {
            val url = "https://photon.komoot.io/api/?q=$encoded&limit=6"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Android; OSM Toilet Map)")
                .build()

            client.newCall(req).execute().use { res ->
                if (res.isSuccessful) {
                    val body = res.body?.string() ?: ""
                    val root = gson.fromJson(body, JsonObject::class.java)
                    val features = root.getAsJsonArray("features") ?: JsonArray()

                    for (i in 0 until features.size()) {
                        val feature = features[i].asJsonObject
                        val geom = feature.getAsJsonObject("geometry")
                        val coords = geom.getAsJsonArray("coordinates")
                        val lon = coords[0].asDouble
                        val lat = coords[1].asDouble

                        val props = feature.getAsJsonObject("properties")
                        val name = props.get("name")?.asString ?: props.get("street")?.asString ?: query
                        val city = props.get("city")?.asString ?: props.get("state")?.asString ?: ""
                        val country = props.get("country")?.asString ?: ""

                        val subtitleParts = listOf(city, country).filter { it.isNotBlank() }
                        val subtitle = if (subtitleParts.isNotEmpty()) subtitleParts.joinToString(", ") else "Location"

                        results.add(SearchResult(name, subtitle, lat, lon))
                    }
                    if (results.isNotEmpty()) return@withContext results
                }
            }
        } catch (e: Exception) {
            // Try Nominatim fallback
        }

        // 2. Nominatim Fallback
        try {
            val url = "https://nominatim.openstreetmap.org/search?q=$encoded&format=json&limit=6"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "OSMToiletApp/1.0 (Android)")
                .build()

            client.newCall(req).execute().use { res ->
                if (res.isSuccessful) {
                    val body = res.body?.string() ?: ""
                    val arr = gson.fromJson(body, JsonArray::class.java) ?: JsonArray()
                    for (i in 0 until arr.size()) {
                        val item = arr[i].asJsonObject
                        val lat = item.get("lat").asDouble
                        val lon = item.get("lon").asDouble
                        val displayName = item.get("display_name").asString
                        val parts = displayName.split(", ")
                        val name = parts.firstOrNull() ?: displayName
                        val subtitle = if (parts.size > 1) parts.subList(1, parts.size.coerceAtMost(3)).joinToString(", ") else ""
                        results.add(SearchResult(name, subtitle, lat, lon))
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return@withContext results
    }
}
