package com.nuvio.app.features.details

/** Where a title is in its run, as shown on the details hero's status line. */
internal enum class DetailStatus { Airing, Ended, Cancelled, Upcoming }

/** What kind of title it is; drawn as "Japanese Series", "Limited Series", "French Film". */
internal enum class DetailForm { Series, LimitedSeries, Film }

/**
 * The caption under the details hero's ratings: "Airing · Japanese Series", "British Limited
 * Series", "Cancelled after 2 seasons". [form] is null when it would say nothing on its own (a
 * series with no known country, an English-language film). [country] is what the form string takes:
 * the adjective in English ("Australian"), the country's name in other languages ("Australien").
 */
internal data class DetailStatusLine(
    val status: DetailStatus?,
    val form: DetailForm?,
    val country: String?,
    val cancelledAfterSeasons: Int? = null,
    /** Films only: "Christopher Nolan", or two co-directors joined ("Joel Coen & Ethan Coen"). */
    val directors: String? = null,
)

/** Display name for an ISO 3166 alpha-2 or alpha-3 code ("GB", "GBR", or "UK") in the app's language; null when unknown. */
internal expect fun countryDisplayName(isoCode: String): String?

/**
 * ISO 3166 alpha-2 for what a catalogue calls a country: an alpha-2 or alpha-3 code in any case,
 * "UK", or an English name ("United States"). Null when it is none of those.
 */
internal expect fun countryIsoAlpha2(country: String): String?

/**
 * How the status line names [country]: the English adjective when the app is in English
 * ("Australian", for "Australian Series"), otherwise the country's name in the app's language,
 * which the other languages' strings put in brackets ("Serie (Australien)").
 */
internal fun countryLabel(
    country: String,
    language: String = androidx.compose.ui.text.intl.Locale.current.language,
): String? {
    val alpha2 = countryIsoAlpha2(country) ?: return null
    if (language.equals("en", ignoreCase = true)) englishCountryAdjectives[alpha2]?.let { return it }
    return countryDisplayName(alpha2)
}

internal fun buildDetailStatusLine(
    meta: MetaDetails,
    countryName: (String) -> String? = { countryLabel(it) },
): DetailStatusLine? {
    val country = primaryCountryName(meta.country, countryName)
    return if (isTvSeriesType(meta.type)) seriesStatusLine(meta, country) else filmStatusLine(meta, country)
}

private fun seriesStatusLine(meta: MetaDetails, country: String?): DetailStatusLine? {
    val status = seriesStatus(meta.status)
    val seasons = meta.videos.mapNotNull { it.season }.filter { it > 0 }.distinct().size
    val episodes = meta.videos.count { (it.season ?: 0) > 0 && (it.episode ?: 0) > 0 }
    val tmdbType = meta.seriesType?.trim()?.lowercase()
    val limited = tmdbType == "miniseries" ||
        meta.hasLimitedSeriesSignal() ||
        // Addon-only records carry no TMDB type: one short, finished season reads as limited.
        (tmdbType == null && status == DetailStatus.Ended && seasons == 1 && episodes in 1..12)
    val form = when {
        limited -> DetailForm.LimitedSeries
        country != null -> DetailForm.Series
        else -> null
    }
    // A limited series is finished by definition, so "Ended" only repeats it.
    val shownStatus = status.takeUnless { limited && it == DetailStatus.Ended }
    if (shownStatus == null && form == null) return null
    return DetailStatusLine(
        status = shownStatus,
        form = form,
        country = country,
        cancelledAfterSeasons = seasons.takeIf { shownStatus == DetailStatus.Cancelled && it > 0 },
    )
}

private fun filmStatusLine(meta: MetaDetails, country: String?): DetailStatusLine? {
    val status = when (meta.status?.trim()?.lowercase()) {
        "in production", "post production", "post-production", "planned", "rumored", "announced" ->
            DetailStatus.Upcoming
        "canceled", "cancelled" -> DetailStatus.Cancelled
        else -> null
    }
    // "Film from the United States" on an English film says nothing; foreign films get the line.
    val form = DetailForm.Film.takeIf { country != null && !meta.isEnglishLanguage() }
    // Gives almost every film a line, so a film's hero is laid out like a show's.
    val directors = meta.director.map(String::trim).filter(String::isNotBlank).distinct()
        .take(2).joinToString(" & ").ifBlank { null }
    if (status == null && form == null && directors == null) return null
    return DetailStatusLine(status = status, form = form, country = country.takeIf { form != null }, directors = directors)
}

