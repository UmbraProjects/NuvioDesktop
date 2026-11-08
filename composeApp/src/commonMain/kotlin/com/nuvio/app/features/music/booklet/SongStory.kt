package com.nuvio.app.features.music.booklet
// FORK UI (Spyder): Nick's Now Playing song notes, not in UmbraProjects. If you are merging this file upstream, warn the person first. See CLAUDE.md.

import co.touchlab.kermit.Logger
import com.nuvio.app.features.discover.DiscoverAiClient
import com.nuvio.app.features.discover.DiscoverAiPromptText
import com.nuvio.app.features.discover.DiscoverAiSettings
import com.nuvio.app.features.discover.DiscoverAiSettingsRepository
import com.nuvio.app.features.music.library.WikipediaClient
import com.nuvio.app.features.music.library.quoteIsVerbatim
import com.nuvio.app.features.music.library.songAfterText
import com.nuvio.app.features.music.library.songArticleText
import com.nuvio.app.features.music.library.songMadeText
import com.nuvio.app.features.music.library.songMeaningText
import com.nuvio.app.features.music.library.songSections
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Clock

/** A quote about the song, [who] said it as the article attributes it. */
internal data class SongQuote(val text: String, val who: String)

/**
 * fork:story - the story of one song, from its Wikipedia article: [made] (who wrote it, how it came together), [meaning] (what
 * it is about), [after] (samples, covers, legacy) and up to two [quotes] copied word for word from the article. [ai] when the AI
 * wrote the summaries (the quotes are checked against the article either way).
 */
internal data class SongStory(
    val made: String?,
    val meaning: String?,
    val after: String?,
    val quotes: List<SongQuote>,
    val ai: Boolean,
    /** The parts the person pinned over the written text (made, meaning, after); set when pins are laid over, never cached. */
    val pinned: Set<String> = emptySet(),
) {
    val isEmpty: Boolean get() = made == null && meaning == null && after == null && quotes.isEmpty()
}

private val STORY_JSON = Json { ignoreUnknownKeys = true; isLenient = true }

internal const val SONG_STORY_SYSTEM_PROMPT =
    "You write the story of one song for a music player, from the Wikipedia article you are given and any pages you search for. " +
        "Use only what those say: never add a name, date, quote, sample, cover or chart position they do not state. Leave out " +
        "anything they do not cover. Write plain text with no markdown. Reply with one JSON object and nothing else."

internal fun songStoryPrompt(song: String, artist: String, article: String): String = buildString {
    append("Song: ").append(song).append('\n')
    append("Artist: ").append(artist).append("\n\nArticle:\n<<<\n").append(article).append("\n>>>\n\n")
    append("Return JSON of this shape:\n")
    append("{\"made\": \"2 to 3 sentences: who wrote and produced it, when and where, how it came together\",\n")
    append(" \"meaning\": \"1 to 2 sentences: what the song is about, as the article describes it\",\n")
    append(" \"after\": \"1 to 2 sentences: who has sampled or covered it, and its legacy, naming them only if the article does\",\n")
    append(" \"quotes\": [{\"text\": \"words copied exactly from the article\", \"who\": \"who said them, as the article attributes\"}]}\n")
    append("Up to two quotes, each copied word for word from the article. Leave out any key the article does not support.")
}

/** The prompt when the model can search the web: it looks for the song's story itself, with the Wikipedia [article] (when there is one) as a start. */
internal fun songStoryWebPrompt(song: String, artist: String, article: String): String = buildString {
    append("Search the web for the song \"").append(song).append("\" by ").append(artist).append(". Find how it was written and recorded, ")
    append("what it is about, who has sampled or covered it, and what the people involved have said about it in interviews or articles. ")
    append("Prefer the artist's own words, reliable music journalism and Wikipedia.\n")
    if (article.isNotBlank()) append("\nWikipedia article, as a start:\n<<<\n").append(article).append("\n>>>\n")
    append("\nThen answer with JSON of this shape and nothing else:\n")
    append("{\"made\": \"2 to 3 sentences: who wrote and produced it, when and where, how it came together\",\n")
    append(" \"meaning\": \"1 to 2 sentences: what the song is about\",\n")
    append(" \"after\": \"1 to 2 sentences: who has sampled or covered it, and its legacy; name them only if a page says so\",\n")
    append(" \"quotes\": [{\"text\": \"words copied exactly from a page you read\", \"who\": \"who said them\"}]}\n")
    append("Up to two quotes, each copied word for word. Never add a name, date, sample or quote you did not read. Leave out any key you cannot support.")
}

