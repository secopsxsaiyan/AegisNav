package com.aegisnav.app

import java.time.ZonedDateTime
import kotlin.math.*

/**
 * NOAA solar algorithm for computing sunrise/sunset times.
 * Pure Kotlin - no Android or external dependencies.
 */
object SunriseSunset {

    fun isNighttime(
        lat: Double,
        lon: Double,
        now: ZonedDateTime = ZonedDateTime.now()
    ): Boolean {
        // Julian day number
        val jd = toJulianDay(now)

        // Julian century
        val t = (jd - 2451545.0) / 36525.0

        // Geometric mean longitude (degrees)
        val l0 = (280.46646 + t * (36000.76983 + t * 0.0003032)) % 360.0

        // Geometric mean anomaly (degrees)
        val m = 357.52911 + t * (35999.05029 - 0.0001537 * t)
        val mRad = toRad(m)

        // Equation of center
        val c = sin(mRad) * (1.914602 - t * (0.004817 + 0.000014 * t)) +
                sin(2 * mRad) * (0.019993 - 0.000101 * t) +
                sin(3 * mRad) * 0.000289

        // Sun's true longitude (degrees)
        val sunLon = l0 + c

        // Apparent longitude (correct for aberration)
        val omega = 125.04 - 1934.136 * t
        val lambda = sunLon - 0.00569 - 0.00478 * sin(toRad(omega))

        // Obliquity of ecliptic (degrees)
        val epsilon0 = 23.0 + (26.0 + (21.448 - t * (46.8150 + t * (0.00059 - t * 0.001813))) / 60.0) / 60.0
        val epsilon = epsilon0 + 0.00256 * cos(toRad(omega))

        // Solar declination (degrees)
        val decl = toDeg(asin(sin(toRad(epsilon)) * sin(toRad(lambda))))

        // Equation of time (minutes)
        val y = tan(toRad(epsilon / 2)).let { it * it }
        val l0Rad = toRad(l0)
        val eot = 4.0 * toDeg(
            y * sin(2 * l0Rad) -
            2 * sin(mRad) * 0.016708634 +
            4 * sin(mRad) * 0.016708634 * y * cos(2 * l0Rad) -
            0.5 * y * y * sin(4 * l0Rad) -
            1.25 * 0.016708634 * 0.016708634 * sin(2 * mRad)
        )

        // Hour angle for zenith = 90.833 degrees
        val zenith = 90.833
        val cosHA = (cos(toRad(zenith)) /
                (cos(toRad(lat)) * cos(toRad(decl)))) -
                tan(toRad(lat)) * tan(toRad(decl))

        // Polar edge cases
        if (cosHA < -1.0) return true  // polar night - never rises
        if (cosHA > 1.0) return false  // midnight sun - never sets

        val haDeg = toDeg(acos(cosHA))

        // Solar noon in minutes from midnight UTC
        val solarNoonMin = (720.0 - 4.0 * lon - eot) + now.offset.totalSeconds / 60.0

        val sunriseMin = solarNoonMin - haDeg * 4.0
        val sunsetMin  = solarNoonMin + haDeg * 4.0

        // Current time in minutes from midnight local time
        val nowMin = now.hour * 60.0 + now.minute + now.second / 60.0

        return nowMin < sunriseMin || nowMin > sunsetMin
    }

    private fun toJulianDay(dt: ZonedDateTime): Double {
        var y = dt.year
        var m = dt.monthValue
        val d = dt.dayOfMonth +
                dt.hour / 24.0 +
                dt.minute / 1440.0 +
                dt.second / 86400.0

        if (m <= 2) { y -= 1; m += 12 }
        val a = (y / 100)
        val b = 2 - a + (a / 4)
        return (365.25 * (y + 4716)).toInt() + (30.6001 * (m + 1)).toInt() + d + b - 1524.5
    }

    private fun toRad(deg: Double) = deg * PI / 180.0
    private fun toDeg(rad: Double) = rad * 180.0 / PI
}
