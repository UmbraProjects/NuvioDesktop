package com.nuvio.app.features.iptv

import java.text.Normalizer

/**
 * fork:live-tv-triage - what language a channel is in, read off its name, its category's name, and its country:
 * "US: Univision" is Spanish though the US is English, "UK | Asian" holds Zee TV (South Asian) beside channels in
 * English, and a Canadian "TVA" is French. The name decides first, then the category, then the country's own
 * language. [OTHER] is a channel whose country has no language here and whose names say none.
 */
object IptvLanguages {
    const val OTHER = "other"

    /** Codes in the order the triage asks about them, with what the question shows. */
    val LABELS: Map<String, String> = linkedMapOf(
        "en" to "English",
        "es" to "Spanish",
        "fr" to "French",
        "pt" to "Portuguese",
        "de" to "German",
        "it" to "Italian",
        "nl" to "Dutch",
        "pl" to "Polish",
        "ru" to "Russian",
        "tr" to "Turkish",
        "ar" to "Arabic",
        "sa" to "South Asian languages",
        "zh" to "Chinese",
        "ko" to "Korean",
        "vi" to "Vietnamese",
        "tl" to "Filipino",
        "el" to "Greek",
        "ro" to "Romanian",
        "sv" to "Nordic languages",
        "cs" to "Czech and Slovak",
        "hu" to "Hungarian",
        "sq" to "Albanian",
        "yu" to "Balkan languages",
        "bg" to "Bulgarian",
        "uk" to "Ukrainian",
        "he" to "Hebrew",
        "fa" to "Persian",
        "ja" to "Japanese",
        "th" to "Thai",
        "ms" to "Malay and Indonesian",
        OTHER to "Other or unknown",
    )

    fun label(code: String): String = LABELS[code] ?: code

    private val COUNTRY: Map<String, String> = buildMap {
        listOf("GB", "US", "CA", "AU", "NZ", "IE", "ZA", "JM", "TT", "NG", "KE", "GH", "SG").forEach { put(it, "en") }
        listOf("ES", "MX", "AR", "CO", "CL", "PE", "VE", "EC", "UY", "PY", "BO", "CR", "PA", "DO", "GT", "HN", "SV", "NI", "PR", "CU").forEach { put(it, "es") }
        listOf("FR", "BE", "LU", "MC").forEach { put(it, "fr") }
        listOf("PT", "BR", "AO", "MZ").forEach { put(it, "pt") }
        listOf("DE", "AT", "CH").forEach { put(it, "de") }
        put("IT", "it"); put("NL", "nl"); put("PL", "pl"); put("RU", "ru"); put("TR", "tr"); put("GR", "el"); put("CY", "el"); put("RO", "ro")
        listOf("SE", "NO", "DK", "FI", "IS").forEach { put(it, "sv") }
        listOf("SA", "AE", "EG", "QA", "KW", "BH", "OM", "JO", "LB", "IQ", "MA", "DZ", "TN", "LY", "SY", "YE", "PS").forEach { put(it, "ar") }
        listOf("IN", "PK", "BD", "LK", "NP").forEach { put(it, "sa") }
        listOf("CN", "HK", "TW", "MO").forEach { put(it, "zh") }
        put("KR", "ko"); put("VN", "vi"); put("PH", "tl")
        put("CZ", "cs"); put("SK", "cs"); put("HU", "hu"); put("AL", "sq"); put("XK", "sq"); put("BG", "bg"); put("UA", "uk")
        listOf("RS", "HR", "BA", "ME", "MK", "SI").forEach { put(it, "yu") }
        put("IL", "he"); put("IR", "fa"); put("AF", "fa"); put("JP", "ja"); put("TH", "th"); put("ID", "ms"); put("MY", "ms")
    }

    /** The country's own language, null for a country not listed (or "no country"). */
    fun ofCountry(region: String?): String? = region?.let { COUNTRY[it.uppercase()] }

    private fun phrases(vararg words: String) = Regex("""\b(?:${words.joinToString("|") { Regex.escape(it) }})\b""")