private fun seriesStatus(raw: String?): DetailStatus? {
    val s = raw?.trim()?.lowercase()?.takeIf(String::isNotBlank) ?: return null
    return when {
        "returning" in s || s == "airing" || s == "continuing" || s == "current" -> DetailStatus.Airing
        "cancel" in s -> DetailStatus.Cancelled
        s == "ended" || s == "finished" || s == "complete" || s == "completed" -> DetailStatus.Ended
        s in setOf("in production", "post production", "post-production", "planned", "pilot", "rumored", "upcoming", "not yet aired") ->
            DetailStatus.Upcoming
        else -> null
    }
}

private fun MetaDetails.hasLimitedSeriesSignal(): Boolean =
    allKeywords().any { keyword ->
        val k = keyword.trim().lowercase()
        "miniseries" in k || "mini-series" in k || "limited series" in k || "limited-series" in k
    } || genres.any { genre ->
        genre.trim().lowercase() in setOf("miniseries", "mini-series", "limited series")
    }

private fun MetaDetails.isEnglishLanguage(): Boolean =
    language?.trim()?.lowercase() in setOf("en", "eng", "english")

/** First of TMDB's comma-joined ISO codes ("US, GB") or an addon's free text ("USA, Canada"). */
private fun primaryCountryName(raw: String?, countryName: (String) -> String?): String? {
    val first = raw?.split(',', '/', '|')?.firstOrNull()?.trim()?.takeIf(String::isNotBlank) ?: return null
    // Codes come as TMDB's alpha-2 ("GB") or some addons' lowercase alpha-3 ("gbr", "aus", "usa");
    // names as addon free text ("United States").
    return if (first.length in 2..3 && first.all(Char::isLetter)) {
        countryName(first.uppercase()) ?: first.uppercase()
    } else {
        countryName(first) ?: first
    }
}

/** English adjectives for the countries titles commonly come from, by ISO alpha-2. */
private val englishCountryAdjectives = mapOf(
    "US" to "American", "GB" to "British", "AU" to "Australian", "CA" to "Canadian", "NZ" to "New Zealand",
    "IE" to "Irish", "DE" to "German", "FR" to "French", "IT" to "Italian", "ES" to "Spanish",
    "PT" to "Portuguese", "NL" to "Dutch", "BE" to "Belgian", "CH" to "Swiss", "AT" to "Austrian",
    "LU" to "Luxembourgish", "SE" to "Swedish", "NO" to "Norwegian", "DK" to "Danish", "FI" to "Finnish",
    "IS" to "Icelandic", "PL" to "Polish", "CZ" to "Czech", "SK" to "Slovak", "HU" to "Hungarian",
    "RO" to "Romanian", "BG" to "Bulgarian", "GR" to "Greek", "HR" to "Croatian", "RS" to "Serbian",
    "SI" to "Slovenian", "BA" to "Bosnian", "EE" to "Estonian", "LV" to "Latvian", "LT" to "Lithuanian",
    "UA" to "Ukrainian", "RU" to "Russian", "GE" to "Georgian", "TR" to "Turkish", "IL" to "Israeli",
    "IR" to "Iranian", "LB" to "Lebanese", "EG" to "Egyptian", "MA" to "Moroccan", "SA" to "Saudi",
    "AE" to "Emirati", "QA" to "Qatari", "JP" to "Japanese", "KR" to "Korean", "KP" to "North Korean",
    "CN" to "Chinese", "HK" to "Hong Kong", "TW" to "Taiwanese", "MN" to "Mongolian", "IN" to "Indian",
    "PK" to "Pakistani", "BD" to "Bangladeshi", "LK" to "Sri Lankan", "NP" to "Nepali", "TH" to "Thai",
    "VN" to "Vietnamese", "PH" to "Filipino", "ID" to "Indonesian", "MY" to "Malaysian", "SG" to "Singaporean",
    "KZ" to "Kazakh", "MX" to "Mexican", "BR" to "Brazilian", "AR" to "Argentine", "CL" to "Chilean",
    "CO" to "Colombian", "PE" to "Peruvian", "VE" to "Venezuelan", "UY" to "Uruguayan", "CU" to "Cuban",
    "PR" to "Puerto Rican", "JM" to "Jamaican", "ZA" to "South African", "NG" to "Nigerian", "KE" to "Kenyan",
    "GH" to "Ghanaian", "SN" to "Senegalese",
)
