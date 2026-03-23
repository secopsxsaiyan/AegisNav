package com.aegisnav.app

import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first

data class USState(
    val name: String,
    val abbr: String,
    val minLat: Double,
    val maxLat: Double,
    val minLon: Double,
    val maxLon: Double
)

object USStates {
    val all = listOf(
        USState("Alabama",        "AL", 30.14,  35.01, -88.47, -84.89),
        USState("Alaska",         "AK", 54.77,  71.35,-179.15, -129.97),
        USState("Arizona",        "AZ", 31.33,  37.00,-114.82, -109.04),
        USState("Arkansas",       "AR", 33.00,  36.50, -94.62,  -89.65),
        USState("California",     "CA", 32.53,  42.01,-124.41, -114.13),
        USState("Colorado",       "CO", 36.99,  41.00,-109.05, -102.04),
        USState("Connecticut",    "CT", 40.95,  42.05, -73.73,  -71.79),
        USState("Delaware",       "DE", 38.45,  39.84, -75.79,  -74.98),
        USState("Florida",        "FL", 24.40,  31.00, -87.63,  -79.97),
        USState("Georgia",        "GA", 30.36,  35.00, -85.61,  -80.84),
        USState("Hawaii",         "HI", 18.91,  22.24,-160.25, -154.81),
        USState("Idaho",          "ID", 41.99,  49.00,-117.24, -111.04),
        USState("Illinois",       "IL", 36.97,  42.51, -91.51,  -87.49),
        USState("Indiana",        "IN", 37.77,  41.77, -88.10,  -84.78),
        USState("Iowa",           "IA", 40.38,  43.50, -96.64,  -90.14),
        USState("Kansas",         "KS", 36.99,  40.00,-102.05,  -94.59),
        USState("Kentucky",       "KY", 36.50,  39.15, -89.57,  -81.96),
        USState("Louisiana",      "LA", 28.93,  33.02, -94.04,  -88.82),
        USState("Maine",          "ME", 43.06,  47.46, -71.08,  -66.95),
        USState("Maryland",       "MD", 37.91,  39.72, -79.49,  -74.98),
        USState("Massachusetts",  "MA", 41.24,  42.89, -73.51,  -69.93),
        USState("Michigan",       "MI", 41.70,  48.19, -90.42,  -82.41),
        USState("Minnesota",      "MN", 43.50,  49.38, -97.24,  -89.49),
        USState("Mississippi",    "MS", 30.17,  35.01, -91.65,  -88.10),
        USState("Missouri",       "MO", 35.99,  40.61, -95.77,  -89.10),
        USState("Montana",        "MT", 44.36,  49.00,-116.05, -104.04),
        USState("Nebraska",       "NE", 40.00,  43.00,-104.05,  -95.31),
        USState("Nevada",         "NV", 35.00,  42.00,-120.00, -114.04),
        USState("New Hampshire",  "NH", 42.70,  45.31, -72.56,  -70.61),
        USState("New Jersey",     "NJ", 38.92,  41.36, -75.56,  -73.89),
        USState("New Mexico",     "NM", 31.33,  37.00,-109.05, -103.00),
        USState("New York",       "NY", 40.49,  45.01, -79.76,  -71.86),
        USState("North Carolina", "NC", 33.84,  36.59, -84.32,  -75.46),
        USState("North Dakota",   "ND", 45.93,  49.00,-104.05,  -96.55),
        USState("Ohio",           "OH", 38.40,  42.32, -84.82,  -80.52),
        USState("Oklahoma",       "OK", 33.62,  37.00,-103.00,  -94.43),
        USState("Oregon",         "OR", 41.99,  46.24,-124.57, -116.46),
        USState("Pennsylvania",   "PA", 39.72,  42.27, -80.52,  -74.69),
        USState("Rhode Island",   "RI", 41.15,  42.02, -71.91,  -71.12),
        USState("South Carolina", "SC", 32.05,  35.22, -83.35,  -78.54),
        USState("South Dakota",   "SD", 42.48,  45.94,-104.06,  -96.44),
        USState("Tennessee",      "TN", 34.98,  36.68, -90.31,  -81.65),
        USState("Texas",          "TX", 25.84,  36.50,-106.65,  -93.51),
        USState("Utah",           "UT", 36.99,  42.00,-114.05, -109.04),
        USState("Vermont",        "VT", 42.73,  45.02, -73.44,  -71.47),
        USState("Virginia",       "VA", 36.54,  39.47, -83.68,  -75.24),
        USState("Washington",     "WA", 45.54,  49.00,-124.73, -116.92),
        USState("West Virginia",  "WV", 37.20,  40.64, -82.64,  -77.72),
        USState("Wisconsin",      "WI", 42.49,  47.08, -92.89,  -86.25),
        USState("Wyoming",        "WY", 40.99,  45.01,-111.06, -104.05),
        USState("Washington DC",  "DC", 38.79,  38.99, -77.12,  -76.91)
    )

    fun byAbbr(abbr: String) = all.firstOrNull { it.abbr == abbr }
    fun forAbbrs(abbrs: Set<String>) = all.filter { it.abbr in abbrs }
}

// DataStore helpers
const val PREFS_STATES = "state_prefs"
const val PREF_SELECTED_STATES = "selected_states"

suspend fun saveSelectedStates(context: android.content.Context, abbrs: Set<String>) {
    val dataStore = com.aegisnav.app.security.SecureDataStore.get(context, PREFS_STATES)
    // Async DataStore read
    dataStore.edit {
        it[androidx.datastore.preferences.core.stringSetPreferencesKey(PREF_SELECTED_STATES)] = abbrs
    }
}

suspend fun loadSelectedStates(context: android.content.Context): Set<String> {
    val dataStore = com.aegisnav.app.security.SecureDataStore.get(context, PREFS_STATES)
    // Async DataStore read
    return dataStore.data.first()[androidx.datastore.preferences.core.stringSetPreferencesKey(PREF_SELECTED_STATES)]
        ?: emptySet()
}
