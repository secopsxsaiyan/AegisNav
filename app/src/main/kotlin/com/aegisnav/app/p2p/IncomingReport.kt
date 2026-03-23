package com.aegisnav.app.p2p

data class IncomingReport(
    val lat: Double,
    val lon: Double,
    val type: String,
    val subtype: String,
    val description: String,
    val threatLevel: String,
    val timestamp: Long,
    val sourceNodeId: String,
    val voteCount: Int = 1
)
