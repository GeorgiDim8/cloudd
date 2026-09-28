package com.cloudd.transitstopfinder

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.Priority
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var modeToggleGroup: MaterialButtonToggleGroup
    private lateinit var busButton: MaterialButton
    private lateinit var trainButton: MaterialButton
    private lateinit var findStopButton: MaterialButton
    private lateinit var switchStopButton: MaterialButton
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar

    private val mainHandler = Handler(Looper.getMainLooper())
    private var selectedMode: TransitMode = TransitMode.BUS

    private var searchInProgress = false
    // Bumped whenever a search starts or is cancelled, so late callbacks from an old search are ignored.
    private var searchGeneration = 0

    private var latestLocation: Location? = null
    private var cachedLocation: Location? = null
    private var locationWaitStartedAt = 0L

    // The two candidate stops from the last search, so the "switch" button can
    // flip between them instantly without another network round-trip.
    private var currentStop: TransitStop? = null
    private var alternateStop: TransitStop? = null

    private val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, LOCATION_UPDATE_INTERVAL_MS)
        .setMinUpdateIntervalMillis(LOCATION_MIN_UPDATE_INTERVAL_MS)
        .setWaitForAccurateLocation(false)
        .build()

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { latestLocation = it }
        }
    }

    private val locationWaitCheck = Runnable { onLocationWaitTick() }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        when {
            results[Manifest.permission.ACCESS_FINE_LOCATION] == true -> findNearestStop()
            results[Manifest.permission.ACCESS_COARSE_LOCATION] == true -> {
                Toast.makeText(this, R.string.approximate_location_warning, Toast.LENGTH_LONG).show()
                findNearestStop()
            }
            else -> statusText.text = getString(R.string.status_permission_required)
        }
    }

    private val locationSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            findNearestStop()
        } else {
            statusText.text = getString(R.string.status_location_off)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        modeToggleGroup = findViewById(R.id.modeToggleGroup)
        busButton = findViewById(R.id.busModeButton)
        trainButton = findViewById(R.id.trainModeButton)
        findStopButton = findViewById(R.id.findStopButton)
        switchStopButton = findViewById(R.id.switchStopButton)
        statusText = findViewById(R.id.statusText)
        progressBar = findViewById(R.id.progressBar)

        modeToggleGroup.check(busButton.id) // Bus is the default mode.

        modeToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            selectedMode = if (checkedId == trainButton.id) TransitMode.TRAIN else TransitMode.BUS
            findNearestStop()
        }

        findStopButton.setOnClickListener { findNearestStop() }
        switchStopButton.setOnClickListener { switchToAlternateStop() }
    }

    override fun onStart() {
        super.onStart()
        // Search straight away when the app is opened, but not when the user just
        // backs out of Maps - they may want the "switch stop" button instead.
        val now = SystemClock.elapsedRealtime()
        if (lastAutoSearchAt == 0L || now - lastAutoSearchAt > AUTO_REFRESH_AFTER_MS) {
            lastAutoSearchAt = now
            findNearestStop()
        }
    }

    override fun onStop() {
        super.onStop()
        if (searchInProgress) cancelSearch()
    }

    private fun findNearestStop() {
        if (searchInProgress) return

        if (!hasAnyLocationPermission()) {
            permissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
            return
        }

        searchInProgress = true
        val generation = ++searchGeneration
        resetStopResults()
        setBusy(true, getString(R.string.status_refreshing_location))

        // Make sure the phone's Location switch is on; if not, show the system "turn on location" dialog.
        val settingsRequest = LocationSettingsRequest.Builder().addLocationRequest(locationRequest).build()
        LocationServices.getSettingsClient(this)
            .checkLocationSettings(settingsRequest)
            .addOnSuccessListener {
                if (generation == searchGeneration) startLocationWait()
            }
            .addOnFailureListener { error ->
                if (generation != searchGeneration) return@addOnFailureListener
                if (error is ResolvableApiException) {
                    endSearch(getString(R.string.status_location_off))
                    locationSettingsLauncher.launch(IntentSenderRequest.Builder(error.resolution).build())
                } else {
                    // Settings can't be checked on this device - try anyway.
                    startLocationWait()
                }
            }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationWait() {
        latestLocation = null
        cachedLocation = null
        locationWaitStartedAt = SystemClock.elapsedRealtime()

        val generation = searchGeneration
        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            if (generation == searchGeneration && location != null && ageMillis(location) <= CACHED_LOCATION_MAX_AGE_MS) {
                cachedLocation = location
            }
        }

        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())

        // Give the location provider a moment to deliver a fresh, settled fix before
        // acting on it - the first reading after a cold start is often inaccurate.
        mainHandler.postDelayed(locationWaitCheck, LOCATION_SETTLE_DELAY_MS)
    }

    private fun onLocationWaitTick() {
        val fresh = latestLocation
        val waited = SystemClock.elapsedRealtime() - locationWaitStartedAt

        if (fresh == null && waited < LOCATION_TIMEOUT_MS) {
            statusText.text = getString(R.string.status_waiting_for_gps)
            mainHandler.postDelayed(locationWaitCheck, LOCATION_RECHECK_INTERVAL_MS)
            return
        }

        stopLocationUpdates()
        val location = fresh ?: cachedLocation
        if (location == null) {
            endSearch(getString(R.string.status_no_location))
        } else {
            searchNearbyStops(location)
        }
    }

    private fun searchNearbyStops(location: Location) {
        statusText.text = getString(R.string.status_searching, selectedMode.label)

        val mode = selectedMode
        val generation = searchGeneration
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    PlacesNearbySearch(BuildConfig.PLACES_API_KEY)
                        .searchNearby(location.latitude, location.longitude, mode)
                }
            }
            if (generation != searchGeneration) return@launch

            result.onSuccess { stops -> onStopsFound(stops, mode) }
            result.onFailure { error ->
                endSearch(getString(R.string.status_network_error))
                Toast.makeText(this@MainActivity, error.message ?: "Unknown error", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun onStopsFound(stops: List<TransitStop>, mode: TransitMode) {
        if (stops.isEmpty()) {
            endSearch(getString(R.string.status_no_stops, mode.label))
            return
        }

        val nearest = stops[0]
        val secondNearest = stops.getOrNull(1)

        currentStop = nearest
        alternateStop = if (secondNearest != null && isAmbiguouslyClose(nearest, secondNearest)) {
            secondNearest
        } else {
            null
        }

        endSearch(getString(R.string.status_opening_maps, nearest.name))
        updateSwitchButton()
        openInMaps(nearest)
    }

    /**
     * True when the runner-up stop is close enough to the nearest one that a user standing
     * between them could plausibly want either - e.g. stops on opposite sides of the same
     * street, or two platforms of the same station complex.
     */
    private fun isAmbiguouslyClose(nearest: TransitStop, runnerUp: TransitStop): Boolean {
        val gapFromUser = runnerUp.distanceMetersFromUser - nearest.distanceMetersFromUser
        if (gapFromUser <= CLOSE_STOP_GAP_THRESHOLD_METERS) return true

        val betweenStops = FloatArray(1)
        Location.distanceBetween(
            nearest.latitude, nearest.longitude,
            runnerUp.latitude, runnerUp.longitude,
            betweenStops
        )
        return betweenStops[0] <= CLOSE_STOP_GAP_THRESHOLD_METERS
    }

    private fun switchToAlternateStop() {
        val current = currentStop ?: return
        val alternate = alternateStop ?: return

        // Swap roles so the button keeps offering "the other one" on each tap.
        currentStop = alternate
        alternateStop = current

        statusText.text = getString(R.string.status_opening_maps, alternate.name)
        updateSwitchButton()
        openInMaps(alternate)
    }

    private fun updateSwitchButton() {
        val alternate = alternateStop
        if (alternate == null) {
            switchStopButton.visibility = View.GONE
            return
        }
        switchStopButton.text = getString(
            R.string.switch_stop_button,
            alternate.name,
            "%.0fm".format(alternate.distanceMetersFromUser)
        )
        switchStopButton.visibility = View.VISIBLE
    }

    private fun openInMaps(stop: TransitStop) {
        val uri = Uri.parse(
            "https://www.google.com/maps/search/?api=1" +
                "&query=" + Uri.encode(stop.name) +
                "&query_place_id=" + Uri.encode(stop.placeId)
        )

        val mapsIntent = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage("com.google.android.apps.maps")
        }

        if (mapsIntent.resolveActivity(packageManager) != null) {
            startActivity(mapsIntent)
        } else {
            // Google Maps app isn't installed - fall back to letting the OS pick a handler.
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        }
    }

    private fun stopLocationUpdates() {
        mainHandler.removeCallbacks(locationWaitCheck)
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }

    private fun cancelSearch() {
        searchGeneration++
        stopLocationUpdates()
        endSearch(getString(R.string.status_idle))
        // The search never finished, so search again next time the app is opened.
        lastAutoSearchAt = 0L
    }

    private fun endSearch(statusMessage: String) {
        searchInProgress = false
        setBusy(false, statusMessage)
    }

    private fun resetStopResults() {
        currentStop = null
        alternateStop = null
        switchStopButton.visibility = View.GONE
    }

    private fun setBusy(busy: Boolean, statusMessage: String) {
        progressBar.visibility = if (busy) View.VISIBLE else View.GONE
        findStopButton.isEnabled = !busy
        busButton.isEnabled = !busy
        trainButton.isEnabled = !busy
        statusText.text = statusMessage
    }

    private fun hasAnyLocationPermission(): Boolean =
        isGranted(Manifest.permission.ACCESS_FINE_LOCATION) || isGranted(Manifest.permission.ACCESS_COARSE_LOCATION)

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun ageMillis(location: Location): Long =
        (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000

    companion object {
        private const val LOCATION_UPDATE_INTERVAL_MS = 1000L
        private const val LOCATION_MIN_UPDATE_INTERVAL_MS = 500L
        // Minimum wait before acting on a fix, so a cold GPS reading has time to settle.
        private const val LOCATION_SETTLE_DELAY_MS = 2500L
        private const val LOCATION_RECHECK_INTERVAL_MS = 500L
        // Give up waiting for a fresh fix after this long and fall back to the phone's last known location.
        private const val LOCATION_TIMEOUT_MS = 12_000L
        private const val CACHED_LOCATION_MAX_AGE_MS = 5 * 60 * 1000L
        private const val AUTO_REFRESH_AFTER_MS = 2 * 60 * 1000L
        private const val CLOSE_STOP_GAP_THRESHOLD_METERS = 150.0

        // Process-wide so it survives screen rotation; resets when the app is fully closed.
        private var lastAutoSearchAt = 0L
    }
}
