package com.osm.toiletmap

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

data class RegionInfo(
    val id: String,
    val name: String,
    val country: String,
    val south: Double,
    val west: Double,
    val north: Double,
    val east: Double
) {
    fun contains(lat: Double, lon: Double): Boolean {
        return lat in south..north && lon in west..east
    }

    val area: Double
        get() = (north - south) * (east - west)
}

class RegionManager(private val context: Context) {

    private val prefs = context.getSharedPreferences("downloaded_regions_prefs", Context.MODE_PRIVATE)
    private val KEY_DOWNLOADED = "downloaded_region_ids"
    private val KEY_CACHE_PREFIX = "cached_region_"

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val reverseCache = mutableMapOf<String, RegionInfo>()

    init {
        // Load persistent cache from SharedPreferences
        try {
            val allEntries = prefs.all
            for ((k, v) in allEntries) {
                if (k.startsWith(KEY_CACHE_PREFIX) && v is String) {
                    val region = gson.fromJson(v, RegionInfo::class.java)
                    if (region != null) {
                        val key = k.removePrefix(KEY_CACHE_PREFIX)
                        reverseCache[key] = region
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Pre-configured first-level sub-national divisions and city-states.
    // Smaller / enclaved regions are placed first so containment/overlap prefers the specific region.
    // Real full names are used (no abbreviations), and English names are added in parentheses if different.
    val PRECONFIGURED_REGIONS: List<RegionInfo> = listOf(
        // --- German City-States & Small States ---
        RegionInfo("de-be", "Berlin", "Germany", 52.338, 13.088, 52.675, 13.761),
        RegionInfo("de-hb", "Bremen", "Germany", 53.010, 8.480, 53.570, 8.920),
        RegionInfo("de-hh", "Hamburg", "Germany", 53.387, 9.690, 53.740, 10.325),
        RegionInfo("de-sl", "Saarland", "Germany", 49.112, 6.358, 49.638, 7.410),

        // --- German Federal States ---
        RegionInfo("de-bb", "Brandenburg", "Germany", 51.359, 11.268, 53.559, 14.766),
        RegionInfo("de-bw", "Baden-Württemberg (Baden-Wuerttemberg)", "Germany", 47.532, 7.512, 49.791, 10.496),
        RegionInfo("de-by", "Bayern (Bavaria)", "Germany", 47.270, 8.976, 50.565, 13.840),
        RegionInfo("de-he", "Hessen (Hesse)", "Germany", 49.395, 7.773, 51.655, 10.237),
        RegionInfo("de-mv", "Mecklenburg-Vorpommern (Mecklenburg-Western Pomerania)", "Germany", 53.072, 10.590, 54.686, 14.417),
        RegionInfo("de-ni", "Niedersachsen (Lower Saxony)", "Germany", 51.295, 6.650, 53.890, 11.598),
        RegionInfo("de-nw", "Nordrhein-Westfalen (North Rhine-Westphalia)", "Germany", 50.323, 5.866, 52.532, 9.462),
        RegionInfo("de-rp", "Rheinland-Pfalz (Rhineland-Palatinate)", "Germany", 48.966, 6.107, 50.942, 8.508),
        RegionInfo("de-sn", "Sachsen (Saxony)", "Germany", 50.171, 11.872, 51.685, 15.042),
        RegionInfo("de-st", "Sachsen-Anhalt (Saxony-Anhalt)", "Germany", 50.938, 10.562, 53.040, 13.310),
        RegionInfo("de-sh", "Schleswig-Holstein", "Germany", 53.360, 7.865, 55.058, 11.316),
        RegionInfo("de-th", "Thüringen (Thuringia)", "Germany", 50.204, 9.878, 51.649, 12.653),

        // --- City-States & Microstates (Whole Country offered) ---
        RegionInfo("country_lu", "Lëtzebuerg (Luxembourg)", "Luxembourg", 49.447, 5.734, 50.183, 6.531),
        RegionInfo("country_mc", "Monaco", "Monaco", 43.725, 7.409, 43.752, 7.440),
        RegionInfo("country_li", "Liechtenstein", "Liechtenstein", 47.047, 9.471, 47.271, 9.648),
        RegionInfo("country_ad", "Andorra", "Andorra", 42.428, 1.413, 42.656, 1.786),
        RegionInfo("country_sm", "San Marino", "San Marino", 43.893, 12.403, 43.992, 12.518),
        RegionInfo("country_va", "Città del Vaticano (Vatican City)", "Vatican City", 41.900, 12.445, 41.907, 12.458),
        RegionInfo("country_mt", "Malta", "Malta", 35.790, 14.180, 36.080, 14.580),
        RegionInfo("country_sg", "Singapore", "Singapore", 1.150, 103.600, 1.480, 104.050),
        RegionInfo("country_bh", "Bahrain", "Bahrain", 25.550, 50.300, 26.350, 50.850),
        RegionInfo("country_cy", "Κύπρος (Cyprus)", "Cyprus", 34.550, 32.250, 35.720, 34.650),
        RegionInfo("country_gi", "Gibraltar", "Gibraltar", 36.105, -5.370, 36.166, -5.335),

        // --- Austria (Bundesländer) ---
        RegionInfo("at-w", "Wien (Vienna)", "Austria", 48.118, 16.182, 48.323, 16.577),
        RegionInfo("at-noe", "Niederösterreich (Lower Austria)", "Austria", 47.420, 14.450, 49.030, 17.070),
        RegionInfo("at-ooe", "Oberösterreich (Upper Austria)", "Austria", 47.460, 12.750, 48.780, 15.000),
        RegionInfo("at-sbg", "Salzburg", "Austria", 46.980, 12.080, 48.010, 13.780),
        RegionInfo("at-tir", "Tirol (Tyrol)", "Austria", 46.650, 10.090, 47.740, 12.970),
        RegionInfo("at-vbg", "Vorarlberg", "Austria", 46.850, 9.530, 47.590, 10.230),
        RegionInfo("at-ktn", "Kärnten (Carinthia)", "Austria", 46.360, 12.650, 47.130, 15.060),
        RegionInfo("at-stmk", "Steiermark (Styria)", "Austria", 46.610, 13.560, 47.830, 16.150),
        RegionInfo("at-bgld", "Burgenland", "Austria", 46.830, 16.050, 48.120, 17.170),

        // --- Switzerland (Cantons) ---
        RegionInfo("ch-zh", "Zürich (Zurich)", "Switzerland", 47.160, 8.350, 47.700, 8.900),
        RegionInfo("ch-be", "Bern", "Switzerland", 46.330, 7.000, 47.300, 8.400),
        RegionInfo("ch-ge", "Genève (Geneva)", "Switzerland", 46.120, 5.950, 46.360, 6.320),
        RegionInfo("ch-bs", "Basel-Stadt (Basel City)", "Switzerland", 47.520, 7.550, 47.600, 7.700),
        RegionInfo("ch-vd", "Vaud", "Switzerland", 46.150, 6.080, 46.990, 7.200),
        RegionInfo("ch-lu", "Luzern (Lucerne)", "Switzerland", 46.790, 7.850, 47.310, 8.550),
        RegionInfo("ch-vs", "Valais (Wallis)", "Switzerland", 45.900, 6.780, 46.620, 8.480),
        RegionInfo("ch-ti", "Ticino (Tessin)", "Switzerland", 45.780, 8.390, 46.620, 9.150),
        RegionInfo("ch-sg", "St. Gallen", "Switzerland", 46.880, 8.780, 47.580, 9.680),
        RegionInfo("country_ch", "Schweiz (Switzerland)", "Switzerland", 45.818, 5.956, 47.808, 10.492),

        // --- Netherlands (Provinces) ---
        RegionInfo("nl-nh", "Noord-Holland (North Holland)", "Netherlands", 52.150, 4.500, 53.200, 5.400),
        RegionInfo("nl-zh", "Zuid-Holland (South Holland)", "Netherlands", 51.700, 3.900, 52.350, 5.150),
        RegionInfo("nl-ut", "Utrecht", "Netherlands", 51.900, 4.800, 52.300, 5.600),
        RegionInfo("nl-nb", "Noord-Brabant (North Brabant)", "Netherlands", 51.200, 4.200, 51.900, 6.050),
        RegionInfo("nl-ge", "Gelderland", "Netherlands", 51.750, 4.980, 52.550, 6.900),
        RegionInfo("nl-li", "Limburg", "Netherlands", 50.750, 5.560, 51.780, 6.250),
        RegionInfo("nl-ov", "Overijssel", "Netherlands", 52.100, 5.900, 52.880, 7.100),
        RegionInfo("nl-gr", "Groningen", "Netherlands", 52.900, 6.180, 53.550, 7.250),
        RegionInfo("nl-fr", "Fryslân (Friesland)", "Netherlands", 52.800, 5.100, 53.500, 6.350),

        // --- Belgium (Regions) ---
        RegionInfo("be-bru", "Brussels (Bruxelles)", "Belgium", 50.790, 4.240, 50.920, 4.490),
        RegionInfo("be-vlg", "Flanders (Vlaanderen)", "Belgium", 50.680, 2.540, 51.510, 5.910),
        RegionInfo("be-wal", "Wallonia (Wallonie)", "Belgium", 49.490, 2.840, 50.810, 6.410),

        // --- France (Metropolitan Regions) ---
        RegionInfo("fr-idf", "Île-de-France", "France", 48.120, 1.440, 49.250, 3.560),
        RegionInfo("fr-ara", "Auvergne-Rhône-Alpes", "France", 44.110, 2.060, 46.800, 7.190),
        RegionInfo("fr-paca", "Provence-Alpes-Côte d'Azur", "France", 42.980, 4.230, 45.300, 7.730),
        RegionInfo("fr-naq", "Nouvelle-Aquitaine (New Aquitaine)", "France", 42.770, -1.790, 47.180, 2.610),
        RegionInfo("fr-occ", "Occitanie (Occitania)", "France", 42.330, -0.330, 45.050, 4.850),
        RegionInfo("fr-ges", "Grand Est", "France", 47.700, 3.390, 50.180, 8.240),
        RegionInfo("fr-hdf", "Hauts-de-France (Upper France)", "France", 49.000, 1.620, 51.100, 4.250),
        RegionInfo("fr-bre", "Bretagne (Brittany)", "France", 47.280, -4.800, 48.900, -1.020),
        RegionInfo("fr-nor", "Normandie (Normandy)", "France", 48.330, -1.980, 50.080, 1.800),
        RegionInfo("fr-bfc", "Bourgogne-Franche-Comté (Burgundy-Franche-Comté)", "France", 46.150, 2.800, 48.400, 7.100),
        RegionInfo("fr-cvl", "Centre-Val de Loire", "France", 46.350, 0.050, 48.950, 3.150),
        RegionInfo("fr-pdl", "Pays de la Loire", "France", 46.250, -2.600, 48.600, 0.950),
        RegionInfo("fr-cor", "Corse (Corsica)", "France", 41.330, 8.550, 43.030, 9.580),

        // --- United Kingdom ---
        RegionInfo("gb-eng", "England", "United Kingdom", 49.950, -5.720, 55.810, 1.770),
        RegionInfo("gb-sct", "Scotland", "United Kingdom", 54.630, -7.660, 60.850, -0.720),
        RegionInfo("gb-wls", "Wales", "United Kingdom", 51.370, -5.350, 53.440, -2.650),
        RegionInfo("gb-nir", "Northern Ireland", "United Kingdom", 54.020, -8.180, 55.320, -5.430),

        // --- Spain (Autonomous Communities) ---
        RegionInfo("es-md", "Comunidad de Madrid (Community of Madrid)", "Spain", 39.880, -4.580, 41.170, -3.050),
        RegionInfo("es-ct", "Catalunya (Catalonia)", "Spain", 40.520, 0.150, 42.860, 3.330),
        RegionInfo("es-an", "Andalucía (Andalusia)", "Spain", 35.990, -7.530, 38.730, -1.630),
        RegionInfo("es-vc", "Comunitat Valenciana (Valencian Community)", "Spain", 37.850, -1.530, 40.790, 0.690),
        RegionInfo("es-ga", "Galicia", "Spain", 41.800, -9.300, 43.790, -6.730),
        RegionInfo("es-pv", "País Vasco (Basque Country)", "Spain", 42.460, -3.450, 43.460, -1.740),
        RegionInfo("es-cl", "Castilla y León (Castile and León)", "Spain", 40.080, -7.050, 43.240, -1.760),
        RegionInfo("es-cm", "Castilla-La Mancha (Castile-La Mancha)", "Spain", 38.020, -5.410, 41.330, -1.030),
        RegionInfo("es-ar", "Aragón (Aragon)", "Spain", 39.850, -2.170, 42.930, 0.770),
        RegionInfo("es-ib", "Illes Balears (Balearic Islands)", "Spain", 38.640, 1.150, 40.100, 4.350),
        RegionInfo("es-cn", "Canarias (Canary Islands)", "Spain", 27.630, -18.170, 29.420, -13.330),

        // --- Italy (Regions) ---
        RegionInfo("it-lom", "Lombardia (Lombardy)", "Italy", 44.680, 8.490, 46.640, 11.420),
        RegionInfo("it-laz", "Lazio", "Italy", 41.200, 11.450, 42.840, 14.030),
        RegionInfo("it-ven", "Veneto", "Italy", 44.790, 10.610, 46.680, 13.110),
        RegionInfo("it-pie", "Piemonte (Piedmont)", "Italy", 44.060, 6.620, 46.460, 9.210),
        RegionInfo("it-emr", "Emilia-Romagna", "Italy", 43.730, 9.220, 45.140, 12.760),
        RegionInfo("it-cam", "Campania", "Italy", 39.990, 13.750, 41.510, 15.800),
        RegionInfo("it-sic", "Sicilia (Sicily)", "Italy", 36.640, 11.950, 38.310, 15.650),
        RegionInfo("it-tos", "Toscana (Tuscany)", "Italy", 42.240, 9.680, 44.470, 12.370),
        RegionInfo("it-pug", "Puglia (Apulia)", "Italy", 39.790, 14.920, 41.900, 18.520),
        RegionInfo("it-sar", "Sardegna (Sardinia)", "Italy", 38.860, 8.130, 41.260, 9.830),
        RegionInfo("it-lig", "Liguria", "Italy", 43.780, 7.500, 44.660, 10.050),

        // --- Japan (Prefectures, Kanji with English in parentheses) ---
        RegionInfo("jp-13", "東京都 (Tokyo)", "Japan", 35.500, 138.900, 35.900, 139.950),
        RegionInfo("jp-27", "大阪府 (Osaka)", "Japan", 34.250, 135.050, 35.050, 135.750),
        RegionInfo("jp-26", "京都府 (Kyoto)", "Japan", 34.780, 134.850, 35.780, 136.050),
        RegionInfo("jp-01", "北海道 (Hokkaido)", "Japan", 41.350, 139.300, 45.550, 145.850),
        RegionInfo("jp-14", "神奈川県 (Kanagawa)", "Japan", 35.120, 138.900, 35.680, 139.780),
        RegionInfo("jp-23", "愛知県 (Aichi)", "Japan", 34.570, 136.670, 35.420, 137.840),
        RegionInfo("jp-40", "福岡県 (Fukuoka)", "Japan", 33.050, 129.980, 33.950, 131.050),
        RegionInfo("jp-34", "広島県 (Hiroshima)", "Japan", 34.030, 132.030, 35.110, 133.470),
        RegionInfo("jp-47", "沖縄県 (Okinawa)", "Japan", 24.050, 122.900, 27.100, 128.350),

        // --- United States (Full state names, no abbreviations) ---
        RegionInfo("us-ca", "California", "United States", 32.530, -124.410, 42.010, -114.130),
        RegionInfo("us-ny", "New York", "United States", 40.490, -79.760, 45.020, -71.850),
        RegionInfo("us-tx", "Texas", "United States", 25.830, -106.650, 36.500, -93.500),
        RegionInfo("us-fl", "Florida", "United States", 24.520, -87.630, 31.000, -80.030),
        RegionInfo("us-wa", "Washington", "United States", 45.540, -124.850, 49.000, -116.910),
        RegionInfo("us-il", "Illinois", "United States", 36.970, -91.510, 42.510, -87.020),
        RegionInfo("us-pa", "Pennsylvania", "United States", 39.720, -80.520, 42.270, -74.690),
        RegionInfo("us-oh", "Ohio", "United States", 38.400, -84.820, 42.000, -80.510),
        RegionInfo("us-ga", "Georgia", "United States", 30.350, -85.600, 35.000, -80.840),
        RegionInfo("us-nc", "North Carolina", "United States", 33.840, -84.320, 36.590, -75.460),
        RegionInfo("us-mi", "Michigan", "United States", 41.690, -90.420, 48.300, -82.410),
        RegionInfo("us-nj", "New Jersey", "United States", 38.920, -75.560, 41.360, -73.890),
        RegionInfo("us-va", "Virginia", "United States", 36.540, -83.670, 39.470, -75.240),
        RegionInfo("us-az", "Arizona", "United States", 31.330, -114.820, 37.000, -109.040),
        RegionInfo("us-ma", "Massachusetts", "United States", 41.230, -73.510, 42.890, -69.920),
        RegionInfo("us-co", "Colorado", "United States", 36.990, -109.050, 41.010, -102.040),

        // --- Canada ---
        RegionInfo("ca-on", "Ontario", "Canada", 41.670, -95.160, 56.860, -74.340),
        RegionInfo("ca-qc", "Québec (Quebec)", "Canada", 44.990, -79.770, 62.580, -57.100),
        RegionInfo("ca-bc", "British Columbia", "Canada", 48.300, -139.060, 60.000, -114.050),
        RegionInfo("ca-ab", "Alberta", "Canada", 48.990, -120.000, 60.000, -110.000),

        // --- Australia ---
        RegionInfo("au-nsw", "New South Wales", "Australia", -37.510, 140.990, -28.160, 153.640),
        RegionInfo("au-vic", "Victoria", "Australia", -39.160, 140.960, -33.980, 149.980),
        RegionInfo("au-qld", "Queensland", "Australia", -29.180, 137.990, -10.680, 153.550),
        RegionInfo("au-wa", "Western Australia", "Australia", -35.200, 112.920, -13.680, 129.000)
    )

    fun isRegionDownloaded(regionId: String): Boolean {
        val set = prefs.getStringSet(KEY_DOWNLOADED, emptySet()) ?: emptySet()
        return set.contains(regionId)
    }

    fun markRegionDownloaded(regionId: String) {
        val set = prefs.getStringSet(KEY_DOWNLOADED, emptySet())?.toMutableSet() ?: mutableSetOf()
        set.add(regionId)
        prefs.edit().putStringSet(KEY_DOWNLOADED, set).apply()
    }

    fun getDownloadedRegionIds(): Set<String> {
        return prefs.getStringSet(KEY_DOWNLOADED, emptySet()) ?: emptySet()
    }

    /**
     * Finds which region to offer download for according to user criteria:
     * 1. If any highest-level administrative division covers >= 75% of the screen area, return THAT exact region.
     * 2. Otherwise, if screen width < 25 km, resolve region for the screen center point.
     * 3. Otherwise, returns null.
     */
    suspend fun resolveRegionForDownload(
        centerLat: Double,
        centerLon: Double,
        screenSouth: Double,
        screenWest: Double,
        screenNorth: Double,
        screenEast: Double,
        screenWidthMeters: Double
    ): RegionInfo? = withContext(Dispatchers.IO) {
        val screenArea = (screenNorth - screenSouth) * (screenEast - screenWest)
        if (screenArea <= 0.0) return@withContext null

        // 1. Actively resolve the administrative division for the screen center
        val centerRegion = getRegionForLocation(centerLat, centerLon)

        fun calculateCoverage(region: RegionInfo): Double {
            val intSouth = max(screenSouth, region.south)
            val intNorth = min(screenNorth, region.north)
            val intWest = max(screenWest, region.west)
            val intEast = min(screenEast, region.east)
            return if (intNorth > intSouth && intEast > intWest) {
                ((intNorth - intSouth) * (intEast - intWest)) / screenArea
            } else 0.0
        }

        // Criterion 1: Does the region at the center of the screen cover >= 75% of the screen?
        val centerCoverage = calculateCoverage(centerRegion)
        if (centerCoverage >= 0.75) {
            return@withContext centerRegion
        }

        // Check if any other known region covers >= 75% of the screen (e.g. if center falls in an enclave or lake)
        val allCandidates = mutableListOf<RegionInfo>()
        allCandidates.addAll(PRECONFIGURED_REGIONS)
        allCandidates.addAll(reverseCache.values)

        val qualifying = allCandidates.mapNotNull { region ->
            val cov = calculateCoverage(region)
            if (cov >= 0.75) Pair(region, cov) else null
        }

        if (qualifying.isNotEmpty()) {
            val best = qualifying.maxByOrNull { it.second }?.first
            if (best != null) {
                return@withContext best
            }
        }

        // Criterion 2: If screen width is less than 25 km, offer the region at the center
        if (screenWidthMeters < 25000.0) {
            return@withContext centerRegion
        }

        return@withContext null
    }

    /**
     * Resolves the highest-level sub-national division (federal state, province, prefecture, region)
     * or the country itself if it is a city-state or has no subdivisions.
     * Guarantees:
     * - Actual full names with no abbreviations.
     * - English name in braces behind local name if they differ: "Local (English)".
     * - Never returns raw coordinates.
     */
    suspend fun getRegionForLocation(lat: Double, lon: Double): RegionInfo = withContext(Dispatchers.IO) {
        // 1. Check pre-configured regions (small city-states and specific regions first)
        for (region in PRECONFIGURED_REGIONS) {
            if (region.contains(lat, lon)) {
                return@withContext region
            }
        }

        // 2. Check in-memory and persistent reverse cache (coarse 0.25° grid key)
        val cacheKey = String.format(Locale.US, "%.1f,%.1f", lat, lon)
        reverseCache[cacheKey]?.let { return@withContext it }

        // 3. Online resolution using Nominatim at zoom=5 with namedetails=1 for dual-language names
        try {
            val nominatimUrl = String.format(
                Locale.US,
                "https://nominatim.openstreetmap.org/reverse?lat=%f&lon=%f&format=json&zoom=5&addressdetails=1&namedetails=1",
                lat, lon
            )
            val req = Request.Builder()
                .url(nominatimUrl)
                .header("User-Agent", "OSMToiletFinderApp/2.0 (contact: info@toiletfinder.org; android)")
                .build()

            client.newCall(req).execute().use { res ->
                if (res.isSuccessful) {
                    val body = res.body?.string().orEmpty()
                    val obj = gson.fromJson(body, JsonObject::class.java)
                    val address = obj.getAsJsonObject("address")
                    val namedetails = obj.getAsJsonObject("namedetails")

                    // Extract local name
                    val rawLocal = obj.get("name")?.asString
                        ?: address?.get("state")?.asString
                        ?: address?.get("province")?.asString
                        ?: address?.get("region")?.asString
                        ?: address?.get("prefecture")?.asString
                        ?: address?.get("canton")?.asString
                        ?: address?.get("state_district")?.asString
                        ?: address?.get("county")?.asString
                        ?: address?.get("country")?.asString
                        ?: ""

                    // Extract English name
                    val rawEnglish = namedetails?.get("name:en")?.asString
                        ?: namedetails?.get("name:en-US")?.asString
                        ?: namedetails?.get("name:en-GB")?.asString
                        ?: namedetails?.get("int_name")?.asString

                    val countryName = address?.get("country")?.asString.orEmpty()
                    val countryCode = address?.get("country_code")?.asString.orEmpty()

                    val cleanLocal = expandAbbreviation(rawLocal.ifBlank { countryName })
                    val cleanEnglish = rawEnglish?.let { expandAbbreviation(it) }
                    val finalName = formatDualLanguageName(cleanLocal, cleanEnglish)

                    // Nominatim boundingbox format: [south_lat, north_lat, west_lon, east_lon]
                    val bboxArr = obj.getAsJsonArray("boundingbox")
                    if (bboxArr != null && bboxArr.size() == 4 && finalName.isNotBlank()) {
                        val s = bboxArr[0].asDouble
                        val n = bboxArr[1].asDouble
                        val w = bboxArr[2].asDouble
                        val e = bboxArr[3].asDouble

                        val sanitizedId = (countryCode.ifEmpty { "reg" } + "_" + cleanLocal)
                            .lowercase(Locale.ROOT)
                            .replace("[^a-z0-9]".toRegex(), "_")

                        val region = RegionInfo(
                            id = sanitizedId,
                            name = finalName,
                            country = countryName.ifEmpty { "Unknown" },
                            south = s,
                            west = w,
                            north = n,
                            east = e
                        )
                        persistToCache(cacheKey, region)
                        return@withContext region
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 4. Online resolution using Komoot Photon as secondary fallback
        try {
            val photonUrl = String.format(
                Locale.US,
                "https://photon.komoot.io/reverse?lat=%f&lon=%f",
                lat, lon
            )
            val req = Request.Builder()
                .url(photonUrl)
                .header("User-Agent", "OSMToiletFinderApp/2.0 (Android)")
                .build()

            client.newCall(req).execute().use { res ->
                if (res.isSuccessful) {
                    val body = res.body?.string().orEmpty()
                    val root = gson.fromJson(body, JsonObject::class.java)
                    val features = root.getAsJsonArray("features")
                    if (features != null && features.size() > 0) {
                        val first = features[0].asJsonObject
                        val props = first.getAsJsonObject("properties")
                        val state = props.get("state")?.asString
                            ?: props.get("province")?.asString
                            ?: props.get("region")?.asString
                            ?: props.get("county")?.asString

                        val country = props.get("country")?.asString.orEmpty()
                        val countryCode = props.get("countrycode")?.asString?.lowercase(Locale.ROOT).orEmpty()

                        val rawName = if (!state.isNullOrBlank()) state else if (country.isNotBlank()) country else ""
                        if (rawName.isNotBlank()) {
                            val cleanName = expandAbbreviation(rawName)
                            val finalName = formatDualLanguageName(cleanName, null)

                            val sanitizedId = (countryCode.ifEmpty { "reg" } + "_" + cleanName)
                                .lowercase(Locale.ROOT)
                                .replace("[^a-z0-9]".toRegex(), "_")

                            val region = RegionInfo(
                                id = sanitizedId,
                                name = finalName,
                                country = country.ifEmpty { "Unknown" },
                                south = lat - 0.8,
                                west = lon - 1.0,
                                north = lat + 0.8,
                                east = lon + 1.0
                            )
                            persistToCache(cacheKey, region)
                            return@withContext region
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 5. Offline country/geographic fallback (NEVER coordinates!)
        val countryMatch = resolveOfflineCountryName(lat, lon)
        val fallback = RegionInfo(
            id = "region_${cacheKey.replace(".", "_").replace(",", "_")}",
            name = countryMatch,
            country = countryMatch,
            south = lat - 0.7,
            west = lon - 0.9,
            north = lat + 0.7,
            east = lon + 0.9
        )
        persistToCache(cacheKey, fallback)
        return@withContext fallback
    }

    private fun persistToCache(key: String, region: RegionInfo) {
        reverseCache[key] = region
        try {
            prefs.edit().putString(KEY_CACHE_PREFIX + key, gson.toJson(region)).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Formats names into: "LocalName (EnglishName)" when English differs from local,
     * or just "LocalName" if they are identical or English is missing.
     */
    private fun formatDualLanguageName(localName: String, englishName: String?): String {
        val cleanLocal = localName.trim()
        val cleanEn = englishName?.trim()

        if (cleanEn.isNullOrBlank() || cleanLocal.equals(cleanEn, ignoreCase = true)) {
            return cleanLocal
        }
        // If cleanLocal already has braces, don't duplicate
        if (cleanLocal.contains("(") && cleanLocal.contains(")")) {
            return cleanLocal
        }
        return "$cleanLocal ($cleanEn)"
    }

    /**
     * Expands standard 2-letter or common state/province/nation abbreviations to full official names.
     */
    private fun expandAbbreviation(name: String): String {
        val trimmed = name.trim()
        val abbreviationMap = mapOf(
            // US States
            "AL" to "Alabama", "AK" to "Alaska", "AZ" to "Arizona", "AR" to "Arkansas",
            "CA" to "California", "CO" to "Colorado", "CT" to "Connecticut", "DE" to "Delaware",
            "FL" to "Florida", "GA" to "Georgia", "HI" to "Hawaii", "ID" to "Idaho",
            "IL" to "Illinois", "IN" to "Indiana", "IA" to "Iowa", "KS" to "Kansas",
            "KY" to "Kentucky", "LA" to "Louisiana", "ME" to "Maine", "MD" to "Maryland",
            "MA" to "Massachusetts", "MI" to "Michigan", "MN" to "Minnesota", "MS" to "Mississippi",
            "MO" to "Missouri", "MT" to "Montana", "NE" to "Nebraska", "NV" to "Nevada",
            "NH" to "New Hampshire", "NJ" to "New Jersey", "NM" to "New Mexico", "NY" to "New York",
            "NC" to "North Carolina", "ND" to "North Dakota", "OH" to "Ohio", "OK" to "Oklahoma",
            "OR" to "Oregon", "PA" to "Pennsylvania", "RI" to "Rhode Island", "SC" to "South Carolina",
            "SD" to "South Dakota", "TN" to "Tennessee", "TX" to "Texas", "UT" to "Utah",
            "VT" to "Vermont", "VA" to "Virginia", "WA" to "Washington", "WV" to "West Virginia",
            "WI" to "Wisconsin", "WY" to "Wyoming", "DC" to "District of Columbia",

            // German States
            "BW" to "Baden-Württemberg", "BY" to "Bayern", "BE" to "Berlin", "BB" to "Brandenburg",
            "HB" to "Bremen", "HH" to "Hamburg", "HE" to "Hessen", "MV" to "Mecklenburg-Vorpommern",
            "NI" to "Niedersachsen", "NW" to "Nordrhein-Westfalen", "NRW" to "Nordrhein-Westfalen",
            "RP" to "Rheinland-Pfalz", "SL" to "Saarland", "SN" to "Sachsen", "ST" to "Sachsen-Anhalt",
            "SH" to "Schleswig-Holstein", "TH" to "Thüringen",

            // Canadian Provinces
            "ON" to "Ontario", "QC" to "Quebec", "BC" to "British Columbia", "AB" to "Alberta",
            "MB" to "Manitoba", "SK" to "Saskatchewan", "NS" to "Nova Scotia", "NB" to "New Brunswick",
            "NL" to "Newfoundland and Labrador", "PE" to "Prince Edward Island",

            // Australian States
            "NSW" to "New South Wales", "VIC" to "Victoria", "QLD" to "Queensland",
            "WA" to "Western Australia", "SA" to "South Australia", "TAS" to "Tasmania",
            "ACT" to "Australian Capital Territory", "NT" to "Northern Territory",

            // UK Nations
            "UK" to "United Kingdom", "ENG" to "England", "SCT" to "Scotland",
            "WLS" to "Wales", "NIR" to "Northern Ireland"
        )

        return abbreviationMap[trimmed] ?: trimmed
    }

    /**
     * Offline fallback for geographic location to avoid displaying raw coordinates.
     */
    private fun resolveOfflineCountryName(lat: Double, lon: Double): String {
        return when {
            // Central & Western Europe
            lat in 47.0..55.1 && lon in 5.8..15.1 -> "Deutschland (Germany)"
            lat in 42.0..51.1 && lon in -5.0..9.6 -> "France"
            lat in 36.0..47.1 && lon in 6.6..18.6 -> "Italia (Italy)"
            lat in 36.0..43.8 && lon in -9.3..3.3 -> "España (Spain)"
            lat in 49.0..60.9 && lon in -8.2..1.8 -> "United Kingdom"
            lat in 50.7..53.6 && lon in 3.3..7.3 -> "Nederland (Netherlands)"
            lat in 49.4..51.6 && lon in 2.5..6.5 -> "Belgique (Belgium)"
            lat in 46.3..49.1 && lon in 9.5..17.2 -> "Österreich (Austria)"
            lat in 45.8..47.9 && lon in 5.9..10.5 -> "Schweiz (Switzerland)"
            lat in 36.9..42.2 && lon in -9.6..-6.1 -> "Portugal"
            lat in 49.0..54.9 && lon in 14.1..24.2 -> "Polska (Poland)"
            lat in 48.5..51.1 && lon in 12.0..18.9 -> "Česko (Czech Republic)"
            lat in 54.5..57.8 && lon in 8.0..15.2 -> "Danmark (Denmark)"
            lat in 55.3..69.1 && lon in 11.0..24.2 -> "Sverige (Sweden)"
            lat in 57.9..71.2 && lon in 4.5..31.1 -> "Norge (Norway)"
            lat in 59.7..70.1 && lon in 20.5..31.6 -> "Suomi (Finland)"
            lat in 34.8..41.8 && lon in 19.3..29.7 -> "Ελλάδα (Greece)"
            lat in 45.7..48.3 && lon in 16.1..22.9 -> "Magyarország (Hungary)"
            lat in 51.3..55.5 && lon in -10.7..-5.4 -> "Éire (Ireland)"

            // Americas
            lat in 24.5..49.4 && lon in -125.0..-66.9 -> "United States"
            lat in 41.6..83.1 && lon in -141.0..-52.6 -> "Canada"
            lat in 14.5..32.8 && lon in -118.4..-86.7 -> "México (Mexico)"
            lat in -33.8..5.3 && lon in -73.9..-34.7 -> "Brasil (Brazil)"
            lat in -55.0..-21.7 && lon in -73.6..-53.6 -> "Argentina"

            // Asia & Oceania
            lat in 24.0..45.6 && lon in 122.9..153.9 -> "日本 (Japan)"
            lat in 33.0..38.7 && lon in 124.6..131.0 -> "대한민국 (South Korea)"
            lat in 18.1..53.6 && lon in 73.5..135.1 -> "中国 (China)"
            lat in 8.0..37.1 && lon in 68.1..97.4 -> "India"
            lat in -43.7..-10.6 && lon in 113.1..153.7 -> "Australia"
            lat in -47.3..-34.3 && lon in 166.4..178.6 -> "New Zealand"

            else -> "Local Region"
        }
    }

    fun getRegionFile(regionId: String): File {
        val dir = File(context.filesDir, "regions")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "$regionId.json")
    }
}
