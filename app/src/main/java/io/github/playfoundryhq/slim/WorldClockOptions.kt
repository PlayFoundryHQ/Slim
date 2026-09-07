package io.github.playfoundryhq.slim

/**
 * Curated list of cities offered for the optional secondary "world clock".
 *
 * Android has no shared, cross-OEM API exposing the world clocks a user has
 * configured in the stock Clock app — that list lives in the Clock app's own
 * private database. So this is Slim's own setting, not read from the device;
 * a short curated list (rather than the full ~450-entry IANA zone database)
 * keeps the picker itself in line with Slim's minimalist design.
 */
object WorldClockOptions {
    data class City(val label: String, val timeZoneId: String)

    val CITIES: List<City> = listOf(
        City("New York", "America/New_York"),
        City("Los Angeles", "America/Los_Angeles"),
        City("Chicago", "America/Chicago"),
        City("Toronto", "America/Toronto"),
        City("Mexico City", "America/Mexico_City"),
        City("São Paulo", "America/Sao_Paulo"),
        City("London", "Europe/London"),
        City("Paris", "Europe/Paris"),
        City("Berlin", "Europe/Berlin"),
        City("Madrid", "Europe/Madrid"),
        City("Rome", "Europe/Rome"),
        City("Amsterdam", "Europe/Amsterdam"),
        City("Moscow", "Europe/Moscow"),
        City("Istanbul", "Europe/Istanbul"),
        City("Cairo", "Africa/Cairo"),
        City("Lagos", "Africa/Lagos"),
        City("Johannesburg", "Africa/Johannesburg"),
        City("Dubai", "Asia/Dubai"),
        City("Tehran", "Asia/Tehran"),
        City("Karachi", "Asia/Karachi"),
        City("New Delhi", "Asia/Kolkata"),
        City("Dhaka", "Asia/Dhaka"),
        City("Bangkok", "Asia/Bangkok"),
        City("Singapore", "Asia/Singapore"),
        City("Hong Kong", "Asia/Hong_Kong"),
        City("Shanghai", "Asia/Shanghai"),
        City("Tokyo", "Asia/Tokyo"),
        City("Seoul", "Asia/Seoul"),
        City("Sydney", "Australia/Sydney"),
        City("Auckland", "Pacific/Auckland"),
    )
}
