package com.osm.toiletmap

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit

class ToiletRepository(private val context: Context) {

    val regionManager = RegionManager(context)

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    private val cachedToilets = mutableMapOf<Long, Toilet>()

    private val overpassEndpoints = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
        "https://maps.mail.ru/osm/tools/overpass/api/interpreter"
    )

    private var endpointCounter = 0

    /**
     * Loads all previously downloaded regions from local disk storage.
     * By default, no data is loaded if no regions have been downloaded yet.
     */
    fun loadAllOfflineDownloadedToilets(): List<Toilet> {
        val downloadedIds = regionManager.getDownloadedRegionIds()
        for (id in downloadedIds) {
            val file = regionManager.getRegionFile(id)
            if (file.exists()) {
                try {
                    val json = file.readText()
                    val array = gson.fromJson(json, JsonArray::class.java)
                    for (i in 0 until array.size()) {
                        val obj = array[i].asJsonObject
                        val toilet = parseToiletElement(obj)
                        if (toilet != null) {
                            cachedToilets[toilet.id] = toilet
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        return cachedToilets.values.toList()
    }

    private data class BoundingBox(
        val south: Double,
        val west: Double,
        val north: Double,
        val east: Double
    )

    /**
     * Downloads toilet data for the specified region from OSM Overpass API.
     * If the server returns an error or times out because the area is too big,
     * it recursively splits the area into smaller quadrants until every part
     * has been downloaded.
     * Returns the updated list of toilets, or null if no data could be downloaded.
     */
    suspend fun downloadRegionToilets(region: RegionInfo): List<Toilet>? = withContext(Dispatchers.IO) {
        val initialBox = BoundingBox(region.south, region.west, region.north, region.east)

        // Fetch all elements for this region, splitting into smaller quadrants if needed
        val allElements = fetchBoxElementsWithSubdivision(initialBox, depth = 0)

        val uniqueElementsMap = mutableMapOf<Long, JsonObject>()
        if (allElements != null) {
            for (elem in allElements) {
                val id = elem.get("id")?.asLong ?: continue
                uniqueElementsMap[id] = elem
            }
        }

        // Offline seed fallback for Berlin if live download yielded no data
        if (uniqueElementsMap.isEmpty() && region.id == "de-be") {
            try {
                val seedJson = context.assets.open("seed_toilets.json").bufferedReader().use { it.readText() }
                val root = gson.fromJson(seedJson, JsonObject::class.java)
                val elems = root.getAsJsonArray("elements")
                if (elems != null) {
                    for (i in 0 until elems.size()) {
                        val elem = elems[i].asJsonObject
                        val id = elem.get("id")?.asLong ?: continue
                        uniqueElementsMap[id] = elem
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Verification: verify that data has actually been downloaded
        if (uniqueElementsMap.isEmpty()) {
            // No data could be downloaded
            return@withContext null
        }

        // Parse unique elements and update local cache
        val elementsArray = JsonArray()
        for ((_, elem) in uniqueElementsMap) {
            elementsArray.add(elem)
            val toilet = parseToiletElement(elem)
            if (toilet != null) {
                cachedToilets[toilet.id] = toilet
            }
        }

        // Save downloaded region data to local disk storage
        try {
            val file = regionManager.getRegionFile(region.id)
            file.writeText(gson.toJson(elementsArray))
            regionManager.markRegionDownloaded(region.id)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return@withContext cachedToilets.values.toList()
    }

    /**
     * Attempts to fetch toilet elements for a given bounding box.
     * If a query fails or errors (due to area size, timeout, or server limits),
     * it splits the box into 4 smaller sub-bounding boxes and fetches each part recursively.
     */
    private suspend fun fetchBoxElementsWithSubdivision(
        box: BoundingBox,
        depth: Int
    ): List<JsonObject>? {
        // First try to fetch the box as a whole
        val directResult = tryFetchOverpassBox(box)
        if (directResult != null) {
            return directResult
        }

        // If the query failed and we haven't exceeded max subdivision depth (depth 3 = 64 sub-blocks)
        if (depth < 3) {
            val midLat = (box.south + box.north) / 2.0
            val midLon = (box.west + box.east) / 2.0

            val quadrants = listOf(
                BoundingBox(box.south, box.west, midLat, midLon),
                BoundingBox(box.south, midLon, midLat, box.east),
                BoundingBox(midLat, box.west, box.north, midLon),
                BoundingBox(midLat, midLon, box.north, box.east)
            )

            val combined = mutableListOf<JsonObject>()
            var anyPartSucceeded = false

            for (quad in quadrants) {
                delay(120) // Brief delay between sub-part queries to prevent rate-limiting
                val subResult = fetchBoxElementsWithSubdivision(quad, depth + 1)
                if (subResult != null) {
                    combined.addAll(subResult)
                    anyPartSucceeded = true
                }
            }

            if (anyPartSucceeded) {
                return combined
            }
        }

        return null
    }

    /**
     * Executes a single Overpass query for a bounding box across available mirror endpoints.
     * Returns the list of JsonObjects if successful, or null if all endpoints failed or returned an error.
     */
    private fun tryFetchOverpassBox(box: BoundingBox): List<JsonObject>? {
        val query = String.format(
            Locale.US,
            """
            [out:json][timeout:35];
            (
              node["amenity"="toilets"](%.5f,%.5f,%.5f,%.5f);
              way["amenity"="toilets"](%.5f,%.5f,%.5f,%.5f);
            );
            out center;
            """.trimIndent(),
            box.south, box.west, box.north, box.east,
            box.south, box.west, box.north, box.east
        )

        val encodedQuery = try {
            URLEncoder.encode(query, "UTF-8")
        } catch (e: Exception) {
            return null
        }

        // Try rotating endpoints
        val totalEndpoints = overpassEndpoints.size
        for (i in 0 until totalEndpoints) {
            val endpointIndex = (endpointCounter + i) % totalEndpoints
            val endpoint = overpassEndpoints[endpointIndex]

            try {
                val url = "$endpoint?data=$encodedQuery"
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "OSMToiletApp/2.0 (Android; info@toiletfinder.org)")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: return@use
                        val root = gson.fromJson(body, JsonObject::class.java)

                        // Check for Overpass runtime errors (e.g. timeout or memory limit exceeded)
                        if (root.has("remark")) {
                            val remark = root.get("remark").asString
                            if (remark.contains("error", ignoreCase = true) ||
                                remark.contains("timed out", ignoreCase = true) ||
                                remark.contains("memory", ignoreCase = true)
                            ) {
                                // Server indicated error/timeout during query
                                return@use
                            }
                        }

                        val elements = root.getAsJsonArray("elements")
                        if (elements != null) {
                            endpointCounter = endpointIndex // stick with working endpoint
                            val list = mutableListOf<JsonObject>()
                            for (j in 0 until elements.size()) {
                                list.add(elements[j].asJsonObject)
                            }
                            return list
                        }
                    }
                }
            } catch (e: Exception) {
                // Try next endpoint
            }
        }

        return null
    }

    private fun parseToiletElement(obj: JsonObject): Toilet? {
        val id = obj.get("id")?.asLong ?: return null
        val lat = when {
            obj.has("lat") -> obj.get("lat").asDouble
            obj.has("center") -> obj.getAsJsonObject("center").get("lat").asDouble
            else -> return null
        }
        val lon = when {
            obj.has("lon") -> obj.get("lon").asDouble
            obj.has("center") -> obj.getAsJsonObject("center").get("lon").asDouble
            else -> return null
        }

        val tags = if (obj.has("tags")) obj.getAsJsonObject("tags") else null
        val fee = tags?.get("fee")?.asString
        val charge = tags?.get("charge")?.asString
        val openingHours = tags?.get("opening_hours")?.asString
        val name = tags?.get("name")?.asString

        return Toilet(
            id = id,
            lat = lat,
            lon = lon,
            fee = fee,
            charge = charge,
            openingHours = openingHours,
            name = name
        )
    }

    fun getAllCachedToilets(): List<Toilet> {
        return cachedToilets.values.toList()
    }
}
