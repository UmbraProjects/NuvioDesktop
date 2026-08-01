package com.nuvio.app.features.streams

/**
 * Release-group reputation, used by [StreamScoreTrait.GROUP_TRUSTED] and
 * [StreamScoreTrait.GROUP_LOW_QUALITY].
 *
 * Derived from the TRaSH Guides ranked release-group regexes — the same source Radarr/Sonarr custom
 * formats are built on. Generated from Vidhin's English Ranked Regexes export, with two deliberate
 * simplifications:
 *
 *  - **Movie and TV lists are merged.** TRaSH keeps Radarr and Sonarr tiers separate; a group that
 *    is good at films is good at episodes, and one combined list is what the scoring UI needs.
 *  - **Source categories are merged.** Remux / UHD Bluray / HD Bluray / Web / Anime tiers collapse
 *    into a single placement per group, taking its *best* tier across all of them.
 *
 * Tiers are intentionally **not exposed in the UI** — a user sets one number for "trusted group".
 * The tier acts behind the scenes as a multiplier on that number (see [tierWeight]), so a T1 remux
 * group outranks a T5 one without anybody having to think in tiers.
 */
object StreamReleaseGroups {

    /** Best tier a group holds across every TRaSH category. 1 is the highest. */
    private val TIERS: Map<Int, Set<String>> = mapOf(
        1 to setOf(
            "3l", "abbie", "ajp69", "andy", "apex", "archie", "arg0", "baws", "bbq", "bizkit", "bluranium",
            "blutonium", "bmf", "byndr", "c0ke", "casstudio", "chotab", "cinephiles", "cmrg", "crfw", "crisc",
            "crud", "ctrlhd", "d-z0n3", "dariush", "decibel", "demihuman", "don", "ebp", "eclipse", "edph",
            "fle", "flugel", "flux", "framestor", "geek", "gnome", "hbo", "heavenlyoppa", "hone", "itsok",
            "jkct", "kings", "kitsune", "lolhd", "lostyears", "lovebug", "lys1th3a", "madsky", "mainframe",
            "mark", "mcballs", "mmr", "monkee", "mrhulk", "ncmt", "nosivid", "ntb", "ntg", "paxa", "pexa",
            "phanteam", "piramidhead", "pmp", "pter", "qoq", "rawr", "rtn", "scy", "setsugen", "sh3lby",
            "sic", "synfm", "t6d", "tayto", "tdd", "tepes", "thefarm", "tnp", "tommy", "viethd", "visum",
            "w4nk3r", "wendy", "wildcat", "xepa", "z4st1n", "zerobuild", "zorosenpai", "zq",
        ),
        2 to setOf(
            "0x539", "12gaugeshotgun", "3ctweb", "4kbec", "atelier", "blackrose", "btw", "cebex", "cinefeel",
            "cit", "coo7", "cytox", "db", "deep", "ea", "end", "epsilon", "ethics", "fatesucks", "fc", "gsk-kun",
            "gsk.kun", "gskkun", "half-baked", "hatsubs", "hchcsen", "hidt", "hifi", "hisd", "hqmux", "hydes",
            "ift", "ijp", "ika", "it00nz", "jetix", "johntitor", "jysze", "khn", "kimchi", "kralimarko",
            "kulot", "lazy", "mald", "meakes", "miu", "mtbb", "mzabi", "npms", "nyh", "okay-subs", "orbitron",
            "pandamoon", "phoenix", "playbd", "playweb", "psig", "reza", "roccat", "rtfm", "sa89", "sbr",
            "sdcc", "sicfoi", "sigma", "slyfox", "smurf", "solce", "spirit", "surfinbird", "triton", "tvsmash",
            "wap", "welp", "xebec",
        ),
        3 to setOf(
            "adweb", "anozu", "bbt-rmx", "bhdstudio", "bloom", "chdweb", "chucksmux", "cunny", "cunnysseur",
            "dooky", "dracula", "gnomission", "hallowed", "hdctv", "hhweb", "inka-subs", "lacroix", "lord",
            "nan0", "netaro", "ninjacentral", "noiy", "npz", "ntrx", "ourtv", "p9", "playhd", "pmr", "ptp",
            "rmx", "sekkon", "shine", "slignome", "sphd", "subsmix", "sumvision", "swaglander", "sylvar",
            "t4h", "toa", "vision", "webdv", "zr",
        ),
        4 to setOf(
            "abdex", "armx", "birju", "bkc", "cbt", "erai-raws", "grimf", "ik", "iznjie-biznjie", "iznjie.biznjie",
            "iznjiebiznjie", "kaleido-subs", "kametsu", "kh", "lazyremux", "mk", "neko-kbaraka", "ozr", "pog42",
            "quetzal", "shimatta", "spirale", "toonshub", "udf", "uqw", "varyg", "virtuality",
        ),
        5 to setOf(
            "animorphs", "aomundson", "asc", "b00ba", "bluelobster", "cait-sidhe", "css", "ctr", "d4c", "deanzel",
            "eldon", "freehold", "ghs", "gst", "hark0n", "holomux", "horriblerips", "horriblesubs", "kan3d2m",
            "kiyoshistar", "ks", "mc", "mottoj", "nandesuka", "nh", "ntrm", "o7", "qm", "sobsplease", "some-stuffs",
            "subsplease", "ttga", "ultraremux", "uranime", "wbdp", "wse",
        ),
        6 to setOf(
            "9volt", "asenshi", "bunny-apocalypse", "commie", "cyc", "damedesuyo", "datte13", "ejf", "getittwisted",
            "gjm", "ikaos", "kaleido", "karios", "kawasubs", "pookie", "rasetsu", "starbez", "yoghurt",
        ),
        7 to setOf(
            "bluraydesuyo", "brrrrrrr", "dae", "dragon-releases", "dragsterps", "e-d", "exiled-destiny",
            "fff", "final8", "geonope", "iahd", "inid4c", "koten-gars", "koten.gars", "kotengars", "kuchikirukia",
            "lce", "ntw", "orz", "rai", "revo", "scp-2223", "sev", "thora",
        ),
        8 to setOf(
            "akihitosubs", "arukoru", "nep-blanc", "nep.blanc", "nepblanc",
        ),
    )

