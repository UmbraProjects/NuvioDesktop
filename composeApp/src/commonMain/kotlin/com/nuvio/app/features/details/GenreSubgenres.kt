package com.nuvio.app.features.details

/**
 * Turns a title's keyword tags into a per-genre breakdown for the details page: hovering "Horror"
 * on a title tagged `slasher` and `found footage` reads "Slasher · Found footage".
 *
 * The tags are TMDB's keyword vocabulary (reached directly, or through MDBList which passes it on
 * verbatim), so every phrase below is spelt the way TMDB spells it. That vocabulary is folksonomy:
 * it mixes sub-genres (`psychological thriller`) with themes (`revenge`) and settings (`island`),
 * and nothing in it says which genre a tag refines. This list is that missing link, per genre, in
 * the order a reader would want them — recognised sub-genres first, then the themes that
 * characterise the genre. Order matters because the result is capped at [MAX_PER_GENRE].
 *
 * A tag that merely repeats the genre (`horror` on a Horror title) is never a breakdown of it and
 * is dropped. Matching is exact after normalisation; substring matching was tried and produced
 * `war` inside `warehouse`.
 *
 * That vocabulary yields *themes*. The sub-genres proper ("Folk Horror", "Psychological Drama")
 * come from IMDb's interests ([ImdbInterest]); [card] puts the two together for the whole title,
 * sub-genres first and themes as a second line, and every genre in the row shows that one card.
 */
internal object GenreSubgenres {

    const val MAX_PER_GENRE = 6

    /** Longest the merged theme line gets; each genre contributes up to [MAX_PER_GENRE]. */
    const val MAX_THEMES = 8

    /**
     * What the genre hover says about a title: IMDb's [subgenres], then the TMDB-keyword [themes]
     * its genres are refined by. Either may be empty; [isEmpty] means the title has nothing to say
     * and its genres get no hover at all.
     */
    data class Card(
        val subgenres: List<String>,
        val themes: List<String>,
    ) {
        val isEmpty: Boolean get() = subgenres.isEmpty() && themes.isEmpty()

        /** Sub-genre line for the tooltip title; blank when there are none. */
        val headline: String get() = subgenres.joinToString(SEPARATOR)

        /** Theme line for the tooltip subtitle; blank when there are none. */
        val themeLine: String get() = themes.joinToString(SEPARATOR)
    }

    /**
     * One card for the title, shown from every genre in the row rather than split per genre —
     * the reader wants "what kind of comedy / animation is South Park" in one glance, not a hunt
     * across three hovers. Sub-genres are IMDb's list as given, deduplicated; the parser already
     * dropped the plain genres from it and [genres] are dropped again here in case an addon named
     * one differently. Themes are the union of each genre's [breakdown], in row order, minus
     * anything the sub-genre line already says ("Sitcom" and "Satire" are both a TMDB keyword and
     * an IMDb interest), capped at [MAX_THEMES].
     */
    fun card(
        genres: Collection<String>,
        interests: Collection<ImdbInterest>,
        keywords: Collection<String>,
    ): Card {
        val genreWords = genres.flatMapTo(HashSet(), ::genreWords)
        val subgenres = LinkedHashMap<String, String>()
        for (interest in interests) {
            val name = interest.name.trim()
            val key = normalise(name)
            if (key.isBlank() || key in genreWords) continue
            subgenres.putIfAbsent(key, name)
        }
        val themes = LinkedHashMap<String, String>()
        for (genre in genres) {
            for (theme in breakdown(genre, keywords)) {
                if (themes.size >= MAX_THEMES) break
                val key = normalise(theme)
                if (key in subgenres) continue
                themes.putIfAbsent(key, theme)
            }
        }
        return Card(subgenres = subgenres.values.toList(), themes = themes.values.toList())
    }

    /** How many of the card's tags join the genre line itself. */
    const val MAX_LINE_TAGS = 2

