# Transit Stop Finder

An Android app that finds the bus or train stop closest to your current
location and opens it directly in the Google Maps app, so you can see the
live/scheduled departures Maps shows for that stop.

## How it works

1. Tap **Find Nearest Stop** (or launch the app - it defaults to Bus mode).
2. The app requests a fresh GPS/network location fix and waits ~2.5 seconds
   for it to settle (a cold location fix is often inaccurate for the first
   second or two), taking the most recently updated reading.
3. It calls the [Places API (New) "Nearby Search"](https://developers.google.com/maps/documentation/places/web-service/nearby-search)
   endpoint, restricted to `bus_station` (Bus mode) or `train_station` /
   `subway_station` / `light_rail_station` (Train mode), sorted by distance.
4. It opens the closest match directly in the Google Maps app via an
   `ACTION_VIEW` intent pointed at that place's `place_id`, so Maps shows
   the stop's own page (and its live departures, where Maps has that data
   for the region).
5. If the second-closest stop is nearly as close as the first (within
   ~150 m of the user, or within ~150 m of the first stop itself - e.g. two
   stops on opposite sides of the same street), a **"Switch to ..."**
   button appears so you can flip straight to the other one without
   re-searching.

There's no public API for Google Maps' live transit timetables themselves,
so this app does the next best thing: it finds the right stop for you and
hands off to Maps' own place page, which is where that departure data
actually lives.

## Project layout

Standard single-module Android app, written in Kotlin:

- `app/src/main/java/com/cloudd/transitstopfinder/MainActivity.kt` - UI,
  location handling with the settle delay, and the switch-stop logic.
- `app/src/main/java/com/cloudd/transitstopfinder/PlacesNearbySearch.kt` -
  minimal HTTP client for the Places API (New) Nearby Search endpoint.
- `app/src/main/java/com/cloudd/transitstopfinder/TransitStop.kt` - data
  model and the `TransitMode` (Bus/Train) enum with its Places API types.
- `app/src/main/res/layout/activity_main.xml` - the single screen: mode
  toggle, find-stop button, status text, and the alternate-stop button.

## Setup

1. **Get a Places API key.** In the
   [Google Cloud Console](https://console.cloud.google.com/), create (or
   reuse) a project, enable **Places API (New)**, and create an API key.
   For production use, restrict the key to Android apps (by package name
   `com.cloudd.transitstopfinder` + your signing certificate's SHA-1) and
   to the Places API.
2. Copy `local.properties.example` to `local.properties` (git-ignored) and
   set:
   ```properties
   sdk.dir=/path/to/your/Android/sdk
   MAPS_API_KEY=your-api-key-here
   ```
3. Open the project in Android Studio (Iguana or newer) and let it sync -
   Android Studio will generate the Gradle wrapper on first sync if it's
   missing. It reads `MAPS_API_KEY` from `local.properties` at build time
   and compiles it into `BuildConfig.PLACES_API_KEY`, so the key is never
   committed to source control.
4. Run on a device or emulator that has the **Google Maps app** installed
   (required, since a real device/Play emulator image is needed - the app
   launches Maps via an explicit package intent).

## Permissions

The app requests `ACCESS_FINE_LOCATION` at runtime the first time you tap
**Find Nearest Stop**. Location is only used locally to build the Places
API request - it isn't stored or sent anywhere except to Google's Places
endpoint as your search coordinates.

## Notes / limitations

- This was built and reviewed outside of Android Studio (no Android SDK or
  access to Google's Maven repo in this environment), so it hasn't been
  compiled here - please do a first build/run in Android Studio to confirm
  your API key and SDK setup before relying on it.
- Whether Google Maps actually shows live departure times on a stop's page
  depends on transit data availability in your city/region - the app
  guarantees it opens the *correct nearest stop*, not that Maps has
  real-time data for it.
- The "two very close stops" threshold (150 m) is a constant
  (`CLOSE_STOP_GAP_THRESHOLD_METERS` in `MainActivity.kt`) you can tune.