    private val TIER_BY_GROUP: Map<String, Int> =
        TIERS.entries.flatMap { (tier, groups) -> groups.map { it to tier } }.toMap()

    /**
     * Groups TRaSH flags as low quality: aggressive re-encodes, upscales, bad dual-audio muxes,
     * dub-only releases and raws.
     */
    private val LOW_QUALITY: Set<String> = setOf(
        "0neshot", "1xbet", "24xhd", "41rgb", "4k4u", "a-destiny", "aceares", "ahmaddev", "aius", "animedynastyen",
        "animekuro", "animerg", "animesubs", "animetr", "anitsu", "anivoid", "aoc", "appletor", "arataenc",
        "arey", "aroma", "asw", "axxo", "azaze", "bakedfish", "barc0de", "bat", "bauckley", "bdc", "bdmv",
        "bdvd", "beast", "ben-the-men", "ben.the.men", "benthemen", "beyondhd", "bioma", "bjx", "blackbit",
        "blackluster", "blasphemy", "bnd", "bols", "bonkai77", "br-guyzo", "brink", "btm", "c1nem4",
        "c4k", "c76", "cameesp", "cat66", "cbb", "cddhd", "chaos", "chd", "chx", "cine", "classicalhd",
        "collective", "cory", "creative24", "crewsade", "ctfoh", "cuap", "cypher", "d3g", "darkflix",
        "dbarabic", "ddr", "deadfish", "deadmau--raws", "deadmau..raws", "deadmauraws", "depraved", "devisive",
        "dkb", "dnl", "dp", "drx", "dsuns", "dub", "eniahd", "epic", "eureka", "evo", "exren", "extreme",
        "fangding0", "feranki1980", "ff", "fgt", "flights", "fmd", "foxx", "frds", "funarts", "fzhd",
        "g4ris", "galaxyrg", "germini", "ghd", "ghosts", "golumpa", "gpthd", "gueira", "guyzo", "hakata-ramen",
        "hakata.ramen", "hakataramen", "hall_of_c", "hav1t", "hdhub4u", "hds", "hdt", "hdtime", "hdwing",
        "henil", "hiqve", "hollowroxas", "iceblue", "intenso", "iplanet", "ipunisher", "ivy", "jacobswaggedup",
        "jennaortega", "jff", "johnny-englishsubs", "kaidubs", "kamifs", "kanjouteki", "kc", "kekmasters",
        "kingdom", "kira", "kirion", "kqrm", "krp", "l0sernight", "lama", "lcd", "leffe", "liber8", "ligas",
        "lolihouse", "lucy", "luvmichelle", "m2ts", "m@ni", "magicstar", "mal-lu-zen", "mal.lu.zen",
        "malluzen", "markii", "mdcx", "megusta", "mesc", "metaljerk", "mgb", "mgd", "mge", "mhd", "minifreeza",
        "minimtbb", "miniscuba", "minitheatre", "mites", "mlh", "modders-bay", "modders.bay", "moddersbay",
        "moozzi2", "msd", "mt", "mteam", "mysilu", "n3g4n", "nahom", "nemdiggers", "neohevc", "next",
        "nhanc3", "nhd", "nikt0", "nogroup", "nokou", "ns", "nsd", "nyanpasu", "oeplus", "oft", "oldcastle",
        "onlymovie", "patomiel", "pd", "phazer11", "pirates", "plex-friendly", "plex.friendly", "plexfriendly",
        "pnpsubs", "polarwindz", "prodji", "project-gxs", "psa", "pthome", "ptnk", "puyasubs", "qas",
        "qce", "rando235", "rarbg", "raws-maji", "rbb", "rdn", "reaktor", "reinforce", "rifftrax", "rightshiftby2",
        "rip-time", "rip.time", "riper", "riptime", "rk", "ru4hd", "rw", "salieri", "samir755", "sankyuu",
        "santi", "sasukeduck", "scene", "shd", "shfs", "shieldbearer", "shincaps", "sigla", "slax", "slay3r",
        "spacefish", "srw", "ssa", "straygods", "stuttershit", "sunscreen", "swtyblz", "taengoo", "tars",
        "tarunk9c", "tbs", "teamturquoize", "teewee", "tekno3d", "telly", "tenrai-sensei", "tenrai.sensei",
        "tenraisensei", "tg", "the-upscaler", "the.upscaler", "theupscaler", "tigole", "tiko", "tm",
        "tnf", "tokar86a", "topkek", "torenter69", "turg", "tvr", "u3-web", "unco", "unco@avistaz", "unkn0wn",
        "uprez", "ups", "valenciano", "vdon", "vector", "videohole", "vipapkstudios", "visionplushdr",
        "visionxpert", "vnlls", "wadu", "waf", "wiki", "will1869", "worldmkv", "wtf-anime", "wtf.anime",
        "wtfanime", "wtv", "x0r", "xiao-av1", "xiquexique", "xlf", "yabai_desu_nerandomremux", "yakuboencodes",
        "yatogam1", "yify", "youshikibi", "yts", "yuisubs", "yusukefla", "zero00", "zeus", "zigzag",
        "znm",
    )

