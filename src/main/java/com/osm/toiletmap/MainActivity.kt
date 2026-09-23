package com.osm.toiletmap

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Rect
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.osm.toiletmap.databinding.ActivityMainBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import kotlin.math.cos
import kotlin.math.log2
import kotlin.math.min
import kotlin.math.pow

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var mapLibreMap: MapLibreMap? = null

    private lateinit var toiletRepo: ToiletRepository
    private lateinit var routingService: OsmRoutingService
    private lateinit var geocodingService: GeocodingService
    private lateinit var closestToiletFinder: ClosestToiletFinder
    private lateinit var searchAdapter: SearchSuggestionsAdapter

    private var currentMode: TransportMode = TransportMode.WALK
    private var selectedToilet: Toilet? = null
    private var routeTargetToilet: Toilet? = null

    // Track active route parameters for safe mode-switch recalculation without crash
    private var activeRouteStartLat: Double? = null
    private var activeRouteStartLon: Double? = null
    private var activeRouteToilet: Toilet? = null

    private var isSelectingStartPointForRoute = false
    private var isSelectingStartPointForClosest = false

    private val handler = Looper.myLooper()?.let { Handler(it) } ?: Handler(Looper.getMainLooper())
    private var dismissPromptRunnable: Runnable? = null
    private var searchJob: Job? = null
    private var isProgrammaticSearchTextChange = false

    // Region download state
    private var pendingDownloadRegion: RegionInfo? = null
    private var dismissedRegionId: String? = null
    private var dismissedAtZoom: Double? = null

    // Swipe down tracking on toilet overlay
    private var swipeOverlayStartY = 0f
    private var swipeOverlayStartX = 0f
    private var isTrackingOverlaySwipe = false
    private var isSwipeDragging = false
    private var hasDispatchedCancelToOverlay = false
    private var regionCheckJob: kotlinx.coroutines.Job? = null

    private val earthCircumferenceMeters = 40075016.686
    private val defaultLat = 51.1657 // Center of Germany
    private val defaultLon = 10.4515

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        toiletRepo = ToiletRepository(this)
        routingService = OsmRoutingService()
        geocodingService = GeocodingService()
        closestToiletFinder = ClosestToiletFinder(routingService)

        initViews()
        initTouchInterceptor()
        setupBackPressHandling()

        binding.mapView.onCreate(savedInstanceState)
        binding.mapView.getMapAsync { map ->
            mapLibreMap = map
            setupMap(map)
        }
    }

    private fun setupBackPressHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.suggestionsCard.visibility == View.VISIBLE) {
                    binding.suggestionsCard.visibility = View.GONE
                    binding.searchEditText.clearFocus()
                } else if (binding.toiletOverlay.visibility == View.VISIBLE) {
                    hideToiletOverlay()
                } else if (binding.routeSummaryCard.visibility == View.VISIBLE) {
                    clearActiveRoute()
                } else if (binding.regionDownloadCard.visibility == View.VISIBLE) {
                    dismissedRegionId = pendingDownloadRegion?.id
                    dismissedAtZoom = mapLibreMap?.cameraPosition?.zoom ?: 0.0
                    binding.regionDownloadCard.visibility = View.GONE
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })
    }

    private fun initViews() {
        // Search suggestions adapter
        searchAdapter = SearchSuggestionsAdapter { searchResult ->
            onSearchResultSelected(searchResult)
        }
        binding.searchResultsRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.searchResultsRecyclerView.adapter = searchAdapter

        // Search edit text focus listener: hides suggestions when focus is lost
        binding.searchEditText.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                binding.suggestionsCard.visibility = View.GONE
            } else {
                val q = binding.searchEditText.text.toString().trim()
                if (q.length >= 2) {
                    performSearch(q)
                }
            }
        }

        // Search edit text
        binding.searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (isProgrammaticSearchTextChange) return
                val query = s?.toString()?.trim().orEmpty()
                binding.clearSearchBtn.visibility = if (query.isNotEmpty()) View.VISIBLE else View.GONE
                performSearch(query)
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.searchEditText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val query = binding.searchEditText.text.toString().trim()
                performSearch(query, immediate = true)
                hideKeyboard()
                true
            } else false
        }

        binding.clearSearchBtn.setOnClickListener {
            binding.searchEditText.text.clear()
            searchAdapter.clear()
            binding.suggestionsCard.visibility = View.GONE
        }

        binding.gpsBtn.setOnClickListener {
            handleGpsButtonClick()
        }

        // Mode selector buttons
        binding.modeWalkBtn.setOnClickListener { setTransportMode(TransportMode.WALK) }
        binding.modeBikeBtn.setOnClickListener { setTransportMode(TransportMode.BIKE) }
        binding.modeDriveBtn.setOnClickListener { setTransportMode(TransportMode.CAR) }

        // Reset orientation to north button (placed on right side, below search bar)
        binding.btnResetNorth.setOnClickListener {
            mapLibreMap?.animateCamera(CameraUpdateFactory.bearingTo(0.0))
        }

        // Region download card buttons
        binding.btnDownloadRegion.setOnClickListener {
            val region = pendingDownloadRegion ?: return@setOnClickListener
            downloadRegionToilets(region)
        }
        binding.btnDismissRegion.setOnClickListener {
            dismissedRegionId = pendingDownloadRegion?.id
            dismissedAtZoom = mapLibreMap?.cameraPosition?.zoom ?: 0.0
            binding.regionDownloadCard.visibility = View.GONE
        }

        // Route button inside toilet table overlay
        binding.btnFindShortestRoute.setOnClickListener {
            val toilet = selectedToilet ?: return@setOnClickListener
            hideToiletOverlay()
            routeTargetToilet = toilet
            isSelectingStartPointForRoute = true
            isSelectingStartPointForClosest = false
            showRouteGuideBanner(getString(R.string.prompt_select_start))
        }

        // Close button inside toilet table overlay
        binding.btnCloseToiletTable.setOnClickListener {
            hideToiletOverlay()
        }

        // Bottom-Right FAB: Find Closest Open Toilet
        binding.fabClosestToilet.setOnClickListener {
            onClosestToiletFabClicked()
        }

        // Cancel route planning button
        binding.cancelRoutePlanningBtn.setOnClickListener {
            cancelRoutePlanning()
        }

        // Close active route summary
        binding.closeRouteBtn.setOnClickListener {
            clearActiveRoute()
        }

        // Sync initial button icon with current mode
        updateFindShortestRouteButtonIcon()
    }

    private fun initTouchInterceptor() {
        binding.rootLayout.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                dismissPromptCard()
            }
            false
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN) {
            dismissPromptCard()
        }

        // Interactive swipe-down on toilet table overlay to close it (without moving the map underneath)
        if (binding.toiletOverlay.visibility == View.VISIBLE) {
            val overlayRect = Rect()
            binding.toiletOverlay.getGlobalVisibleRect(overlayRect)
            val insideOverlay = overlayRect.contains(ev.rawX.toInt(), ev.rawY.toInt())

            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (insideOverlay) {
                        swipeOverlayStartY = ev.rawY
                        swipeOverlayStartX = ev.rawX
                        isTrackingOverlaySwipe = true
                        isSwipeDragging = false
                        hasDispatchedCancelToOverlay = false
                        // Let super dispatch ACTION_DOWN so buttons inside toiletOverlay receive clicks normally.
                        // Because toiletOverlay is on top and has clickable=true, mapView NEVER receives this!
                        return super.dispatchTouchEvent(ev)
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isTrackingOverlaySwipe) {
                        val dy = ev.rawY - swipeOverlayStartY
                        val dx = ev.rawX - swipeOverlayStartX
                        val slop = ViewConfiguration.get(this).scaledTouchSlop.toFloat()
                        if (dy > slop && dy > kotlin.math.abs(dx)) {
                            // User is dragging downwards to dismiss table
                            isSwipeDragging = true
                            if (!hasDispatchedCancelToOverlay) {
                                // Cancel child button pressed highlight so buttons don't stay pressed
                                val cancelEvent = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
                                super.dispatchTouchEvent(cancelEvent)
                                cancelEvent.recycle()
                                hasDispatchedCancelToOverlay = true
                            }
                            binding.toiletOverlay.translationY = dy
                            return true // Intercept drag downward so nothing else moves
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isTrackingOverlaySwipe) {
                        isTrackingOverlaySwipe = false
                        if (isSwipeDragging) {
                            isSwipeDragging = false
                            val dy = ev.rawY - swipeOverlayStartY
                            val threshold = 65 * resources.displayMetrics.density
                            if (dy > threshold) {
                                binding.toiletOverlay.animate()
                                    .translationY(binding.toiletOverlay.height.toFloat().coerceAtLeast(500f))
                                    .setDuration(180)
                                    .withEndAction {
                                        hideToiletOverlay()
                                        binding.toiletOverlay.translationY = 0f
                                    }
                                    .start()
                            } else {
                                binding.toiletOverlay.animate()
                                    .translationY(0f)
                                    .setDuration(150)
                                    .start()
                            }
                            return true
                        }
                        // If not dragging, super.dispatchTouchEvent(ev) will trigger normal click on button
                    }
                }
            }
        }

        return super.dispatchTouchEvent(ev)
    }

    private fun setupMap(map: MapLibreMap) {
        map.uiSettings.isZoomGesturesEnabled = true
        map.uiSettings.isScrollGesturesEnabled = true
        map.uiSettings.isDoubleTapGesturesEnabled = true
        map.uiSettings.isRotateGesturesEnabled = true
        map.uiSettings.isTiltGesturesEnabled = false
        map.uiSettings.isAttributionEnabled = false
        map.uiSettings.isLogoEnabled = false
        // Dedicated custom north button handles compass display below the search bar
        map.uiSettings.isCompassEnabled = false

        map.setStyle(Style.Builder().fromUri("asset://style.json")) { style ->
            // Add marker icons to style (including selected states with black blurred shadow)
            style.addImage("marker-open", ToiletMarkerHelper.createMarkerBitmap(this, ToiletStatus.OPEN, isSelected = false))
            style.addImage("marker-closed", ToiletMarkerHelper.createMarkerBitmap(this, ToiletStatus.CLOSED, isSelected = false))
            style.addImage("marker-unknown", ToiletMarkerHelper.createMarkerBitmap(this, ToiletStatus.UNKNOWN, isSelected = false))

            style.addImage("marker-open-selected", ToiletMarkerHelper.createMarkerBitmap(this, ToiletStatus.OPEN, isSelected = true))
            style.addImage("marker-closed-selected", ToiletMarkerHelper.createMarkerBitmap(this, ToiletStatus.CLOSED, isSelected = true))
            style.addImage("marker-unknown-selected", ToiletMarkerHelper.createMarkerBitmap(this, ToiletStatus.UNKNOWN, isSelected = true))

            // Add GeoJSON sources and layers
            initMapLayers(style)

            // Setup camera: default view for all of Germany or restore saved camera
            setDefaultGermanyCamera(map)

            // Setup camera listeners
            map.addOnCameraMoveListener {
                updateCompassNeedle()
                updateScaleBar()
                updateToiletsLayerVisibility()
            }

            map.addOnCameraIdleListener {
                saveCurrentCameraPosition()
                updateCompassNeedle()
                updateScaleBar()
                updateToiletsLayerVisibility()
                checkRegionDownloadEligibility()
            }

            // By default, no toilet data is downloaded from network.
            // Load only previously downloaded regions stored offline.
            val offlineToilets = toiletRepo.loadAllOfflineDownloadedToilets()
            renderToiletsOnMap(offlineToilets)

            // Initial UI updates
            updateCompassNeedle()
            updateScaleBar()
            updateToiletsLayerVisibility()
            checkRegionDownloadEligibility()

            // Setup map click listeners
            setupMapClickListeners(map)
        }
    }

    /**
     * Sets the default camera to include all of Germany (based on screen width or height depending
     * on the aspect ratio), or restores the last saved camera position and zoom if previously stored.
     */
    private fun setDefaultGermanyCamera(map: MapLibreMap) {
        val prefs = getSharedPreferences("map_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("has_saved_camera", false)) {
            val lat = prefs.getString("camera_lat", null)?.toDoubleOrNull() ?: defaultLat
            val lon = prefs.getString("camera_lon", null)?.toDoubleOrNull() ?: defaultLon
            val zoom = prefs.getFloat("camera_zoom", 5.6f).toDouble()
            val bearing = prefs.getFloat("camera_bearing", 0f).toDouble()

            val position = CameraPosition.Builder()
                .target(LatLng(lat, lon))
                .zoom(zoom)
                .bearing(bearing)
                .build()

            map.moveCamera(CameraUpdateFactory.newCameraPosition(position))
            return
        }

        // Compute aspect-ratio aware zoom for all of Germany
        val widthPx = resources.displayMetrics.widthPixels.toDouble()
        val heightPx = resources.displayMetrics.heightPixels.toDouble()
        val latRad = Math.toRadians(defaultLat)

        val germanyWidthMeters = 640000.0 // East-West span of Germany ~640 km
        val germanyHeightMeters = 870000.0 // North-South span of Germany ~870 km

        val zoomForWidth = log2((widthPx * earthCircumferenceMeters * cos(latRad)) / (256.0 * germanyWidthMeters))
        val zoomForHeight = log2((heightPx * earthCircumferenceMeters * cos(latRad)) / (256.0 * germanyHeightMeters))
        val targetZoom = min(zoomForWidth, zoomForHeight).coerceIn(4.5, 18.0)

        val initialPosition = CameraPosition.Builder()
            .target(LatLng(defaultLat, defaultLon))
            .zoom(targetZoom)
            .bearing(0.0)
            .build()
        map.moveCamera(CameraUpdateFactory.newCameraPosition(initialPosition))

        // Ensure exact bounds fitting once MapView has completed layout
        binding.mapView.post {
            try {
                val germanyBounds = LatLngBounds.from(55.0581, 15.0419, 47.2701, 5.8663)
                map.moveCamera(CameraUpdateFactory.newLatLngBounds(germanyBounds, 32))
            } catch (e: Exception) {
                // Layout ready
            }
        }
    }

    /**
     * Stores current map position and zoom level so the app opens at the same location.
     */
    private fun saveCurrentCameraPosition() {
        val map = mapLibreMap ?: return
        val cam = map.cameraPosition
        val target = cam.target ?: return
        getSharedPreferences("map_prefs", Context.MODE_PRIVATE).edit()
            .putBoolean("has_saved_camera", true)
            .putString("camera_lat", target.latitude.toString())
            .putString("camera_lon", target.longitude.toString())
            .putFloat("camera_zoom", cam.zoom.toFloat())
            .putFloat("camera_bearing", cam.bearing.toFloat())
            .apply()
    }

    private fun updateCompassNeedle() {
        val map = mapLibreMap ?: return
        val bearing = map.cameraPosition.bearing
        binding.imgCompassNeedle.rotation = (-bearing).toFloat()
    }

    /**
     * Checks if the map area on the screen is wider than 25 km.
     */
    private fun isMapAreaWiderThan25Km(): Boolean {
        val map = mapLibreMap ?: return true
        val widthPx = resources.displayMetrics.widthPixels.toDouble()
        val cam = map.cameraPosition
        val target = cam.target ?: return true
        val latRad = Math.toRadians(target.latitude)
        val metersPerPx = (earthCircumferenceMeters * cos(latRad)) / (256.0 * 2.0.pow(cam.zoom))
        val screenWidthMeters = widthPx * metersPerPx
        return screenWidthMeters > 25000.0
    }

    /**
     * Hides toilet icons and labels if the map area on the screen is wider than 25 km.
     */
    private fun updateToiletsLayerVisibility() {
        val style = mapLibreMap?.style ?: return
        val toiletsLayer = style.getLayerAs<SymbolLayer>("toilets-layer") ?: return
        val hideToilets = isMapAreaWiderThan25Km()
        toiletsLayer.setProperties(visibility(if (hideToilets) Property.NONE else Property.VISIBLE))
    }

    /**
     * Updates the scale bar in the bottom-left of the screen.
     * Guaranteed not to exceed 1/3 of the screen width.
     */
    private fun updateScaleBar() {
        val map = mapLibreMap ?: return
        val widthPx = resources.displayMetrics.widthPixels.toDouble()
        val maxWidthPx = widthPx / 3.0 // Must not be wider than 1/3 of screen width
        val cam = map.cameraPosition
        val target = cam.target ?: return
        val latRad = Math.toRadians(target.latitude)
        val metersPerPx = (earthCircumferenceMeters * cos(latRad)) / (256.0 * 2.0.pow(cam.zoom))

        val maxMeters = maxWidthPx * metersPerPx

        val steps = doubleArrayOf(
            10.0, 20.0, 50.0, 100.0, 200.0, 500.0,
            1000.0, 2000.0, 5000.0, 10000.0, 20000.0, 50000.0,
            100000.0, 200000.0, 500000.0, 1000000.0
        )

        var chosenMeters = steps[0]
        for (step in steps) {
            if (step <= maxMeters) {
                chosenMeters = step
            } else break
        }

        val barWidthPx = (chosenMeters / metersPerPx).coerceAtMost(maxWidthPx).toInt()
        val labelText = if (chosenMeters >= 1000.0) {
            "${(chosenMeters / 1000.0).toInt()} km"
        } else {
            "${chosenMeters.toInt()} m"
        }

        binding.scaleBarText.text = labelText
        val params = binding.scaleBarLine.layoutParams
        params.width = barWidthPx
        binding.scaleBarLine.layoutParams = params
    }

    /**
     * Checks whether the user should be offered a download option:
     * 1. If an area covers >= 75% of screen area, download is offered for THAT area.
     * 2. If screen width < 25 km, center of screen determines region.
     * When user dismisses, it resets when zooming out and reappears when zooming in again.
     */
    private fun checkRegionDownloadEligibility() {
        val map = mapLibreMap ?: return
        val cam = map.cameraPosition
        val target = cam.target ?: return
        val widthPx = resources.displayMetrics.widthPixels.toDouble()
        val latRad = Math.toRadians(target.latitude)
        val metersPerPx = (earthCircumferenceMeters * cos(latRad)) / (256.0 * 2.0.pow(cam.zoom))
        val screenWidthMeters = widthPx * metersPerPx

        val bounds = map.projection.visibleRegion.latLngBounds

        // Reset dismissal if user zoomed out significantly
        val currentZoom = cam.zoom
        val dZoom = dismissedAtZoom
        if (dismissedRegionId != null && dZoom != null && currentZoom < dZoom - 0.4) {
            dismissedRegionId = null
            dismissedAtZoom = null
        }

        regionCheckJob?.cancel()
        regionCheckJob = lifecycleScope.launch {
            kotlinx.coroutines.delay(300) // Debounce camera movement

            val region = toiletRepo.regionManager.resolveRegionForDownload(
                centerLat = target.latitude,
                centerLon = target.longitude,
                screenSouth = bounds.latitudeSouth,
                screenWest = bounds.longitudeWest,
                screenNorth = bounds.latitudeNorth,
                screenEast = bounds.longitudeEast,
                screenWidthMeters = screenWidthMeters
            )

            if (region == null) {
                binding.regionDownloadCard.visibility = View.GONE
                return@launch
            }

            if (toiletRepo.regionManager.isRegionDownloaded(region.id)) {
                binding.regionDownloadCard.visibility = View.GONE
                return@launch
            }

            if (dismissedRegionId != region.id) {
                pendingDownloadRegion = region
                binding.regionDownloadTitle.text = getString(R.string.download_region_prompt, region.name)
                binding.regionDownloadCard.visibility = View.VISIBLE
            } else {
                binding.regionDownloadCard.visibility = View.GONE
            }
        }
    }

    private fun downloadRegionToilets(region: RegionInfo) {
        lifecycleScope.launch {
            binding.regionDownloadProgress.visibility = View.VISIBLE
            binding.btnDownloadRegion.isEnabled = false

            Toast.makeText(this@MainActivity, "Downloading toilets for ${region.name}...", Toast.LENGTH_SHORT).show()
            val allToilets = toiletRepo.downloadRegionToilets(region)

            binding.regionDownloadProgress.visibility = View.GONE
            binding.btnDownloadRegion.isEnabled = true

            if (allToilets.isNullOrEmpty()) {
                androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                    .setMessage("Error when downloading data")
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                Toast.makeText(this@MainActivity, "Error when downloading data", Toast.LENGTH_LONG).show()
                return@launch
            }

            binding.regionDownloadCard.visibility = View.GONE
            renderToiletsOnMap(allToilets)
            Toast.makeText(this@MainActivity, "Toilet data for ${region.name} saved offline", Toast.LENGTH_SHORT).show()
        }
    }

    private fun initMapLayers(style: Style) {
        // Route casing layer
        val routeSource = GeoJsonSource("route-source")
        style.addSource(routeSource)

        val routeCasing = LineLayer("route-casing-layer", "route-source").apply {
            setProperties(
                lineColor(Color.parseColor("#0D47A1")),
                lineWidth(8f),
                lineCap(Property.LINE_CAP_ROUND),
                lineJoin(Property.LINE_JOIN_ROUND)
            )
        }
        style.addLayer(routeCasing)

        val routeLine = LineLayer("route-line-layer", "route-source").apply {
            setProperties(
                lineColor(Color.parseColor("#1E88E5")),
                lineWidth(5f),
                lineCap(Property.LINE_CAP_ROUND),
                lineJoin(Property.LINE_JOIN_ROUND)
            )
        }
        style.addLayer(routeLine)

        // Toilets source and symbol layer
        val toiletsSource = GeoJsonSource("toilets-source")
        style.addSource(toiletsSource)

        val toiletsLayer = SymbolLayer("toilets-layer", "toilets-source").apply {
            setProperties(
                iconImage(Expression.get("icon-id")),
                iconAllowOverlap(true),
                iconIgnorePlacement(true)
            )
        }
        style.addLayer(toiletsLayer)
    }

    private fun renderToiletsOnMap(toilets: List<Toilet>) {
        val style = mapLibreMap?.style ?: return
        val source = style.getSourceAs<GeoJsonSource>("toilets-source") ?: return

        val featureCollection = JsonObject().apply {
            addProperty("type", "FeatureCollection")
            val features = JsonArray()
            for (toilet in toilets) {
                val isSelected = (toilet.id == selectedToilet?.id)
                val eval = OpeningHoursEvaluator.evaluate(toilet.openingHours)
                val suffix = if (isSelected) "-selected" else ""
                val iconId = when (eval.status) {
                    ToiletStatus.OPEN -> "marker-open$suffix"
                    ToiletStatus.CLOSED -> "marker-closed$suffix"
                    ToiletStatus.UNKNOWN -> "marker-unknown$suffix"
                }

                val feature = JsonObject().apply {
                    addProperty("type", "Feature")
                    val geom = JsonObject().apply {
                        addProperty("type", "Point")
                        val coords = JsonArray().apply {
                            add(toilet.lon)
                            add(toilet.lat)
                        }
                        add("coordinates", coords)
                    }
                    add("geometry", geom)

                    val props = JsonObject().apply {
                        addProperty("id", toilet.id)
                        addProperty("icon-id", iconId)
                        addProperty("fee", toilet.feeDisplay)
                        addProperty("charge", toilet.chargeDisplay)
                        addProperty("opening_hours", toilet.openingHoursDisplay)
                        addProperty("status", eval.status.name)
                        addProperty("isSelected", isSelected)
                    }
                    add("properties", props)
                }
                features.add(feature)
            }
            add("features", features)
        }

        source.setGeoJson(featureCollection.toString())
    }

    private fun setupMapClickListeners(map: MapLibreMap) {
        map.addOnMapClickListener { point ->
            val screenPoint = map.projection.toScreenLocation(point)

            // If in starting point selection mode:
            if (isSelectingStartPointForRoute) {
                val targetToilet = routeTargetToilet
                if (targetToilet != null) {
                    isSelectingStartPointForRoute = false
                    hideRouteGuideBanner()
                    calculateAndDisplayRoute(point.latitude, point.longitude, targetToilet.lat, targetToilet.lon)
                }
                return@addOnMapClickListener true
            }

            if (isSelectingStartPointForClosest) {
                isSelectingStartPointForClosest = false
                hideRouteGuideBanner()
                findAndDisplayClosestOpenToilet(point.latitude, point.longitude)
                return@addOnMapClickListener true
            }

            // Do not query toilet markers if map area is wider than 25 km (markers hidden)
            if (isMapAreaWiderThan25Km()) {
                return@addOnMapClickListener false
            }

            // Otherwise check if a toilet marker was tapped
            val features = map.queryRenderedFeatures(screenPoint, "toilets-layer")
            if (features.isNotEmpty()) {
                val feature = features[0]
                val id = feature.getProperty("id")?.asLong ?: feature.getStringProperty("id")?.toLongOrNull()
                val toilet = toiletRepo.getAllCachedToilets().find { it.id == id }
                if (toilet != null) {
                    showToiletOverlay(toilet)
                    return@addOnMapClickListener true
                }
            }

            false
        }
    }

    private fun showToiletOverlay(toilet: Toilet) {
        selectedToilet = toilet
        val eval = OpeningHoursEvaluator.evaluate(toilet.openingHours)

        binding.toiletTitle.text = toilet.name ?: "Public Toilet"
        binding.tableValueFee.text = toilet.feeDisplay
        binding.tableValueCharge.text = toilet.chargeDisplay
        binding.tableValueOpeningHours.text = toilet.openingHoursDisplay

        when (eval.status) {
            ToiletStatus.OPEN -> {
                binding.toiletStatusBadge.text = getString(R.string.status_open)
                binding.toiletStatusBadge.setBackgroundResource(R.drawable.bg_status_pill)
                binding.toiletStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.status_open_text))
            }
            ToiletStatus.CLOSED -> {
                binding.toiletStatusBadge.text = getString(R.string.status_closed)
                binding.toiletStatusBadge.setBackgroundColor(ContextCompat.getColor(this, R.color.status_closed_bg))
                binding.toiletStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.status_closed_text))
            }
            ToiletStatus.UNKNOWN -> {
                binding.toiletStatusBadge.text = getString(R.string.status_hours_unknown)
                binding.toiletStatusBadge.setBackgroundColor(ContextCompat.getColor(this, R.color.status_unknown_bg))
                binding.toiletStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.status_unknown_text))
            }
        }

        binding.toiletOverlay.translationY = 0f
        binding.toiletOverlay.visibility = View.VISIBLE

        // Re-render markers so the selected toilet gets the black blurred shadow
        renderToiletsOnMap(toiletRepo.getAllCachedToilets())

        binding.fabClosestToilet.visibility = View.GONE
        binding.routeSummaryCard.visibility = View.GONE
        binding.scaleBarContainer.visibility = View.GONE
    }

    private fun hideToiletOverlay() {
        binding.toiletOverlay.visibility = View.GONE
        binding.toiletOverlay.translationY = 0f
        selectedToilet = null

        // Re-render markers to remove the highlight shadow
        renderToiletsOnMap(toiletRepo.getAllCachedToilets())

        binding.fabClosestToilet.visibility = View.VISIBLE
        binding.scaleBarContainer.visibility = View.VISIBLE
    }

    private fun onClosestToiletFabClicked() {
        hideToiletOverlay()
        clearActiveRoute()

        if (hasLocationPermission() && isLocationEnabled()) {
            val loc = getLastKnownLocation()
            if (loc != null) {
                findAndDisplayClosestOpenToilet(loc.latitude, loc.longitude)
                return
            }
        }

        showPromptCard(getString(R.string.prompt_tap_starting_point))
        isSelectingStartPointForClosest = true
        isSelectingStartPointForRoute = false
        showRouteGuideBanner(getString(R.string.prompt_tap_starting_point))
    }

    private fun showPromptCard(message: String) {
        binding.promptTextView.text = message
        binding.promptCard.visibility = View.VISIBLE
        binding.promptCard.alpha = 1.0f

        dismissPromptRunnable?.let { handler.removeCallbacks(it) }
        val r = Runnable {
            binding.promptCard.animate()
                .alpha(0f)
                .setDuration(250)
                .withEndAction {
                    binding.promptCard.visibility = View.GONE
                    binding.promptCard.alpha = 1f
                }
                .start()
        }
        dismissPromptRunnable = r
        handler.postDelayed(r, 3000L)
    }

    private fun dismissPromptCard() {
        dismissPromptRunnable?.let {
            handler.removeCallbacks(it)
            dismissPromptRunnable = null
        }
        if (binding.promptCard.visibility == View.VISIBLE) {
            binding.promptCard.visibility = View.GONE
        }
    }

    private fun findAndDisplayClosestOpenToilet(startLat: Double, startLon: Double) {
        lifecycleScope.launch {
            Toast.makeText(this@MainActivity, "Searching for closest open toilet...", Toast.LENGTH_SHORT).show()
            val toilets = toiletRepo.getAllCachedToilets()
            val result = closestToiletFinder.findClosestOpenToilet(startLat, startLon, toilets, currentMode)

            if (result != null) {
                activeRouteStartLat = startLat
                activeRouteStartLon = startLon
                activeRouteToilet = result.toilet
                routeTargetToilet = result.toilet

                drawRouteOnMap(result.route)
                showRouteSummary(result.toilet, result.route, result.remainingOpenSeconds)
                zoomToRoute(result.route.coordinates)
            } else {
                Toast.makeText(
                    this@MainActivity,
                    "No open toilets found that remain open for route duration + 10 minutes",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun calculateAndDisplayRoute(startLat: Double, startLon: Double, destLat: Double, destLon: Double) {
        lifecycleScope.launch {
            Toast.makeText(this@MainActivity, "Calculating shortest route...", Toast.LENGTH_SHORT).show()
            val route = routingService.calculateRoute(startLat, startLon, destLat, destLon, currentMode)
            if (route != null) {
                activeRouteStartLat = startLat
                activeRouteStartLon = startLon
                activeRouteToilet = routeTargetToilet

                drawRouteOnMap(route)
                val destToilet = routeTargetToilet
                val eval = destToilet?.let { OpeningHoursEvaluator.evaluate(it.openingHours) }
                showRouteSummary(destToilet, route, eval?.remainingOpenSeconds)
                zoomToRoute(route.coordinates)
            } else {
                Toast.makeText(this@MainActivity, "Unable to find route to destination", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun drawRouteOnMap(route: RouteResult) {
        val style = mapLibreMap?.style ?: return
        val source = style.getSourceAs<GeoJsonSource>("route-source") ?: return

        val featureCollection = JsonObject().apply {
            addProperty("type", "FeatureCollection")
            val features = JsonArray()
            val feature = JsonObject().apply {
                addProperty("type", "Feature")
                val geom = JsonObject().apply {
                    addProperty("type", "LineString")
                    val coords = JsonArray()
                    for ((lat, lon) in route.coordinates) {
                        val pt = JsonArray().apply {
                            add(lon)
                            add(lat)
                        }
                        coords.add(pt)
                    }
                    add("coordinates", coords)
                }
                add("geometry", geom)
            }
            features.add(feature)
            add("features", features)
        }

        source.setGeoJson(featureCollection.toString())
    }

    private fun clearActiveRoute() {
        activeRouteStartLat = null
        activeRouteStartLon = null
        activeRouteToilet = null

        val style = mapLibreMap?.style ?: return
        val source = style.getSourceAs<GeoJsonSource>("route-source") ?: return
        source.setGeoJson("{\"type\":\"FeatureCollection\",\"features\":[]}")
        binding.routeSummaryCard.visibility = View.GONE
        binding.fabClosestToilet.visibility = View.VISIBLE
    }

    private fun showRouteSummary(toilet: Toilet?, route: RouteResult, remainingSec: Long?) {
        binding.routeTitle.text = toilet?.name ?: "Route to Public Toilet"

        val durationMinutes = (route.durationSeconds / 60.0).toInt().coerceAtLeast(1)
        binding.routeDurationText.text = "$durationMinutes min"

        val distanceText = if (route.distanceMeters >= 1000) {
            String.format(java.util.Locale.US, "(%.1f km)", route.distanceMeters / 1000.0)
        } else {
            "(${route.distanceMeters.toInt()} m)"
        }
        binding.routeDistanceText.text = distanceText

        if (remainingSec != null && remainingSec < Long.MAX_VALUE) {
            val remainMin = (remainingSec / 60.0).toInt()
            binding.routeBufferText.visibility = View.VISIBLE
            binding.routeBufferText.text = "Closes in ${remainMin}m (Buffer ok)"
        } else if (remainingSec == Long.MAX_VALUE) {
            binding.routeBufferText.visibility = View.VISIBLE
            binding.routeBufferText.text = "Open 24/7"
        } else {
            binding.routeBufferText.visibility = View.GONE
        }

        binding.routeSummaryCard.visibility = View.VISIBLE
        binding.fabClosestToilet.visibility = View.GONE
    }

    private fun zoomToRoute(points: List<Pair<Double, Double>>) {
        val map = mapLibreMap ?: return
        if (points.isEmpty()) return

        var minLat = Double.MAX_VALUE
        var maxLat = -Double.MAX_VALUE
        var minLon = Double.MAX_VALUE
        var maxLon = -Double.MAX_VALUE

        for ((lat, lon) in points) {
            minLat = minOf(minLat, lat)
            maxLat = maxOf(maxLat, lat)
            minLon = minOf(minLon, lon)
            maxLon = maxOf(maxLon, lon)
        }

        val bounds = LatLngBounds.from(maxLat, maxLon, minLat, minLon)
        map.easeCamera(CameraUpdateFactory.newLatLngBounds(bounds, 120))
    }

    private fun setTransportMode(mode: TransportMode) {
        currentMode = mode

        // Update button UI
        val selectedBg = R.drawable.bg_mode_item_selected
        binding.modeWalkBtn.setBackgroundResource(if (mode == TransportMode.WALK) selectedBg else 0)
        binding.modeWalkText.setTextColor(if (mode == TransportMode.WALK) Color.WHITE else ContextCompat.getColor(this, R.color.text_secondary))
        binding.modeWalkIcon.setColorFilter(if (mode == TransportMode.WALK) Color.WHITE else ContextCompat.getColor(this, R.color.text_secondary))

        binding.modeBikeBtn.setBackgroundResource(if (mode == TransportMode.BIKE) selectedBg else 0)
        binding.modeBikeText.setTextColor(if (mode == TransportMode.BIKE) Color.WHITE else ContextCompat.getColor(this, R.color.text_secondary))
        binding.modeBikeIcon.setColorFilter(if (mode == TransportMode.BIKE) Color.WHITE else ContextCompat.getColor(this, R.color.text_secondary))

        binding.modeDriveBtn.setBackgroundResource(if (mode == TransportMode.CAR) selectedBg else 0)
        binding.modeDriveText.setTextColor(if (mode == TransportMode.CAR) Color.WHITE else ContextCompat.getColor(this, R.color.text_secondary))
        binding.modeDriveIcon.setColorFilter(if (mode == TransportMode.CAR) Color.WHITE else ContextCompat.getColor(this, R.color.text_secondary))

        // Update Find Shortest Route icon to match selected transport mode
        updateFindShortestRouteButtonIcon()

        // When mode changes and an active route is shown, recalculate without crashing
        if (binding.routeSummaryCard.visibility == View.VISIBLE) {
            val sLat = activeRouteStartLat
            val sLon = activeRouteStartLon
            val dest = activeRouteToilet
            if (sLat != null && sLon != null && dest != null) {
                calculateAndDisplayRoute(sLat, sLon, dest.lat, dest.lon)
            }
        }
    }

    private fun updateFindShortestRouteButtonIcon() {
        val iconRes = when (currentMode) {
            TransportMode.WALK -> R.drawable.ic_directions_walk
            TransportMode.BIKE -> R.drawable.ic_directions_bike
            TransportMode.CAR -> R.drawable.ic_directions_car
        }
        binding.btnFindShortestRoute.setIconResource(iconRes)
    }

    private fun performSearch(query: String, immediate: Boolean = false) {
        searchJob?.cancel()
        // Suggestions only appear when user is actively searching with focus
        if (query.length < 2 || !binding.searchEditText.hasFocus()) {
            searchAdapter.clear()
            binding.suggestionsCard.visibility = View.GONE
            return
        }

        searchJob = lifecycleScope.launch {
            if (!immediate) delay(300)
            if (!binding.searchEditText.hasFocus()) {
                binding.suggestionsCard.visibility = View.GONE
                return@launch
            }
            val results = geocodingService.search(query)
            if (results.isNotEmpty() && binding.searchEditText.hasFocus()) {
                searchAdapter.submitList(results)
                binding.suggestionsCard.visibility = View.VISIBLE
            } else {
                binding.suggestionsCard.visibility = View.GONE
            }
        }
    }

    private fun onSearchResultSelected(result: SearchResult) {
        // Cancel active search job and close suggestions list immediately
        searchJob?.cancel()
        searchAdapter.clear()
        binding.suggestionsCard.visibility = View.GONE

        // Set search text with programmatic flag so TextWatcher does not re-open suggestions
        isProgrammaticSearchTextChange = true
        binding.searchEditText.setText(result.name)
        isProgrammaticSearchTextChange = false

        // Clear focus so blinking cursor stops and suggestions don't reappear on screen rotation
        binding.searchEditText.clearFocus()
        binding.rootLayout.requestFocus()
        hideKeyboard()

        // If in starting point selection mode:
        if (isSelectingStartPointForRoute) {
            val target = routeTargetToilet
            if (target != null) {
                isSelectingStartPointForRoute = false
                hideRouteGuideBanner()
                calculateAndDisplayRoute(result.lat, result.lon, target.lat, target.lon)
            }
            return
        }

        if (isSelectingStartPointForClosest) {
            isSelectingStartPointForClosest = false
            hideRouteGuideBanner()
            findAndDisplayClosestOpenToilet(result.lat, result.lon)
            return
        }

        // Navigate camera to selected search result
        mapLibreMap?.let { map ->
            val position = CameraPosition.Builder()
                .target(LatLng(result.lat, result.lon))
                .zoom(15.0)
                .build()
            map.animateCamera(CameraUpdateFactory.newCameraPosition(position))
        }
    }

    private fun handleGpsButtonClick() {
        if (!hasLocationPermission()) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                1001
            )
            return
        }

        val loc = getLastKnownLocation()
        if (loc != null) {
            if (isSelectingStartPointForRoute) {
                val target = routeTargetToilet
                if (target != null) {
                    isSelectingStartPointForRoute = false
                    hideRouteGuideBanner()
                    calculateAndDisplayRoute(loc.latitude, loc.longitude, target.lat, target.lon)
                }
            } else if (isSelectingStartPointForClosest) {
                isSelectingStartPointForClosest = false
                hideRouteGuideBanner()
                findAndDisplayClosestOpenToilet(loc.latitude, loc.longitude)
            } else {
                mapLibreMap?.let { map ->
                    val pos = CameraPosition.Builder()
                        .target(LatLng(loc.latitude, loc.longitude))
                        .zoom(16.0)
                        .build()
                    map.animateCamera(CameraUpdateFactory.newCameraPosition(pos))
                }
            }
        } else {
            Toast.makeText(this, "Location currently unavailable", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showRouteGuideBanner(text: String) {
        binding.routeGuideText.text = text
        binding.routeGuideBanner.visibility = View.VISIBLE
    }

    private fun hideRouteGuideBanner() {
        binding.routeGuideBanner.visibility = View.GONE
    }

    private fun cancelRoutePlanning() {
        isSelectingStartPointForRoute = false
        isSelectingStartPointForClosest = false
        hideRouteGuideBanner()
        binding.fabClosestToilet.visibility = View.VISIBLE
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    this, Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
    }

    private fun isLocationEnabled(): Boolean {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    @SuppressLint("MissingPermission")
    private fun getLastKnownLocation(): Location? {
        if (!hasLocationPermission()) return null
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return try {
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        } catch (e: SecurityException) {
            null
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.searchEditText.windowToken, 0)
    }

    // MapView Lifecycle & Camera State Persistence
    override fun onStart() {
        super.onStart()
        binding.mapView.onStart()
    }

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        saveCurrentCameraPosition()
        binding.mapView.onPause()
    }

    override fun onStop() {
        super.onStop()
        saveCurrentCameraPosition()
        binding.mapView.onStop()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        binding.mapView.onLowMemory()
    }

    override fun onDestroy() {
        super.onDestroy()
        binding.mapView.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        saveCurrentCameraPosition()
        binding.mapView.onSaveInstanceState(outState)
    }
}
