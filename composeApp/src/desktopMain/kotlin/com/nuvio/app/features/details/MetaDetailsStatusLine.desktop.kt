package com.nuvio.app.features.details

import java.util.Locale

/** Alpha-3 ("GBR") to alpha-2 ("GB"); "UK" is not ISO but is what everyone writes. */
private val alpha2ByAlpha3: Map<String, String> by lazy {
    Locale.getISOCountries().associateBy { code ->
        runCatching { Locale.Builder().setRegion(code).build().isO3Country }.getOrDefault("")
    } + ("UK" to "GB")
}

/** English country names ("united states") to alpha-2, for addons that write the name out. */
private val alpha2ByEnglishName: Map<String, String> by lazy {
    Locale.getISOCountries().associateBy { code ->
        runCatching { Locale.Builder().setRegion(code).build().getDisplayCountry(Locale.ENGLISH).lowercase() }.getOrDefault("")
    } + mapOf("usa" to "US", "america" to "US", "united states of america" to "US", "england" to "GB", "great britain" to "GB")
}

private val isoAlpha2Codes: Set<String> by lazy { Locale.getISOCountries().toSet() }

internal actual fun countryIsoAlpha2(country: String): String? {
    val trimmed = country.trim()
    if (trimmed.isEmpty()) return null
    val code = trimmed.uppercase()
    if (code.length == 2 && code in isoAlpha2Codes) return code
    alpha2ByAlpha3[code]?.let { return it }
    return alpha2ByEnglishName[trimmed.lowercase()]
}

// ThemeSettingsStorage applies the app's language through Locale.setDefault, so the default
// locale is the one to name countries in.
internal actual fun countryDisplayName(isoCode: String): String? {
    val alpha2 = countryIsoAlpha2(isoCode) ?: return null
    return runCatching { Locale.Builder().setRegion(alpha2).build() }.getOrNull()
        ?.getDisplayCountry(Locale.getDefault())
        ?.takeIf { it.isNotBlank() && !it.equals(alpha2, ignoreCase = true) }
}
