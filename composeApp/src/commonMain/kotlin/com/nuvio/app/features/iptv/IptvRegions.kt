package com.nuvio.app.features.iptv

import java.text.Normalizer
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

    private val isoCountries: Set<String> = Locale.getISOCountries().toHashSet() + "EU" + "XK"

    private val COMBINING_MARKS = Regex("\\p{M}+")
    private val NON_ALNUM = Regex("[^a-z0-9 ]")
    private val MULTI_SPACE = Regex("\\s+")

    /** Lower case, accents and punctuation gone: "Côte d'Ivoire" -> "cote d ivoire". */
    private fun plain(text: String): String {
        val lower = text.lowercase()
        val folded = if (lower.any { it.code >= 128 }) {
            COMBINING_MARKS.replace(Normalizer.normalize(lower, Normalizer.Form.NFD), "")
        } else {
            lower
        }
        val withAnd = if ('&' in folded) folded.replace("&", " and ") else folded
        return MULTI_SPACE.replace(NON_ALNUM.replace(withAnd, " "), " ").trim()
    }

    /**
     * fork:live-tv-triage - countries written out ("Germany | TV", "Costa Rica | TV"): every ISO country's English
     * name, and what providers call the ones whose official name they do not use.
     */
    private val names: Map<String, String> by lazy {
        val out = HashMap<String, String>()
        for (iso in Locale.getISOCountries()) {
            val name = runCatching { Locale.Builder().setRegion(iso).build().getDisplayCountry(Locale.ENGLISH) }.getOrNull() ?: continue
            out[plain(name)] = iso
            // "Hong Kong SAR China", "Myanmar (Burma)", "Congo - Kinshasa": the first part alone too, when free.
            plain(name.substringBefore(" SAR").substringBefore("(").substringBefore(" - ")).takeIf { it.isNotBlank() }?.let { out.putIfAbsent(it, iso) }
        }
        out += mapOf(
            "usa" to "US", "united states of america" to "US", "america" to "US", "us" to "US",
            "uk" to "GB", "great britain" to "GB", "britain" to "GB", "england" to "GB", "scotland" to "GB", "wales" to "GB",
            "northern ireland" to "GB", "czech" to "CZ", "czech republic" to "CZ", "holland" to "NL", "korea" to "KR",
            "south korea" to "KR", "north korea" to "KP", "russia" to "RU", "turkey" to "TR", "turkiye" to "TR",
            "ivory coast" to "CI", "hong kong" to "HK", "macau" to "MO", "palestine" to "PS", "bosnia" to "BA",
            "bosnia and herzegovina" to "BA", "macedonia" to "MK", "north macedonia" to "MK", "uae" to "AE", "emirates" to "AE",
            "saudi" to "SA", "saudi arabia" to "SA", "burma" to "MM", "myanmar" to "MM", "cape verde" to "CV", "vatican" to "VA",
            "kosovo" to "XK", "taiwan" to "TW", "vietnam" to "VN", "iran" to "IR", "syria" to "SY", "laos" to "LA",
            "moldova" to "MD", "latvia" to "LV", "slovakia" to "SK", "slovenia" to "SI", "dominican rep" to "DO",
        )
        out
    }

    /** Country names that are also ordinary words, only taken as a country before a separator ("Jersey | TV"). */
    private val wordLike = setOf("jersey", "georgia", "jordan", "chad", "turkey", "guernsey", "niger", "guinea", "america", "us", "uk", "man")

    // The text before the first separator: "Costa Rica" of "Costa Rica | TV", "Mexico" of "[Mexico] Cine".
    private val lead = Regex("""^\s*[|\[(]?\s*([^|\[\]():\-–•/]+?)\s*(?:[|\]):\-–•/]|$)""")

    /** fork:live-tv-triage - the country a category name spells out, if it starts with one. */
    fun regionByName(categoryName: String): String? {
        val match = lead.find(categoryName)
        val before = match?.groupValues?.get(1)?.let(::plain).orEmpty()
        // Written before a separator: the whole of it must be a country.
        if (match != null && match.value.trimEnd().let { v -> v.isNotEmpty() && v.last() in "|]):-–•/" }) {
            names[before]?.let { return it }
        }
        // The whole name is a country ("Germany").
        val plainFull = plain(categoryName)
        if (before.isNotEmpty() && before == plainFull && before !in wordLike) names[before]?.let { return it }
        // Space-separated ("Germany Sports"): the longest run of leading words that is a country, not an ordinary word.
        val words = plainFull.split(' ').filter { it.isNotEmpty() }
        for (n in minOf(4, words.size - 1) downTo 1) {
            val candidate = words.take(n).joinToString(" ")
            if (candidate.length < 4 || candidate in wordLike) continue
            names[candidate]?.let { return it }
        }
        return null
    }

    // "|UK| News", "[UK] News", "(UK) News", "UK | News", "UK: News", "UK - News", "UK News".
    private val prefix = Regex("""^\s*[|\[(]?\s*([A-Za-z]{2,3})\s*([|\]):\-–•/]|\s)""")

    /** The ISO alpha-2 region a category name starts with, if any: a country written out, or a code. */
    fun regionOf(categoryName: String): String? = regionByName(categoryName) ?: regionByCode(categoryName)

    private fun regionByCode(categoryName: String): String? {
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
        // A country written out is sure on its own ("Costa Rica | TV" may be its only category); a bare code needs company.
        val named = content.categories.mapNotNull { category -> regionByName(category.name)?.let { category.id to it } }.toMap()
        val coded = content.categories.filter { it.id !in named }.mapNotNull { category -> regionByCode(category.name)?.let { category.id to it } }
        val counts = (named.values + coded.map { it.second }).groupingBy { it }.eachCount()
        return named + coded.filter { (counts[it.second] ?: 0) >= MIN_CATEGORIES }
    }

    /** The regions of [content], most categories first. */
    fun regions(categoryRegions: Map<String, String>): List<String> =
        categoryRegions.values.groupingBy { it }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key }

    /** A flag picture for [region]; the Windows emoji font has no flags to draw it with. */
    /** fork:live-tv-polish - the countries offered for Settings > Live TV > Home country, in this order. */
    val HOME_CHOICES = listOf("GB", "IE", "US", "CA", "AU", "NZ", "DE", "FR", "ES", "IT", "NL", "PT", "PL", "IN")

    /** "United Kingdom" for "GB". */
    fun countryName(region: String): String =
        runCatching { Locale.Builder().setRegion(region).build().getDisplayCountry(Locale.ENGLISH) }.getOrNull()?.ifBlank { null } ?: region

    /**
     * fork:live-tv-polish - how much a programme gains in the picks for where its channel is from, given the viewer's
     * [home] country: home channels rank higher, and news from another country (News 12 Brooklyn, Australian breakfast
     * news) drops right down. No home, or a channel whose country is unknown: nothing changes.
     */
    fun homeBonus(region: String?, home: String?, kind: String?): Int = when {
        home == null || region == null -> 0
        region == home -> HOME_BONUS
        kind == IptvProgrammeKind.NEWS -> -FOREIGN_NEWS_PENALTY
        else -> 0
    }

    const val HOME_BONUS = 20
    const val FOREIGN_NEWS_PENALTY = 40

    /** The home country in use: the one set in Settings, else the triage's only country when it kept just one. */
    fun effectiveHome(setting: String?, triageRegions: Set<String>?): String? =
        setting ?: triageRegions?.filter { it.length == 2 }?.singleOrNull()

    fun flagUrl(region: String): String = "https://flagcdn.com/w80/${region.lowercase()}.png"

    /** The code shown beside a flag: providers' "UK" rather than ISO's "GB". */
    fun label(region: String): String = if (region == "GB") "UK" else region
}
