package com.aegisnav.app.ui

/**
 * Reportable categories - redesigned set.
 */
enum class ReportCategory(
    val emoji: String,
    val label: String,
    val dbType: String,
    val subtypes: List<String>
) {
    POLICE(
        emoji = "👮",
        label = "Police",
        dbType = "POLICE",
        subtypes = listOf("Right", "Left", "Middle")
    ),
    ALPR(
        emoji = "📷",
        label = "Plate Reader",
        dbType = "ALPR",
        subtypes = listOf("Right", "Left", "Middle")
    ),
    SURVEILLANCE(
        emoji = "🎥",
        label = "Surveillance",
        dbType = "SURVEILLANCE",
        subtypes = listOf("Right", "Left", "Middle")
    ),
    ACCIDENT(
        emoji = "🚨",
        label = "Accident",
        dbType = "ACCIDENT",
        subtypes = listOf("Right", "Left", "Middle")
    ),
    HAZARD(
        emoji = "⚠️",
        label = "Hazard",
        dbType = "HAZARD",
        subtypes = listOf("Object in Road", "Spill", "Debris")
    ),
    WEATHER(
        emoji = "🌧️",
        label = "Weather",
        dbType = "WEATHER",
        subtypes = listOf("Ice", "Flooding", "Fog", "High Winds", "Standing Water")
    ),
    CONSTRUCTION(
        emoji = "🚧",
        label = "Work Zone",
        dbType = "CONSTRUCTION",
        subtypes = emptyList()
    ),
    ROAD_CLOSURE(
        emoji = "🛑",
        label = "Road Block",
        dbType = "ROAD_CLOSURE",
        subtypes = emptyList()
    )
}
