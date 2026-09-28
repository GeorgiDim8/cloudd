package com.cloudd.transitstopfinder

/** A single bus or train stop returned by the Places API, with its distance from the user. */
data class TransitStop(
    val placeId: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val distanceMetersFromUser: Float
)

enum class TransitMode(val label: String, val placeTypes: List<String>) {
    BUS("Bus", listOf("bus_station")),
    TRAIN("Train", listOf("train_station", "subway_station", "light_rail_station"))
}
