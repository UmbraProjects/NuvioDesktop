package com.nuvio.app.features.iptv

import java.util.Locale

/**
 * Regions read off provider category names, for the Live TV tab's flag filter. Providers prefix
 * their categories with a country code in a handful of styles ("UK | Sports", "|US| News",
 * "[DE] Kino", "FR: Info", "USA Movies"); the code becomes an ISO 3166 alpha-2 region.
 *
 * A region is only offered when at least [MIN_CATEGORIES] categories carry it, so a lone
 * "TV Shows" (TV is Tuvalu) does not grow a flag.
 */
object IptvRegions {
    const val MIN_CATEGORIES = 2

    /** Three-letter and colloquial prefixes providers use, to their ISO alpha-2 code. */
    private val aliases = mapOf(
        "UK" to "GB", "USA" to "US", "GBR" to "GB", "ENG" to "GB",
        "GER" to "DE", "DEU" to "DE", "FRA" to "FR", "ESP" to "ES", "SPA" to "ES",
        "ITA" to "IT", "POR" to "PT", "PRT" to "PT", "BRA" to "BR", "CAN" to "CA",
        "NED" to "NL", "NLD" to "NL", "HOL" to "NL", "TUR" to "TR", "POL" to "PL",
        "SWE" to "SE", "NOR" to "NO", "DEN" to "DK", "DNK" to "DK", "FIN" to "FI",
        "IRL" to "IE", "AUS" to "AU", "NZL" to "NZ", "IND" to "IN", "PAK" to "PK",
        "MEX" to "MX", "ARG" to "AR", "BEL" to "BE", "SUI" to "CH", "CHE" to "CH",
        "AUT" to "AT", "GRE" to "GR", "GRC" to "GR", "ROU" to "RO", "ROM" to "RO",
        "RUS" to "RU", "UKR" to "UA", "ALB" to "AL", "BUL" to "BG", "BGR" to "BG",
        "CRO" to "HR", "HRV" to "HR", "SRB" to "RS", "HUN" to "HU", "CZE" to "CZ",
        "SVK" to "SK", "SLO" to "SI", "SVN" to "SI", "JPN" to "JP", "KOR" to "KR",
        "CHN" to "CN", "PHI" to "PH", "PHL" to "PH", "THA" to "TH", "VIE" to "VN",
        "VNM" to "VN", "ZAF" to "ZA", "RSA" to "ZA", "NGA" to "NG", "EGY" to "EG",
        "KSA" to "SA", "SAU" to "SA", "UAE" to "AE", "ARE" to "AE", "ISR" to "IL",
        "IRN" to "IR", "AFG" to "AF", "BAN" to "BD", "BGD" to "BD", "LKA" to "LK",
        "COL" to "CO", "CHI" to "CL", "CHL" to "CL", "PER" to "PE", "VEN" to "VE",
        "LAT" to "LV", "LTU" to "LT", "EST" to "EE", "ISL" to "IS", "LUX" to "LU",
        "MLT" to "MT", "CYP" to "CY", "MAR" to "MA", "TUN" to "TN", "ALG" to "DZ",
        "DZA" to "DZ",
    )

    /** Words that look like a country code at the start of a category but are not one. */
    private val notRegions = setOf("TV", "HD", "SD", "VIP", "PPV", "VOD", "NEW", "THE", "ALL", "MY", "NO", "IN", "TO", "ON", "IS")

    private val isoCountries: Set<String> = Locale.getISOCountries().toHashSet() + "EU"

    // "|UK| News", "[UK] News", "(UK) News", "UK | News", "UK: News", "UK - News", "UK News".
    private val prefix = Regex("""^\s*[|\[(]?\s*([A-Za-z]{2,3})\s*([|\]):\-–•/]|\s)""")

    /** The ISO alpha-2 region a category name starts with, if any. */
    fun regionOf(categoryName: String): String? {
        val match = prefix.find(categoryName) ?: return null
        val raw = match.groupValues[1]
        val separated = match.groupValues[2].isNotBlank() || categoryName.trimStart().startsWith("|") ||
            categoryName.trimStart().startsWith("[") || categoryName.trimStart().startsWith("(")
        // Space-separated, only an all-capitals code counts: "Uk news" is a sentence, "UK News" a prefix.
        if (!separated && raw != raw.uppercase()) return null
        val code = raw.uppercase()
        if (!separated && code in notRegions) return null
        val iso = aliases[code] ?: code.takeIf { it.length == 2 } ?: return null
        return iso.takeIf { it in isoCountries }
    }

    /** Category id to region, for the categories of [content] that have one. */
    fun categoryRegions(content: IptvPlaylistContent): Map<String, String> {
        val found = content.categories.mapNotNull { category -> regionOf(category.name)?.let { category.id to it } }
        val counts = found.groupingBy { it.second }.eachCount()
        return found.filter { (counts[it.second] ?: 0) >= MIN_CATEGORIES }.toMap()
    }

    /** The regions of [content], most categories first. */
    fun regions(categoryRegions: Map<String, String>): List<String> =
        categoryRegions.values.groupingBy { it }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key }

    /** A flag picture for [region]; the Windows emoji font has no flags to draw it with. */
    fun flagUrl(region: String): String = "https://flagcdn.com/w80/${region.lowercase()}.png"

    /** The code shown beside a flag: providers' "UK" rather than ISO's "GB". */
    fun label(region: String): String = if (region == "GB") "UK" else region
}
