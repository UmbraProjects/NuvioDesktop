package com.nuvio.app.features.iptv

/**
 * fork:live-tv-stage - guide text made presentable: a title split from its episode number and its "New" / "Live"
 * marks, an XMLTV episode number read, a description cut to a few lines, and the channels and programmes that are
 * filler (24/7 loops, radio, shopping) told apart. Pure; tested by IptvGuideTextTest.
 */
data class IptvCleanTitle(val name: String, val episode: String?, val isNew: Boolean, val isLive: Boolean)

object IptvGuideText {
    const val FLAG_NEW = 1
    const val FLAG_LIVE = 2
    const val DESC_MAX = 180

    // Modifier (superscript) letters some guides write "New" and "Live" in: ᴺᵉʷ, ᴸⁱᵛᵉ.
    private val SUPER = mapOf(
        'ᴬ' to 'a', 'ᴮ' to 'b', 'ᴰ' to 'd', 'ᴱ' to 'e', 'ᴳ' to 'g', 'ᴴ' to 'h', 'ᴵ' to 'i', 'ᴶ' to 'j', 'ᴷ' to 'k',
        'ᴸ' to 'l', 'ᴹ' to 'm', 'ᴺ' to 'n', 'ᴼ' to 'o', 'ᴾ' to 'p', 'ᴿ' to 'r', 'ᵀ' to 't', 'ᵁ' to 'u', 'ⱽ' to 'v', 'ᵂ' to 'w',
        'ᵃ' to 'a', 'ᵇ' to 'b', 'ᶜ' to 'c', 'ᵈ' to 'd', 'ᵉ' to 'e', 'ᶠ' to 'f', 'ᵍ' to 'g', 'ʰ' to 'h', 'ⁱ' to 'i', 'ʲ' to 'j',
        'ᵏ' to 'k', 'ˡ' to 'l', 'ᵐ' to 'm', 'ⁿ' to 'n', 'ᵒ' to 'o', 'ᵖ' to 'p', 'ʳ' to 'r', 'ˢ' to 's', 'ᵗ' to 't', 'ᵘ' to 'u',
        'ᵛ' to 'v', 'ʷ' to 'w', 'ˣ' to 'x', 'ʸ' to 'y', 'ᶻ' to 'z',
    )
    private val EPISODE = Regex("""\s*[-–:|]?\s*[(\[]?\s*S(\d{1,2})\s*[ .]?\s*E(\d{1,3})\s*[)\]]?\s*$""", RegexOption.IGNORE_CASE)
    private val NEW_PREFIX = Regex("""^(new|live)\s*[:!-]\s*""", RegexOption.IGNORE_CASE)
    private val MARK_SUFFIX = Regex("""\s*[(\[](new|live)[)\]]\s*$""", RegexOption.IGNORE_CASE)
    private val SPACES = Regex("""\s+""")

    fun clean(raw: String): IptvCleanTitle {
        var title = raw.trim()
        var isNew = false
        var isLive = false
        // Trailing superscript words: "Soccer Saturday ᴸⁱᵛᵉ".
        while (true) {
            val cut = title.lastIndexOf(' ')
            val word = if (cut < 0) title else title.substring(cut + 1)
            if (word.isEmpty() || word.any { it !in SUPER }) break
            when (word.map { SUPER.getValue(it) }.joinToString("")) {
                "new" -> isNew = true
                "live" -> isLive = true
            }
            title = if (cut < 0) "" else title.substring(0, cut).trimEnd()
        }
        MARK_SUFFIX.find(title)?.let { m ->
            if (m.groupValues[1].equals("new", true)) isNew = true else isLive = true
            title = title.removeRange(m.range).trimEnd()
        }
        NEW_PREFIX.find(title)?.let { m ->
            if (m.groupValues[1].equals("new", true)) isNew = true else isLive = true
            title = title.removeRange(m.range)
        }
        var episode: String? = null
        EPISODE.find(title)?.let { m ->
            val rest = title.removeRange(m.range).trim()
            if (rest.isNotEmpty()) {
                episode = "S${m.groupValues[1].toInt()} E${m.groupValues[2].toInt()}"
                title = rest
            }
        }
        title = SPACES.replace(title, " ").trim().trimEnd('-', '–', ':', '|').trim()
        return IptvCleanTitle(title.ifEmpty { raw.trim() }, episode, isNew, isLive)
    }

    /** An XMLTV `<episode-num>`: `xmltv_ns` counts from 0 ("1.4.0/1" is S2 E5); `onscreen` is "S02E05" or similar. */
    fun episode(system: String?, value: String): String? {
        val v = value.trim()
        if (v.isEmpty()) return null
        if (system.equals("xmltv_ns", true)) {
            val parts = v.split('.')
            val season = parts.getOrNull(0)?.substringBefore('/')?.trim()?.toIntOrNull()
            val ep = parts.getOrNull(1)?.substringBefore('/')?.trim()?.toIntOrNull()
            return when {
                season != null && ep != null -> "S${season + 1} E${ep + 1}"
                ep != null -> "E${ep + 1}"
                else -> null
            }
        }
        Regex("""S(\d{1,2})\s*[ .]?\s*E(\d{1,3})""", RegexOption.IGNORE_CASE).find(v)?.let {
            return "S${it.groupValues[1].toInt()} E${it.groupValues[2].toInt()}"
        }
        return null
    }

    /** A description cut at a word to [max] characters, with an ellipsis when cut. */
    fun shortDesc(desc: String, max: Int = DESC_MAX): String? {
        val clean = SPACES.replace(desc, " ").trim()
        if (clean.isEmpty()) return null
        if (clean.length <= max) return clean
        val cut = clean.lastIndexOf(' ', max - 1).takeIf { it > max / 2 } ?: (max - 1)
        return clean.substring(0, cut).trimEnd(',', ';', ':', ' ') + "…"
    }

    /** The year of an XMLTV `<date>` ("2013", "20130712"), when it looks like one. */
    fun year(date: String): Int? = date.trim().take(4).toIntOrNull()?.takeIf { it in 1890..2100 }

    // ---- Filler ---------------------------------------------------------------------------------------------

    private val shoppingTitles = Regex("""\b(teleshopping|ideal world|qvc|jewellery maker|shop(ping)? ?(tv|live)?|paid programming|infomercial)\b""", RegexOption.IGNORE_CASE)
    private val radioName = Regex("""\bradio\b""", RegexOption.IGNORE_CASE)
    private val fillerKinds = setOf(IptvChannelKind.Loops, IptvChannelKind.Radio, IptvChannelKind.Shopping, IptvChannelKind.Adult, IptvChannelKind.Religious)

    /** Why a channel is filler, by its category and name; null when it is not. */
    fun fillerChannel(channelName: String, categoryName: String): IptvChannelKind? {
        val kind = IptvTriageRules.kindOf(categoryName)
        if (kind in fillerKinds) return kind
        if (radioName.containsMatchIn(channelName)) return IptvChannelKind.Radio
        return null
    }

    fun isShopping(title: String): Boolean = shoppingTitles.containsMatchIn(title)
}
