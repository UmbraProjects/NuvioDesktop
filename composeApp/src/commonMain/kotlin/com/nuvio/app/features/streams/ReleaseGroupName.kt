package com.nuvio.app.features.streams

import java.util.Locale

/**
 * A release name's group, for the stream rows (Built-in Usenet's format) and for scoring, so the
 * group a row shows is the group its score counts.
 */
internal object ReleaseGroupName {
    /**
     * The uploader's tag, as written; null when the name gives none. Names come many ways, so
     * several places are tried, best first:
     *  - after the last dash at the end of the name ("…REMUX-FraMeSToR", "…HDR10+-Ben.The.Men"); a
     *    part number after it is skipped ("…X264-REWARD-1"), and a dash before a space is not one
     *    ("Show - 01", "…2011-Winter Is Coming");
     *  - a leading "[Group]" (fansub names: "[HorribleSubs] Violet Evergarden - 01 [1080p]");
     *  - a trailing "[Group]" (Sonarr and Radarr renames: "… - [Bluray-1080p] [AAC] [PFa]");
     *  - the last word of a closing "(… - Encoder GROUP)" ("… English - Weasley HONE)");
     *  - the last word that is not a release word, after one that is ("…DTS-HD.MA.5.1.KRaLiMaRKo",
     *    "… x264 NTb NL sub"; not "Game.of.Thrones.S01.EXTRAS.720p.BluRay.x264");
     *  - the word after the video codec.
     * Quotes, a file extension ("BiOMA.mkv'", "ION265_mp4") and a dash left at the end ("…H.264-")
     * are dropped. A trailing note in brackets ("[FEATUETTE-Let the Festivities Begin…]") is set
     * aside first, and so are trailing language and subtitle notes ("-XEBEC.[Japanese.-.English]").
     * A candidate made of release words (a resolution, source, codec, audio, language or "sub"),
     * digits or a checksum is not a group: "WEB-DL" made "DL" the group, "Blu-ray" made it "ray",
     * and "H264-20-40" is a codec and two numbers. A group on the trusted tier lists wins over one
     * that is not (a low-quality list entry does not: "[rarbg]" is a site tag after the real group).
     */
    fun of(releaseName: String): String? {
        var name = releaseName.filterNot { it == '\'' || it == '"' }.trim()
            .removeSuffix(".nzb").replace(VIDEO_EXTENSION, "").trim()
        val candidates = mutableListOf<String>()
        // A closing "(… - Encoder GROUP)": the group is its last word.
        TRAILING_PARENS.find(name)?.groupValues?.get(1)?.let { inside ->
            if (" - " in inside) inside.substringAfterLast(" - ").trim().split(' ').lastOrNull()?.let(candidates::add)
        }
        // What follows the release name is set aside: a note in brackets, a repost tag ("-RP"), a
        // version ("v2") and a fansub checksum ("EABC6046"), in any order.
        var checksum = false
        while (true) {
            val tail = TRAILING_NOTE.find(name)
                ?: TRAILING_BRACKET.find(name)?.takeIf { isLanguageNote(it.groupValues[1]) }
                ?: REPOST_TAG.find(name)
                ?: VERSION_TAIL.find(name)
                ?: CHECKSUM_TAIL.find(name)?.also { checksum = true }
                ?: break
            name = name.removeRange(tail.range).trimEnd(' ', '.', '_')
        }
        // A dash left at the end ("…H.264-") is a group left out.
        name = name.trimEnd('-', ' ', '.', '_')
        dashGroup(name)?.let(candidates::add)
        LEADING_BRACKET.find(name)?.groupValues?.get(1)?.trim()?.let(candidates::add)
        // A fansub name whose brackets the indexer dropped still ends in its checksum and starts with
        // its group: "SubsPlease Dragon Ball Super - Beerus - 01 720p EABC6046".
        if (checksum) leadingWord(name)?.let(candidates::add)
        TRAILING_BRACKET.find(name)?.groupValues?.get(1)?.trim()?.let(candidates::add)
        // A group among the tags: "S01E01 [H3LL][1080p][x264][10bit][AAC]".
        BRACKET.findAll(name).map { it.groupValues[1].trim() }.forEach(candidates::add)
        val words = name.split(WORD_SEPARATORS).map { it.trim('(', ')', '[', ']') }.filter { it.isNotBlank() }
        val last = words.indexOfLast { !isReleaseWord(it) }
        if (last > 0 && words.drop(last + 1).all(::isReleaseWord) && words.take(last).any(::isReleaseWord)) {
            // A group of two or three words at the end: "…DDP.2.0.x264.Mr.Yosha".
            val first = (last downTo maxOf(0, last - 2)).last { i -> words.subList(i, last + 1).none(::isReleaseWord) }
            if (first < last && isReleaseWord(words[first - 1].takeIf { first > 0 } ?: "")) {
                candidates += words.subList(first, last + 1).joinToString(".")
            }
            candidates += words[last]
        }
        val codec = words.indexOfLast { it.lowercase(Locale.ROOT) in CODECS }
        if (codec >= 0) words.drop(codec + 1).firstOrNull { !isReleaseWord(it) }?.let(candidates::add)
        // Last resort, a group written before the title: "izzybell-Your Name.2016-1080p-…-x265-FLAC".
        // Only for names with spaces: "Spider-Man.2002.1080p" is a title, not the group Spider.
        if (LEADING_DASH.containsMatchIn(name)) leadingWord(name)?.let(candidates::add)
        val groups = candidates.filter(::isGroupLike)
        return groups.firstOrNull { StreamReleaseGroups.tierOf(it) != null } ?: groups.firstOrNull()
    }