    /**
     * The first [max] of the card's tags (sub-genres, then themes) for the genre line beside the genres, so the
     * most telling ones are seen without hovering: none that repeats a shown genre (in any of its spellings) or
     * another tag. Empty when the card has nothing new.
     */
    fun lineTags(card: Card, genres: Collection<String>, max: Int = MAX_LINE_TAGS): List<String> {
        val taken = genres.flatMapTo(HashSet(), ::genreWords)
        val out = ArrayList<String>(max)
        for (tag in card.subgenres + card.themes) {
            if (out.size >= max) break
            val key = normalise(tag)
            if (key.isBlank() || !taken.add(key)) continue
            out += tag
        }
        return out
    }

    /**
     * Sub-genre phrases the hovered [genre] is refined by, in vocabulary order, at most
     * [MAX_PER_GENRE]. Empty when the title has no tag that refines this genre, in which case the
     * caller shows no hover at all rather than an empty card.
     */
    fun breakdown(genre: String, keywords: Collection<String>): List<String> {
        val vocabularies = vocabulariesFor(genre)
        if (vocabularies.isEmpty() || keywords.isEmpty()) return emptyList()
        val genreWords = genreWords(genre)
        val tags = keywords.mapTo(LinkedHashSet()) { normalise(it) }
        val out = LinkedHashSet<String>()
        for (vocabulary in vocabularies) {
            for (phrase in vocabulary) {
                if (phrase in tags && phrase !in genreWords) out += phrase
                if (out.size >= MAX_PER_GENRE) return out.map(::display)
            }
        }
        return out.map(::display)
    }

    /** The genre's own name in the forms a tag could repeat it, so it can be excluded. */
    private fun genreWords(genre: String): Set<String> {
        val parts = genre.split('&', '/').map(::normalise).filter(String::isNotBlank)
        return buildSet {
            add(normalise(genre))
            addAll(parts)
            parts.forEach { part -> ALIASES[part]?.let(::add) }
        }
    }

    /**
     * Compound genre labels (TMDB television's "Action & Adventure", "Sci-Fi & Fantasy", "War &
     * Politics") are read as each of their halves in turn.
     */
    private fun vocabulariesFor(genre: String): List<List<String>> =
        genreKeys(genre).mapNotNull { key -> VOCABULARY[key] }

    /** Canonical genre key(s) for a label: aliases resolved, compound labels split. */
    private fun genreKeys(genre: String): List<String> =
        genre.split('&', '/')
            .map(::normalise)
            .filter(String::isNotBlank)
            .map { part -> ALIASES[part] ?: part }

    private fun genreKey(genre: String): String =
        normalise(genre).let { ALIASES[it] ?: it }

    private fun normalise(value: String): String =
        value.trim().lowercase().replace(Regex("\\s+"), " ")

    /** Sentence case for the card. TMDB tags are all-lowercase, including proper nouns. */
    private fun display(phrase: String): String =
        phrase.replaceFirstChar { it.uppercaseChar() }

    private val ALIASES: Map<String, String> = mapOf(
        "sci-fi" to "science fiction",
        "scifi" to "science fiction",
        "sci fi" to "science fiction",
        "science-fiction" to "science fiction",
        "kids" to "family",
        "children" to "family",
        "biographical" to "biography",
        "musical" to "music",
        "historical" to "history",
        "sports" to "sport",
        "super power" to "superhero",
        "super hero" to "superhero",
        // IMDb's interest categories where they differ from TMDB's genre labels.
        "reality tv" to "reality",
        "anime" to "animation",
    )

    private const val SEPARATOR = " \u00b7 "