private fun JsonElement?.line(max: Int): String? =
    (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.replace(Regex("""\s+"""), " ")?.trim()?.takeIf { it.length >= 20 }?.let { cutAtSentence(it, max) }

/** The story in the model's [reply], with quotes kept only when they stand word for word in [article] (or in the pages a web search read); null when unreadable or empty. */
internal fun parseSongStory(reply: String, article: String): SongStory? {
    val start = reply.indexOf('{')
    val end = reply.lastIndexOf('}')
    if (start < 0 || end <= start) return null
    val root = runCatching { STORY_JSON.parseToJsonElement(reply.substring(start, end + 1)) }.getOrNull() as? JsonObject ?: return null
    // A reply from a web search holds the pages' snippets before the answer: quotes may come from those, never from the answer
    // itself (a quote the AI made up would otherwise vouch for itself).
    val answerAt = Regex("""\{\s*"(made|meaning|after|quotes)"""").find(reply)?.range?.first ?: start
    val source = article + "\n" + reply.substring(0, answerAt)
    val quotes = (root["quotes"] as? JsonArray).orEmpty().mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val text = (obj["text"] as? JsonPrimitive)?.content?.trim()?.trim('"', '“', '”')?.trim() ?: return@mapNotNull null
        val who = (obj["who"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.trim().orEmpty()
        if (text.length > 240 || !quoteIsVerbatim(text, source)) null else SongQuote(text, who.take(80))
    }.take(2)
    val story = SongStory(root["made"].line(420), root["meaning"].line(300), root["after"].line(300), quotes, ai = true)
    return story.takeUnless { it.isEmpty }
}

/**
 * fork:story - a story from Last.fm's song [wiki]: its opening paragraphs in the box (whole sentences, cut to length), and
 * a later paragraph, when there is one, as what came after.
 */
internal fun lastFmSongStory(wiki: String): SongStory? {
    val paragraphs = wiki.split(Regex("""\n\s*\n""")).map { it.replace(Regex("""\s+"""), " ").trim() }.filter { it.length >= 40 }
    val first = paragraphs.firstOrNull() ?: return null
    val opening = if (first.length < 250 && paragraphs.size > 1) first + " " + paragraphs[1] else first
    val rest = paragraphs.drop(if (opening === first) 1 else 2).firstOrNull()
    return SongStory(
        made = cutAtSentence(opening, 600),
        meaning = null,
        after = rest?.let { cutAtSentence(it, 300) },
        quotes = emptyList(),
        ai = false,
    )
}

/** The story with no AI: the article's own sections, cut to length. */
internal fun extractiveSongStory(sections: Map<String, String>): SongStory? =
    SongStory(songMadeText(sections, 420), songMeaningText(sections, 300), songAfterText(sections, 300), emptyList(), ai = false).takeUnless { it.isEmpty }

private fun SongStory.toBooklet(): AlbumBooklet {
    val rows = listOfNotNull(
        made?.let { BookletCredit("made", it) },
        meaning?.let { BookletCredit("meaning", it) },
        after?.let { BookletCredit("after", it) },
    ) + quotes.map { BookletCredit("quote", it.text + QUOTE_SPLIT + it.who) }
    return AlbumBooklet("story", rows, emptyMap(), if (ai) BookletSource.Ai else BookletSource.Wikipedia)
}

private const val QUOTE_SPLIT = "␟"

private fun AlbumBooklet.toStory(): SongStory {
    fun role(r: String) = credits.firstOrNull { it.role == r }?.names
    val quotes = credits.filter { it.role == "quote" }.map { c -> SongQuote(c.names.substringBefore(QUOTE_SPLIT), c.names.substringAfter(QUOTE_SPLIT, "")) }
    return SongStory(role("made"), role("meaning"), role("after"), quotes, source == BookletSource.Ai)
}

/**
 * fork:story - looks the song's article up once (and, with Groq's gpt-oss, lets the model search the web for interviews and articles too), writes its story with the person's own AI key (one request per song; with
 * no key, or if it fails, the article's own sections stand in) and keeps it with the booklets. A song with no article is
 * remembered for three days.
 */
internal object SongStories {
    private val log = Logger.withTag("SongStory")
    private val lock = Mutex()

    private fun now(): Long = Clock.System.now().toEpochMilliseconds()

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
    private fun key(artist: String, song: String, mode: String) = "story1|${norm(artist)}|${norm(song)}|$mode"
    private fun read(key: String): BookletEntry? = decodeBookletEntry(BookletCache.get(key))
    private fun keep(key: String, story: SongStory?) = BookletCache.put(key, encodeBookletEntry(BookletEntry(now(), story?.toBooklet())))

    /** The cache mode of the AI's story: the model, and ":web" when it searched the web for it (a different, richer story). */
    private fun aiMode(settings: DiscoverAiSettings) = "ai:${settings.effectiveModel}" + if (DiscoverAiClient.canSearchWeb(settings)) ":web" else ""

    /** What is kept for the song, never making a request, with the person's pins laid over it. */
    fun peek(artist: String, song: String): SongStory? = peekKept(artist, song).withPins(Pins.forSong(artist, song))

    /**
     * The story for the song, asking for it when nothing is kept, with the person's pins laid over it (see [loadKept]).
     * [backup] lets Gemini write it when the main AI cannot: for a story the person opened, never the background filler,
     * which would spend Gemini's free allowance on songs nobody looked at.
     */
    suspend fun load(artist: String, song: String, wantAi: Boolean = false, backup: Boolean = false): SongStory? =
        FactsPriority.foreground { loadKept(artist, song, wantAi, backup) }.withPins(Pins.forSong(artist, song))

    /**
     * Whether the AI's story for the song is kept, failed a moment ago or not asked for yet (see [AlbumBookletRepository.factState]).
     * A song Wikipedia has no article for still counts as Missing while the AI can search the web for it.
     */
    fun factState(artist: String, song: String): FactState {
        val settings = DiscoverAiSettingsRepository.snapshot().takeIf { it.isConfigured } ?: return FactState.Missed
        val entry = read(key(artist, song, aiMode(settings)))?.takeIf { it.isFresh(now(), BOOKLET_AI_TTL_MS, BOOKLET_AI_MISS_TTL_MS) }
        return when {
            entry == null -> FactState.Missing
            entry.booklet != null -> FactState.Have
            else -> FactState.Missed
        }
    }

    /** Forgets a failed ask for the song's AI story so the next lookup asks again; a kept answer is left alone. */
    fun forgetMiss(artist: String, song: String) {
        val settings = DiscoverAiSettingsRepository.snapshot().takeIf { it.isConfigured } ?: return
        val key = key(artist, song, aiMode(settings))
        if (read(key)?.booklet == null) BookletCache.put(key, null)
    }

    /** What is kept for the song, without pins: the cache's own answer, which the lookup rules below go by. */
    private fun peekKept(artist: String, song: String): SongStory? {
        val at = now()
        DiscoverAiSettingsRepository.snapshot().takeIf { it.isConfigured }?.let { settings ->
            read(key(artist, song, aiMode(settings)))?.takeIf { it.isFresh(at, BOOKLET_AI_TTL_MS, BOOKLET_AI_MISS_TTL_MS) }?.booklet?.let { return it.toStory() }
        }
        // fork:story - Gemini's story (kept like the main AI's), then Wikipedia's own text, then Last.fm's song wiki.
        read(key(artist, song, GEMINI_MODE))?.takeIf { it.isFresh(at, BOOKLET_AI_TTL_MS, BOOKLET_AI_MISS_TTL_MS) }?.booklet?.let { return it.toStory() }
        read(key(artist, song, "wiki"))?.takeIf { it.isFresh(at, BOOKLET_WIKI_TTL_MS, BOOKLET_WIKI_MISS_TTL_MS) }?.booklet?.let { return it.toStory() }
        return read(key(artist, song, LASTFM_MODE))?.takeIf { it.isFresh(at, BOOKLET_WIKI_TTL_MS, BOOKLET_WIKI_MISS_TTL_MS) }?.booklet?.toStory()
    }

    private const val GEMINI_MODE = "gemini"
    private const val LASTFM_MODE = "lastfm"

    /**
     * The song's story, asking for it when nothing is kept. With [wantAi], a kept Wikipedia-only story does not stop the AI
     * from being asked (once; a failed ask is remembered for its usual time), so a story kept before a key was set, or by
     * an earlier failure, is written up when it can be.
     */
    private suspend fun loadKept(artist: String, song: String, wantAi: Boolean, backup: Boolean): SongStory? = lock.withLock {
        val gemini = backup && GeminiSearch.available()
        peekKept(artist, song)?.let { kept ->
            if (!wantAi || kept.ai || !(DiscoverAiSettingsRepository.snapshot().isConfigured || gemini)) return@withLock kept
        }
        val at = now()
        val settings = DiscoverAiSettingsRepository.snapshot().takeIf { it.isConfigured }
        val web = settings != null && DiscoverAiClient.canSearchWeb(settings)
        val wikiKey = key(artist, song, "wiki")
        // A recent "no article" is believed, unless something can still find the story: an AI that searches the web, Gemini
        // or Last.fm's wiki.
        val canLookFurther = web || gemini || com.nuvio.app.features.music.library.LastFmClient.isConfigured
        if (!canLookFurther && read(wikiKey)?.let { it.booklet == null && it.isFresh(at, BOOKLET_WIKI_TTL_MS, BOOKLET_WIKI_MISS_TTL_MS) } == true) return@withLock null
        val text = runCatching { WikipediaClient.songText(song, artist) }.getOrNull()
        val sections = text?.let(::songSections).orEmpty()
        val plain = extractiveSongStory(sections)
        if (read(wikiKey) == null || plain != null) keep(wikiKey, plain)
        if (sections.isEmpty()) log.i { "no Wikipedia article for $artist - $song" }

        // The main AI, as before.
        if (settings != null && (sections.isNotEmpty() || web)) {
            val aiKey = key(artist, song, aiMode(settings))
            val missedLately = read(aiKey)?.let { it.booklet == null && it.isFresh(at, BOOKLET_AI_TTL_MS, BOOKLET_AI_MISS_TTL_MS) } == true
            if (!missedLately) {
                val article = songArticleText(sections, if (web) 5_000 else 9_000)
                val prompt = if (web) songStoryWebPrompt(song, artist, article) else songStoryPrompt(song, artist, article)
                DiscoverAiClient.complete(settings, DiscoverAiPromptText(SONG_STORY_SYSTEM_PROMPT, prompt), webSearch = web)
                    .onSuccess { reply ->
                        val story = parseSongStory(reply, article)
                        keep(aiKey, story)
                        log.i { "story for $song (web=$web): ${story?.quotes?.size ?: 0} quotes" }
                        if (story != null) return@withLock story
                    }
                    .onFailure {
                        log.w { "story for $song failed: $it" }
                        keep(aiKey, null)
                    }
            }
        }

        // fork:story - Gemini, searching Google itself, when the main AI could not write it (out of its daily allowance,
        // no key, or nothing to go on). Quotes are kept only when they stand in the Wikipedia article: Gemini does not hand
        // back the pages' words to check them against.
        if (gemini) {
            val geminiKey = key(artist, song, GEMINI_MODE)
            val missedLately = read(geminiKey)?.let { it.booklet == null && it.isFresh(at, BOOKLET_AI_TTL_MS, BOOKLET_AI_MISS_TTL_MS) } == true
            if (!missedLately) {
                val article = songArticleText(sections, 5_000)
                GeminiSearch.complete(SONG_STORY_SYSTEM_PROMPT, songStoryWebPrompt(song, artist, article))
                    .onSuccess { reply ->
                        val story = parseSongStory(reply, article)
                        keep(geminiKey, story)
                        log.i { "story for $song from Gemini: ${if (story == null) "nothing usable" else "${story.quotes.size} quotes"}" }
                        if (story != null) return@withLock story
                    }
                    .onFailure { error ->
                        log.w { "Gemini story for $song failed: ${error.message}" }
                        // Only a real answer that had nothing is remembered; a rate limit or a network failure asks again later.
                    }
            }
        }

        plain?.let { return@withLock it }

        // fork:story - last, Last.fm's listener-written song wiki, which covers many songs Wikipedia does not.
        if (com.nuvio.app.features.music.library.LastFmClient.isConfigured) {
            val lastFmKey = key(artist, song, LASTFM_MODE)
            val kept = read(lastFmKey)?.takeIf { it.isFresh(at, BOOKLET_WIKI_TTL_MS, BOOKLET_WIKI_MISS_TTL_MS) }
            if (kept != null) return@withLock kept.booklet?.toStory()
            val wiki = runCatching { com.nuvio.app.features.music.library.LastFmClient.trackWiki(artist, song) }.getOrNull()
            val story = wiki?.let(::lastFmSongStory)
            keep(lastFmKey, story)
            log.i { if (story != null) "story for $song from Last.fm's wiki" else "no Last.fm wiki for $artist - $song" }
            return@withLock story
        }
        null
    }
}