    /**
     * The group a spaced name starts with: its first word, up to a dash that joins it to the title
     * ("MegaMTBB-Kimi no Na wa" is MegaMTBB), except one before "Raws" or "Subs" ("Erai-raws").
     */
    private fun leadingWord(name: String): String? {
        if (' ' !in name) return null
        val first = name.substringBefore(' ')
        val joined = first.substringAfter('-', missingDelimiterValue = "")
        if (joined.isEmpty() || joined.lowercase(Locale.ROOT).trimEnd('.', '_') in GROUP_SUFFIXES) return first
        return first.substringBefore('-')
    }

    /**
     * The word after the name's last dash, skipping part numbers ("-REWARD-1"); bracketed parts are
     * left out. "Raws" and "Subs" end a group's name rather than being one: "-Tsundere-Raws".
     */
    private fun dashGroup(name: String): String? {
        var rest = name.replace(BRACKETED, " ").trim().trimEnd('-', ' ', '.', '_')
        while (true) {
            val group = DASH_TAIL.find(rest) ?: return null
            val word = group.groupValues[1]
            if (word.lowercase(Locale.ROOT) in GROUP_SUFFIXES) {
                val before = DASH_TAIL.find(rest.substring(0, group.range.first)) ?: return word
                return before.groupValues[1] + "-" + word
            }
            if (!word.all(Char::isDigit)) return word
            rest = rest.substring(0, group.range.first).trimEnd('-', ' ', '.', '_')
        }
    }

    /**
     * A group as posters write it: short, no brackets or spaces, and none of its parts digits or a
     * release word ("Ben.The.Men" and "Fall-VS" are fine, "H264-20-40" is not), nor a checksum.
     */
    private fun isGroupLike(candidate: String): Boolean {
        if (candidate.length !in 2..24 || candidate.any { it == '[' || it == ']' || it == '(' || it == ')' || it == ' ' }) return false
        if (CHECKSUM.matches(candidate) || VERSION.matches(candidate)) return false
        if (candidate.lowercase(Locale.ROOT) in GROUP_SUFFIXES) return false
        val parts = candidate.split('.', '-')
        return parts.all { it.isNotEmpty() && !it.all(Char::isDigit) && !isReleaseWord(it) }
    }

    /** A bracketed note naming only languages and subtitles: "[Japanese.-.English]", "[Multi-Subs]". */
    private fun isLanguageNote(inside: String): Boolean {
        val words = inside.lowercase(Locale.ROOT).split(NOTE_SEPARATORS).filter { it.isNotEmpty() }
        return words.isNotEmpty() && words.all { it in LANGUAGE_NOTE_WORDS }
    }

    private fun isReleaseWord(word: String): Boolean {
        val w = word.lowercase(Locale.ROOT).trim('[', ']', '(', ')')
        return w in RELEASE_WORDS || w in CODECS || RESOLUTION_WORD.matches(w) || AUDIO_WORD.matches(w)
    }

