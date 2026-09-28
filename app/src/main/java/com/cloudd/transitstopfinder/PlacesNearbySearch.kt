package com.cloudd.transitstopfinder

import android.location.Location
import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Thin wrapper around the Places API (New) "Nearby Search" endpoint.
 * https://developers.google.com/maps/documentation/places/web-service/nearby-search
 *
 * We call the HTTP endpoint directly (instead of pulling in the full Places
 * SDK) because the Android Places SDK does not expose a general "nearest
 * place of type X" search - only autocomplete and "find current place".
 */
class PlacesNearbySearch(private val apiKey: String) {

    class PlacesApiException(message: String) : Exception(message)

    /**
     * Returns nearby stops of [mode], sorted by distance from [userLat]/[userLng] (closest first).
     */
    fun searchNearby(
        userLat: Double,
        userLng: Double,
        mode: TransitMode,
        radiusMeters: Double = DEFAULT_RADIUS_METERS,
        maxResults: Int = DEFAULT_MAX_RESULTS
    ): List<TransitStop> {
        val requestBody = JSONObject().apply {
            put("includedTypes", mode.placeTypes)
            put("maxResultCount", maxResults)
            put("rankPreference", "DISTANCE")
            put("locationRestriction", JSONObject().apply {
                put("circle", JSONObject().apply {
                    put("center", JSONObject().apply {
                        put("latitude", userLat)
                        put("longitude", userLng)
                    })
                    put("radius", radiusMeters)
                })
            })
        }

        val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("X-Goog-Api-Key", apiKey)
            setRequestProperty("X-Goog-FieldMask", "places.id,places.displayName,places.location")
        }

        try {
            connection.outputStream.use { it.writeUtf8(requestBody.toString()) }

            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
            val responseBody = stream.bufferedReader().use(BufferedReader::readText)

            if (responseCode !in 200..299) {
                throw PlacesApiException("Places API returned HTTP $responseCode: $responseBody")
            }

            return parseResponse(responseBody, userLat, userLng)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseResponse(json: String, userLat: Double, userLng: Double): List<TransitStop> {
        val places = JSONObject(json).optJSONArray("places") ?: return emptyList()
        val results = mutableListOf<TransitStop>()

        for (i in 0 until places.length()) {
            val place = places.getJSONObject(i)
            val id = place.optString("id")
            val displayName = place.optJSONObject("displayName")?.optString("text") ?: "Unnamed stop"
            val location = place.optJSONObject("location") ?: continue
            val lat = location.optDouble("latitude")
            val lng = location.optDouble("longitude")
            if (id.isEmpty() || lat.isNaN() || lng.isNaN()) continue

            val distanceResult = FloatArray(1)
            Location.distanceBetween(userLat, userLng, lat, lng, distanceResult)

            results.add(TransitStop(id, displayName, lat, lng, distanceResult[0]))
        }

        return results.sortedBy { it.distanceMetersFromUser }
    }

    private fun OutputStream.writeUtf8(text: String) {
        write(text.toByteArray(Charsets.UTF_8))
    }

    companion object {
        private const val ENDPOINT = "https://places.googleapis.com/v1/places:searchNearby"
        const val DEFAULT_RADIUS_METERS = 1500.0
        const val DEFAULT_MAX_RESULTS = 5
    }
}
