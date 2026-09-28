package com.cloudd.transitstopfinder

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var modeToggleGroup: MaterialButtonToggleGroup
    private lateinit var findStopButton: MaterialButton
    private lateinit var switchStopButton: MaterialButton
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar

    private val mainHandler = Handler(Looper.getMainLooper())
    private var selectedMode: TransitMode = TransitMode.BUS

    private var latestLocation: Location? = null

    // The two candidate stops from the last search, so the "switch" button can
    // flip between them instantly without another network round-trip.
    private var currentStop: TransitStop? = null
    private var alternateStop: TransitStop? = null

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { latestLocation = it }
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            findNearestStop()
        } else {
            statusText.text = getString(R.string.status_permission_required)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        modeToggleGroup = findViewById(R.id.modeToggleGroup)
        findStopButton = findViewById(R.id.findStopButton)
        switchStopButton = findViewById(R.id.switchStopButton)
        statusText = findViewById(R.id.statusText)
        progressBar = findViewById(R.id.progressBar)

        val busButton = findViewById<MaterialButton>(R.id.busModeButton)
        val trainButton = findViewById<MaterialButton>(R.id.trainModeButton)
        modeToggleGroup.check(busButton.id) // Bus is the default mode.

        modeToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            selectedMode = if (checkedId == trainButton.id) TransitMode.TRAIN else TransitMode.BUS
            resetStopResults()
        }

        findStopButton.setOnClickListener { findNearestStop() }
        switchStopButton.setOnClickListener { switchToAlternateStop() }
    }

    override fun onStop() {
        super.onStop()
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }

    private fun findNearestStop() {
        if (!hasLocationPermission()) {
            requestPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            return
        }

        resetStopResults()
        setBusy(true, getString(R.string.status_refreshing_location))

        latestLocation = null
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, LOCATION_UPDATE_INTERVAL_MS)
            .setMaxUpdates(LOCATION_MAX_UPDATES)
            .build()

        fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())

        // Give the GPS/network location provider a moment to deliver a fresh,
        // settled fix before we act on it - the first callback after a cold
        // start is often stale or low-accuracy.
        mainHandler.postDelayed({
            fusedLocationClient.removeLocationUpdates(locationCallback)
            val location = latestLocation
            if (location == null) {
                setBusy(false, getString(R.string.status_no_location))
            } else {
                searchNearbyStops(location)
            }
        }, LOCATION_SETTLE_DELAY_MS)
    }

    private fun searchNearbyStops(location: Location) {
        setBusy(true, getString(R.string.status_searching, selectedMode.label))

        val mode = selectedMode
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    PlacesNearbySearch(BuildConfig.PLACES_API_KEY)
                        .searchNearby(location.latitude, location.longitude, mode)
                }
            }

            result.onSuccess { stops -> onStopsFound(stops, mode) }
            result.onFailure { error ->
                setBusy(false, getString(R.string.status_network_error))
                Toast.makeText(this@MainActivity, error.message ?: "Unknown error", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun onStopsFound(stops: List<TransitStop>, mode: TransitMode) {
        if (stops.isEmpty()) {
            setBusy(false, getString(R.string.status_no_stops, mode.label))
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

        setBusy(false, getString(R.string.status_opening_maps, nearest.name))
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
            switchStopButton.visibility = android.view.View.GONE
            return
        }
        switchStopButton.text = getString(
            R.string.switch_stop_button,
            alternate.name,
            "%.0fm".format(alternate.distanceMetersFromUser)
        )
        switchStopButton.visibility = android.view.View.VISIBLE
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

    private fun resetStopResults() {
        currentStop = null
        alternateStop = null
        switchStopButton.visibility = android.view.View.GONE
    }

    private fun setBusy(busy: Boolean, statusMessage: String) {
        progressBar.visibility = if (busy) android.view.View.VISIBLE else android.view.View.GONE
        findStopButton.isEnabled = !busy
        modeToggleGroup.isEnabled = !busy
        statusText.text = statusMessage
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    companion object {
        private const val LOCATION_UPDATE_INTERVAL_MS = 1000L
        private const val LOCATION_MAX_UPDATES = 5
        // Delay before we act on the location we've gathered, so a cold GPS fix has time to settle.
        private const val LOCATION_SETTLE_DELAY_MS = 2500L
        private const val CLOSE_STOP_GAP_THRESHOLD_METERS = 150.0
    }
}