    /** Checked in this order; the first that matches decides. English is first so "Al Jazeera English" stays English. */
    private val MARKERS: List<Pair<String, Regex>> = listOf(
        "en" to phrases("english", "eng"),
        "es" to phrases(
            "spanish", "espanol", "latino", "latina", "latinos", "latam", "hispanic", "hispano", "univision", "unimas", "telemundo",
            "galavision", "estrella", "azteca", "tudn", "universo", "deportes", "en espanol", "noticias", "novelas", "telenovelas",
            "cinelatino", "cine latino", "cine mexicano", "pasiones", "sorpresa", "discovery familia", "nat geo mundo", "tr3s", "vme",
            "wapa", "multimedios", "caracol", "teleformula", "telemicro", "canal sur", "mega tv", "america teve", "vix", "las estrellas",
            "de pelicula", "tlnovelas", "bandamax", "telehit", "ritmoson", "htv", "nuestra tele", "ecuavisa", "teleamazonas",
        ),
        "fr" to phrases(
            "french", "francais", "francaise", "tva", "radio canada", "ici rdi", "ici tele", "tele quebec", "rds", "tfo", "noovo",
            "canal d", "canal vie", "z tele", "super ecran", "cinepop", "vrak", "telemagino", "tv5", "tv5monde", "canal plus",
        ),
        "pt" to phrases("portuguese", "portugues", "brazil", "brasil", "brazilian", "globo", "record tv", "sbt", "rtp", "sic", "tvi"),
        "de" to phrases("german", "deutsch", "deutschland"),
        "it" to phrases("italian", "italiano", "italia", "rai", "mediaset"),
        "nl" to phrases("dutch", "nederlands"),
        "pl" to phrases("polish", "polski", "polsat", "tvp"),
        "ru" to phrases("russian", "russkiy"),
        "tr" to phrases("turkish", "turk", "turkce"),
        "ar" to phrases("arabic", "arab", "mbc", "rotana", "osn arabic", "shahid"),
        "sa" to phrases(
            "hindi", "punjabi", "urdu", "tamil", "telugu", "bengali", "bangla", "malayalam", "marathi", "gujarati", "kannada", "desi",
            "zee", "star plus", "star bharat", "sony sab", "sony set", "colors tv", "aaj tak", "ptc", "geo tv", "geo news", "ary", "hum tv",
            "b4u", "sun tv", "asianet", "jalsha", "aastha", "sanskar",
        ),
        "zh" to phrases("chinese", "mandarin", "cantonese", "cctv", "tvb", "cti", "ttv", "ctv asia"),
        "ko" to phrases("korean", "kbs", "jtbc", "mbn"),
        "vi" to phrases("vietnamese", "vtv", "saigon", "little saigon"),
        "tl" to phrases("filipino", "tagalog", "pinoy", "tfc", "abs cbn", "gma"),
        "el" to phrases("greek", "ert"),
        "ro" to phrases("romanian", "romania"),
        "sv" to phrases("swedish", "norwegian", "danish", "finnish", "nordic", "svt", "nrk", "dr1", "yle"),
    )

    private val COMBINING_MARKS = Regex("\\p{M}+")
    private val NON_ALNUM_PLUS = Regex("[^a-z0-9+ ]")
    private val MULTI_SPACE = Regex("\\s+")
    private val COUNTRY_PREFIX = Regex("""^\s*[|\[(]?\s*[A-Za-z]{2,3}\s*[|\])\]:-]+\s*""")

    /** Lower case, accents and punctuation gone: "Telemundo (Español)" -> "telemundo espanol". */
    private fun plain(text: String): String {
        val lower = text.lowercase()
        val folded = if (lower.any { it.code >= 128 }) {
            COMBINING_MARKS.replace(Normalizer.normalize(lower, Normalizer.Form.NFD), "")
        } else {
            lower
        }
        val withAnd = if ('&' in folded) folded.replace("&", " and ") else folded
        return MULTI_SPACE.replace(NON_ALNUM_PLUS.replace(withAnd, " "), " ").trim()
    }

    /** The language [text] names outright, if it names one. */
    fun named(text: String): String? {
        val p = " ${plain(text)} "
        return MARKERS.firstOrNull { (_, regex) -> regex.containsMatchIn(p) }?.first
    }

    /** A channel's language: its own name, then its category's, then its country's; [OTHER] when nothing says. */
    fun ofChannel(channelName: String, categoryName: String, region: String?): String =
        ofChannelResolved(channelName, named(categoryName), ofCountry(region))

    /** Same as [ofChannel], reusing precomputed category and region fallbacks across a category's channels. */
    internal fun ofChannelResolved(
        channelName: String,
        categoryLanguage: String?,
        regionLanguage: String?,
    ): String = named(stripCountry(channelName)) ?: categoryLanguage ?: regionLanguage ?: OTHER

    /** "US: ESPN" -> "ESPN", "|UK| BBC One" -> "BBC One": the country prefix is not a language ("ES" is Spain, here). */
    private fun stripCountry(name: String): String = COUNTRY_PREFIX.replace(name, "")
}