    const val MAX_TIER = 8

    /** The group's tier, or null when it is not a known good group. */
    fun tierOf(group: String): Int? = TIER_BY_GROUP[normalize(group)]

    fun isTrusted(group: String): Boolean = tierOf(group) != null

    fun isLowQuality(group: String): Boolean = normalize(group) in LOW_QUALITY

    /**
     * Multiplier applied to the user's "trusted group" points, so tier matters without being a
     * visible setting. T1 scores the full value and it tapers from there — a lower-tier group is
     * still a positive signal, just a weaker one.
     */
    fun tierWeight(tier: Int): Double = when (tier.coerceIn(1, MAX_TIER)) {
        1 -> 1.0
        2 -> 0.85
        3 -> 0.7
        4 -> 0.6
        5 -> 0.5
        6 -> 0.4
        7 -> 0.35
        else -> 0.3
    }

    /**
     * The best tier mentioned anywhere in [text], or null. Scans the whole release name rather than
     * only the trailing `-GROUP` token, because groups frequently appear mid-string.
     */
    fun bestTierInText(text: String): Int? {
        if (text.isBlank()) return null
        val haystack = text.lowercase()
        var best: Int? = null
        for ((group, tier) in TIER_BY_GROUP) {
            if (best != null && tier >= best) continue
            if (haystack.containsDelimited(group)) best = tier
        }
        return best
    }

    fun textMentionsLowQuality(text: String): Boolean {
        if (text.isBlank()) return false
        val haystack = text.lowercase()
        return LOW_QUALITY.any { haystack.containsDelimited(it) }
    }

    /**
     * Matches a group name anywhere in the text, but only where a release *group* can actually
     * appear: immediately after `-`, `[`, `(` or `@`.
     *
     * Plain word-boundary matching is not safe at this list size. The TRaSH lists contain ordinary
     * words — `don`, `apex`, `mark`, `kings`, `bloom`, `dracula`, `vision` — and a title like
     * `Dracula.2020.1080p` would otherwise be scored as a trusted release. Requiring a group marker
     * keeps the "anywhere in the name" behaviour that matters (`Film-FraMeSToR.2024.mkv`,
     * `[SubsPlease] Show`) while refusing to match a word in the title itself.
     *
     * Position 0 is deliberately *not* a match: a name that merely starts with a group word — a film
     * called `Dracula.2020.1080p` — is a title, not a release by that group. The bracketed anime
     * form still works because the `[` sits at 0 and the name itself starts at 1.
     */
    private fun String.containsDelimited(token: String): Boolean {
        var from = 0
        while (from <= length - token.length) {
            val index = indexOf(token, from)
            if (index < 0) return false
            val startsGroup = index > 0 && this[index - 1] in GROUP_MARKERS
            val end = index + token.length
            val delimitedAfter = end >= length || !this[end].isLetterOrDigit()
            if (startsGroup && delimitedAfter) return true
            from = index + 1
        }
        return false
    }

    private val GROUP_MARKERS = charArrayOf('-', '[', '(', '@')

    /**
     * Release groups arrive with inconsistent decoration — `[YTS.MX]`, `-RARBG`, `QxR` — so compare
     * on a stripped, lower-cased form. The domain suffix is dropped so `yts.mx` matches `yts`.
     */
    private fun normalize(group: String): String =
        group.trim()
            .lowercase()
            .removePrefix("[")
            .removeSuffix("]")
            .removePrefix("-")
            .substringBefore('.')
            .trim()
}