    private val VIDEO_EXTENSION = Regex("""(?i)[._](mkv|mp4|avi|ts|m2ts)$""")
    private val LEADING_BRACKET = Regex("""^\[([^\[\]]+)]""")
    private val BRACKETED = Regex("""\[[^\[\]]*]|\([^()]*\)""")
    /** "-GROUP" at the end, after something other than a space. */
    private val DASH_TAIL = Regex("""(?<=[^\s-])-([^\s\-\[\]()]+)$""")
    /** A CRC32 checksum, as fansub names end: "[A1B2C3D4]". */
    private val CHECKSUM = Regex("""[0-9A-Fa-f]{8}""")
    /** A checksum ending the name, bracketed or not (indexers drop the brackets). */
    private val CHECKSUM_TAIL = Regex("""(?<=[\s._-])\[?[0-9A-Fa-f]{8}]?$""")
    /** "Group-Title …": a word without dots joined to the title by a dash, the name spaced. */
    private val LEADING_DASH = Regex("""^[^\s.\-\[\]()]{2,24}-\S[^.]*\s""")
    /** A release's version: "v2" is the second upload of a fix, not a group. */
    private val VERSION = Regex("""(?i)v\d{1,2}""")
    private val VERSION_TAIL = Regex("""(?i)(?<=[\s._])v\d{1,2}$""")
    /**
     * Tags reposters and indexers add after the group, as Sonarr and Radarr strip them:
     * "…-NTb-RP", "…-GROUP-xpost", "…-GROUP-Obfuscated".
     */
    private val REPOST_TAG = Regex(
        """(?i)-(?:rp|xpost|postbot|obfuscated|scrambled|asrequested|alternativetorequested|buymore|4p|4planet|whiterev)$""",
    )
    /** Words that end a group's name ("Erai-raws", "Tsundere-Raws") but are no group alone. */
    private val GROUP_SUFFIXES = setOf("raws", "raw", "subs", "fansub", "fansubs")
    private val BRACKET = Regex("""\[([^\[\]]+)]""")
    private val TRAILING_BRACKET = Regex("""\[([^\[\]]+)]\s*$""")
    private val TRAILING_PARENS = Regex("""\(([^()]+)\)\s*$""")
    private val TRAILING_NOTE = Regex("""\[[^\[\]]*\s[^\[\]]*]\s*$""")
    private val WORD_SEPARATORS = Regex("""[\s._]+""")
    private val NOTE_SEPARATORS = Regex("""[\s._+,&/-]+""")
    private val LANGUAGE_NOTE_WORDS = setOf(
        "english", "japanese", "chinese", "mandarin", "cantonese", "korean", "french", "german", "spanish", "latino",
        "castellano", "italian", "portuguese", "brazilian", "russian", "arabic", "hindi", "tamil", "telugu", "dutch",
        "polish", "turkish", "swedish", "norwegian", "danish", "finnish", "thai", "vietnamese", "indonesian", "malay",
        "hebrew", "greek", "czech", "hungarian", "romanian", "ukrainian",
        "eng", "en", "jpn", "jap", "ja", "chi", "zh", "kor", "fre", "fr", "ger", "de", "spa", "es", "ita", "por", "rus",
        "multi", "multiple", "dual", "audio", "sub", "subs", "subbed", "subtitle", "subtitles", "softsub", "softsubs",
        "hardsub", "hardsubs", "dub", "dubbed", "and",
    )
    private val RESOLUTION_WORD = Regex("""\d{3,4}[pi]|[248]k""")
    private val AUDIO_WORD = Regex("""(dd|ddp|dd\+|eac3|ac3|aac|dts|truehd|flac|opus|atmos|lpcm|pcm)?\d\.\d|\d+bit|\d+ch""")
    private val CODECS = setOf("x264", "x265", "h264", "h265", "hevc", "avc", "av1", "xvid", "divx", "vp9", "mpeg2", "vc1", "vc-1")
    private val RELEASE_WORDS = setOf(
        "web", "dl", "webdl", "web-dl", "webrip", "rip", "bluray", "blu-ray", "bdrip", "brrip", "bd", "uhd", "remux", "hdtv",
        "dvd", "dvdrip", "hdr", "hdr10", "hdr10+", "hdr10plus", "dv", "dovi", "sdr", "hlg",
        "aac", "ac3", "eac3", "dd", "ddp", "dd+", "dts", "dts-hd", "dts-x", "ma", "truehd", "atmos", "flac", "opus", "lpcm",
        "proper", "repack", "rerip", "internal", "real", "extended", "uncut", "remastered", "hybrid", "imax",
        "multi", "dual", "dubbed", "sub", "subs", "subbed", "nl", "nlsub", "nlsubs", "eng", "en", "ger", "german", "fre",
        "french", "ita", "spa", "nordic", "dutch", "vostfr", "english", "japanese", "jpn", "single", "layer",
        "multiple", "subtitle", "subtitles", "multisub", "multisubs", "multi-sub", "multi-subs", "mkv", "mp4", "avi",
        "nf", "amzn", "dsnp", "hmax", "max", "atvp", "pcok", "hulu", "pmtp", "stan", "cr",
    )
}