    private val VOCABULARY: Map<String, List<String>> = mapOf(
        "horror" to listOf(
            "slasher", "psychological horror", "body horror", "found footage", "supernatural horror",
            "folk horror", "cosmic horror", "gothic horror", "sci-fi horror", "comedy horror",
            "horror comedy", "teen horror", "survival horror", "religious horror", "eco-horror",
            "j-horror", "giallo", "splatter", "gore", "torture porn", "creature feature", "monster",
            "kaiju", "zombie", "zombie apocalypse", "vampire", "werewolf", "ghost", "ghost story",
            "haunted house", "haunting", "haunted", "possession", "demonic possession", "demon",
            "exorcism", "occult", "witch", "witchcraft", "cult", "curse", "evil doll", "killer doll",
            "serial killer", "masked killer", "home invasion", "cannibalism", "cannibal",
            "paranormal", "paranormal investigation", "urban legend", "mad scientist", "backwoods",
            "final girl", "evil spirit", "poltergeist", "apocalypse", "post-apocalyptic",
            "isolation", "dark", "asylum", "mental institution", "psychiatric hospital",
        ),
        "thriller" to listOf(
            "psychological thriller", "crime thriller", "erotic thriller", "political thriller",
            "legal thriller", "action thriller", "techno-thriller", "supernatural thriller",
            "domestic thriller", "medical thriller", "spy thriller", "conspiracy thriller",
            "neo-noir", "film noir", "conspiracy", "conspiracy theory", "heist", "spy", "espionage",
            "serial killer", "hostage", "kidnapping", "abduction", "revenge", "cat and mouse",
            "whodunit", "stalker", "stalking", "home invasion", "survival", "paranoia", "mind game",
            "twist ending", "plot twist", "assassin", "hitman", "undercover", "corruption",
            "cover-up", "manhunt", "wrongful accusation", "amnesia", "unreliable narrator",
            "double life", "blackmail", "vigilante", "hijacking", "terrorism", "chase",
            "identity", "mistaken identity", "on the run", "race against time", "suspense",
            "isolation", "asylum", "mental institution", "psychiatric hospital", "mental illness",
        ),
        "mystery" to listOf(
            "whodunit", "murder mystery", "cozy mystery", "detective", "private detective",
            "amateur detective", "police investigation", "murder investigation", "investigation",
            "cold case", "missing person", "disappearance", "unsolved", "unsolved crime",
            "locked room", "puzzle", "conspiracy", "secret", "twist ending", "plot twist", "amnesia",
            "unreliable narrator", "neo-noir", "film noir", "hardboiled", "serial killer",
            "small town", "true crime", "supernatural", "occult", "cult", "psychological",
            "mental illness", "asylum", "mental institution", "psychiatric hospital", "island",
            "secret identity", "double life", "clue", "clues", "riddle",
        ),
        "drama" to listOf(
            "psychological drama", "crime drama", "family drama", "legal drama", "medical drama",
            "political drama", "period drama", "costume drama", "historical drama",
            "romantic drama", "sports drama", "war drama", "courtroom drama", "teen drama",
            "social drama", "workplace drama", "melodrama", "coming of age", "biography",
            "based on true story", "based on a true story", "true story", "character study",
            "slice of life", "tragedy", "dysfunctional family", "grief", "loss", "redemption",
            "addiction", "alcoholism", "drug addiction", "mental illness", "depression",
            "terminal illness", "cancer", "aging", "midlife crisis", "marriage", "divorce",
            "adultery", "infidelity", "father son relationship", "mother daughter relationship",
            "father daughter relationship", "mother son relationship", "siblings", "friendship",
            "immigration", "class differences", "poverty", "racism", "lgbt", "gay theme",
            "religion", "faith", "prison", "small town", "road movie", "ensemble cast",
            "independent film", "domestic violence", "sexual abuse", "suicide", "holocaust",
        ),
        "comedy" to listOf(
            "dark comedy", "black comedy", "romantic comedy", "screwball comedy", "buddy comedy",
            "sex comedy", "teen comedy", "stoner comedy", "workplace comedy", "action comedy",
            "crime comedy", "horror comedy", "comedy horror", "musical comedy", "family comedy",
            "sketch comedy", "sitcom", "mockumentary", "satire", "political satire", "social satire",
            "parody", "spoof", "slapstick", "farce", "comedy of manners", "gross-out comedy",
            "cringe comedy", "absurdist", "absurd", "surreal", "deadpan", "dry humor", "raunchy",
            "road trip", "fish out of water", "body swap", "coming of age", "bromance", "wedding",
            "bachelor party", "stand-up comedy", "improvisation", "heist", "revenge",
        ),
        "action" to listOf(
            "martial arts", "kung fu", "superhero", "heist", "spy", "espionage", "action thriller",
            "action comedy", "buddy cop", "gun fu", "one man army", "vigilante", "assassin",
            "hitman", "revenge", "disaster", "survival", "military", "special forces", "mercenary",
            "car chase", "shootout", "explosion", "sword fight", "swordplay", "samurai", "ninja",
            "wuxia", "street racing", "boxing", "mixed martial arts", "gladiator", "bounty hunter",
            "terrorism", "hostage", "kidnapping", "rescue mission", "chase", "cop", "police",
            "post-apocalyptic", "dystopia", "cyberpunk", "mecha", "kaiju", "monster", "parkour",
            "war", "pirate", "treasure hunt", "time travel",
        ),
        "adventure" to listOf(
            "treasure hunt", "treasure", "quest", "expedition", "exploration", "explorer",
            "swashbuckler", "pirate", "pirates", "sea voyage", "shipwreck", "survival",
            "wilderness", "jungle", "desert", "island", "lost world", "lost city", "archaeology",
            "archaeologist", "road trip", "journey", "epic", "sword and sorcery", "sword and sandal",
            "fantasy world", "time travel", "dinosaur", "safari", "mountain", "mountain climbing",
            "arctic", "antarctica", "space travel", "space exploration", "hero's journey",
            "coming of age", "adventure comedy", "family adventure", "voyage", "map", "rescue",
        ),
        "science fiction" to listOf(
            "space opera", "cyberpunk", "steampunk", "biopunk", "dystopia", "dystopian",
            "post-apocalyptic", "apocalypse", "time travel", "time loop", "alien", "aliens",
            "alien invasion", "first contact", "artificial intelligence", "robot", "android",
            "cyborg", "clone", "cloning", "virtual reality", "simulation", "parallel universe",
            "multiverse", "alternate reality", "alternate history", "space", "outer space",
            "space travel", "spaceship", "space station", "space colony", "mars", "moon", "planet",
            "near future", "far future", "future", "hard science fiction", "genetic engineering",
            "mutation", "mutant", "superhero", "mecha", "kaiju", "nanotechnology", "teleportation",
            "telepathy", "telekinesis", "psychic", "experiment", "mad scientist",
            "military science fiction", "space western", "zombie", "pandemic", "virus", "outbreak",
            "terraforming", "generation ship", "cryogenics", "transhumanism", "singularity",
            "sci-fi horror", "sci-fi comedy", "technology", "invention", "inventor",
        ),
        "fantasy" to listOf(
            "high fantasy", "epic fantasy", "dark fantasy", "urban fantasy", "sword and sorcery",
            "magical realism", "portal fantasy", "isekai", "fairy tale", "mythology",
            "greek mythology", "norse mythology", "arthurian legend", "magic", "magical", "wizard",
            "witch", "witchcraft", "sorcery", "sorcerer", "dragon", "dragons", "elf", "elves",
            "dwarf", "orc", "fairy", "fairies", "mythical creature", "unicorn", "mermaid", "vampire",
            "werewolf", "ghost", "supernatural", "superhero", "parallel world", "alternate world",
            "fantasy world", "quest", "chosen one", "prophecy", "medieval", "kingdom", "king",
            "queen", "princess", "prince", "knight", "castle", "talking animals", "anthropomorphism",
            "time travel", "curse", "immortality", "afterlife", "angel", "demon", "gods", "deity",
            "steampunk", "genie", "wish", "enchanted", "spell",
        ),
        "romance" to listOf(
            "romantic comedy", "romantic drama", "love triangle", "forbidden love",
            "star-crossed lovers", "first love", "teen romance", "young love", "enemies to lovers",
            "friends to lovers", "fake relationship", "fake dating", "second chance",
            "second chance romance", "unrequited love", "long-distance relationship", "age gap",
            "age difference", "arranged marriage", "wedding", "marriage", "affair", "adultery",
            "infidelity", "lgbt", "gay theme", "lesbian", "gay romance", "lesbian romance",
            "period romance", "historical romance", "workplace romance", "office romance",
            "interracial romance", "summer romance", "holiday romance", "christmas", "erotic",
            "erotica", "sexuality", "passion", "soulmates", "reincarnation", "tragic love",
            "tragic romance", "doomed love", "love story", "dating", "online dating", "breakup",
            "divorce", "single mother", "single father", "widow", "widower", "grief",
        ),
        "crime" to listOf(
            "heist", "bank robbery", "robbery", "organized crime", "mafia", "mob", "gangster",
            "gang", "gang war", "cartel", "drug cartel", "drug trafficking", "drug dealer", "drugs",
            "serial killer", "murder", "murder mystery", "police procedural", "detective",
            "private detective", "cop", "police", "police corruption", "corruption", "corrupt cop",
            "undercover", "undercover cop", "informant", "fbi", "cia", "dea", "prison",
            "prison escape", "prison break", "courtroom", "trial", "lawyer", "true crime",
            "based on true story", "con artist", "con man", "scam", "fraud", "white collar crime",
            "embezzlement", "money laundering", "kidnapping", "hostage", "revenge", "vigilante",
            "hitman", "assassin", "contract killer", "neo-noir", "film noir", "hardboiled",
            "crime thriller", "crime drama", "crime comedy", "yakuza", "triad", "biker gang",
            "car theft", "smuggling", "counterfeiting", "art heist", "casino", "gambling",
            "arms dealer", "human trafficking", "wrongful conviction", "death row",
            "juvenile delinquent", "street gang", "crime family", "witness protection",
            "getaway driver", "bootlegging", "prohibition",
        ),
        "war" to listOf(
            "world war i", "world war ii", "vietnam war", "korean war", "iraq war",
            "afghanistan war", "gulf war", "civil war", "american civil war", "cold war",
            "napoleonic wars", "crusades", "trench warfare", "d-day", "pearl harbor", "holocaust",
            "nazi", "nazis", "resistance", "french resistance", "occupation", "soldier", "soldiers",
            "military", "navy", "marines", "air force", "pilot", "submarine", "tank", "sniper",
            "special forces", "prisoner of war", "pow", "war crimes", "genocide", "refugee",
            "anti-war", "war drama", "war comedy", "battle", "siege", "invasion",
            "guerrilla warfare", "propaganda", "espionage", "spy", "home front", "veteran", "ptsd",
            "post-traumatic stress disorder", "war veteran", "medic", "bombing", "atomic bomb",
            "nuclear war", "dogfight", "naval battle", "samurai", "roman empire", "ancient rome",
            "warrior", "combat", "politics", "political", "political thriller", "political drama",
            "election", "president", "government", "diplomacy", "white house", "prime minister",
            "dictator", "dictatorship", "revolution", "coup", "terrorism",
        ),
        "animation" to listOf(
            "anime", "stop motion", "claymation", "computer animation", "cgi",
            "hand drawn animation", "2d animation", "3d animation", "traditional animation",
            "rotoscoping", "puppet", "puppets", "cutout animation", "adult animation",
            "cartoon", "cartoons", "shonen", "shounen", "shoujo", "seinen", "josei", "mecha",
            "isekai", "slice of life", "magical girl", "kaiju", "based on manga", "manga",
            "based on comic", "based on video game", "anthropomorphism", "talking animals",
            "musical", "superhero", "fantasy world", "fairy tale", "coming of age",
        ),
        "documentary" to listOf(
            "true crime", "nature documentary", "nature", "wildlife", "biography", "history",
            "music documentary", "concert film", "concert", "sports documentary", "science",
            "space", "war", "politics", "social issues", "environment", "climate change", "food",
            "cooking", "travel", "expedition", "investigative journalism", "journalism",
            "archive footage", "found footage", "interview", "interviews", "talking heads",
            "mockumentary", "docudrama", "docuseries", "cinéma vérité", "observational",
            "essay film", "art", "artist", "filmmaking", "making of", "behind the scenes", "cult",
            "religion", "crime", "murder", "serial killer", "disappearance", "scandal", "fraud",
            "con artist", "conspiracy", "ufo", "paranormal", "animals", "ocean", "wilderness",
            "survival",
        ),
        "family" to listOf(
            "family comedy", "family adventure", "family drama", "coming of age", "talking animals",
            "animals", "dog", "cat", "pet", "adoption", "orphan", "friendship", "holiday",
            "christmas", "santa claus", "fairy tale", "magic", "school", "summer camp", "musical",
            "puppets", "toys", "teddy bear", "single parent", "siblings", "grandparent",
            "grandmother", "grandfather", "road trip", "vacation", "sports",
            "based on children's book", "children's book", "based on young adult novel",
            "young adult", "superhero", "dinosaur", "princess",
        ),
        "music" to listOf(
            "musical", "concert", "concert film", "rock opera", "jukebox musical", "stage musical",
            "broadway", "based on musical", "music biopic", "biopic", "rock and roll", "rock music",
            "rock band", "band", "punk", "punk rock", "hip hop", "hip-hop", "rap", "rap music",
            "rapper", "jazz", "blues", "country music", "folk music", "classical music", "opera",
            "pop music", "pop star", "singer", "singing", "musician", "songwriter", "composer",
            "conductor", "orchestra", "piano", "pianist", "guitar", "guitarist", "drummer", "dj",
            "electronic music", "techno", "disco", "dance", "dancing", "ballet", "hip hop dance",
            "breakdance", "tap dancing", "music industry", "record label", "recording studio",
            "tour", "world tour", "music festival", "woodstock", "talent show",
            "singing competition", "choir", "gospel", "soul music", "reggae", "k-pop", "j-pop",
            "idol", "boy band", "girl group",
        ),
        "western" to listOf(
            "spaghetti western", "revisionist western", "neo-western", "contemporary western",
            "acid western", "weird west", "space western", "outlaw", "outlaws", "sheriff",
            "marshal", "u.s. marshal", "bounty hunter", "gunslinger", "gunfighter", "cowboy",
            "cowboys", "cowgirl", "ranch", "rancher", "cattle", "cattle drive", "saloon", "frontier",
            "wild west", "old west", "american west", "gold rush", "railroad", "stagecoach",
            "train robbery", "bank robbery", "duel", "shootout", "showdown", "native american",
            "apache", "comanche", "cavalry", "civil war", "texas", "mexico", "border", "revenge",
            "vigilante", "lawman", "posse", "manhunt", "horse", "horses", "desert", "prairie",
            "homestead", "settlers", "pioneer", "bandit", "bandits", "rodeo",
        ),
        "history" to listOf(
            "based on true story", "based on a true story", "true story", "biography", "biopic",
            "period drama", "costume drama", "ancient rome", "roman empire", "ancient greece",
            "ancient egypt", "medieval", "middle ages", "renaissance", "tudor", "victorian era",
            "victorian", "edwardian", "regency", "georgian era", "18th century", "19th century",
            "20th century", "1920s", "1930s", "1940s", "1950s", "1960s", "1970s", "1980s", "1990s",
            "world war i", "world war ii", "cold war", "civil rights", "civil rights movement",
            "slavery", "colonialism", "revolution", "french revolution", "american revolution",
            "russian revolution", "royalty", "monarchy", "king", "queen", "emperor", "empire",
            "dynasty", "politics", "politician", "president", "prime minister", "election",
            "assassination", "scandal", "holocaust", "genocide", "apartheid", "immigration",
            "great depression", "prohibition", "space race", "moon landing", "berlin wall",
            "vietnam war", "korean war", "samurai", "shogun", "viking", "vikings", "crusades",
            "knights templar", "explorer", "exploration", "conquistador", "pirates", "titanic",
        ),
        "biography" to listOf(
            "biopic", "based on true story", "based on a true story", "true story", "musician",
            "artist", "painter", "writer", "author", "poet", "scientist", "inventor", "politician",
            "president", "royalty", "athlete", "boxer", "soldier", "war hero", "activist",
            "civil rights", "entrepreneur", "businessman", "criminal", "gangster", "serial killer",
            "con artist", "rise and fall", "rise to fame", "addiction", "genius", "prodigy",
        ),
        "sport" to listOf(
            "boxing", "mixed martial arts", "wrestling", "football", "american football", "soccer",
            "basketball", "baseball", "hockey", "ice hockey", "tennis", "golf", "cricket", "rugby",
            "cycling", "running", "marathon", "swimming", "surfing", "skateboarding", "snowboarding",
            "skiing", "figure skating", "gymnastics", "cheerleading", "dance", "ballet", "chess",
            "poker", "horse racing", "car racing", "formula 1", "nascar", "motorcycle racing",
            "street racing", "olympics", "underdog", "coach", "team", "championship", "tournament",
            "comeback", "rivalry", "high school sports", "college sports", "sports drama",
            "sports documentary", "sports comedy",
        ),
        "reality" to listOf(
            "reality competition", "competition", "dating show", "cooking competition",
            "talent show", "singing competition", "game show", "makeover", "home renovation",
            "survival competition", "social experiment", "celebrity", "docuseries", "cooking",
            "baking", "fashion", "modeling", "real estate", "wedding", "dating", "elimination",
        ),
        "soap" to listOf(
            "soap opera", "telenovela", "melodrama", "family saga", "affair", "adultery",
            "love triangle", "amnesia", "secret", "wealthy family", "inheritance", "revenge",
            "betrayal", "scandal", "hospital", "small town", "long lost sibling", "secret identity",
        ),
        "superhero" to listOf(
            "superhero", "superhero team", "supervillain", "secret identity", "origin story",
            "based on comic", "marvel comics", "dc comics", "mutant", "vigilante", "superpower",
            "super powers", "masked vigilante", "alien", "time travel", "multiverse",
        ),
        "supernatural" to listOf(
            "ghost", "ghost story", "haunting", "haunted house", "possession", "demon", "exorcism",
            "vampire", "werewolf", "witch", "witchcraft", "occult", "curse", "psychic", "medium",
            "afterlife", "angel", "reincarnation", "spirit", "evil spirit", "paranormal",
            "paranormal investigation", "zombie", "immortality", "shapeshifting",
        ),
        "psychological" to listOf(
            "psychological thriller", "psychological horror", "psychological drama",
            "mind game", "unreliable narrator", "amnesia", "paranoia", "mental illness",
            "identity", "trauma", "obsession", "manipulation", "gaslighting", "dissociative identity",
            "asylum", "mental institution", "psychiatric hospital", "dream", "nightmare",
            "isolation", "twist ending", "plot twist",
        ),
        "slice of life" to listOf(
            "school", "high school", "friendship", "everyday life", "coming of age", "family",
            "small town", "workplace", "college", "club", "summer", "countryside", "iyashikei",
            "healing", "cooking", "food",
        ),
    )
}
