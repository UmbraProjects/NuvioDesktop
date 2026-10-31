package com.nuvio.app.features.preroll

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.preshow_starting
import nuvio.composeapp.generated.resources.preshow_starting_hint
import org.jetbrains.compose.resources.stringResource
import co.touchlab.kermit.Logger
import com.nuvio.app.core.ui.NuvioAsyncImage
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.discover.DiscoverAiSettingsRepository
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.library.LibraryClock
import com.nuvio.app.features.library.LibraryRepository
import com.nuvio.app.features.library.toLibraryItem
import com.nuvio.app.features.home.components.HomeHeroTrailerSurface
import com.nuvio.app.features.themesong.THEME_SONG_FADE_MS
import com.nuvio.app.features.themesong.ThemeSongPlayer
import com.nuvio.app.features.themesong.ThemeSongRepository
import com.nuvio.app.features.themesong.ThemeSongSettings
import com.nuvio.app.features.trailer.HeroTrailerMetadataService
import com.nuvio.app.features.trailer.TrailerPlaybackSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private val log = Logger.withTag("PrerollGate")

private const val RESOLVE_TIMEOUT_MS = 25_000L
private const val MAX_TRAILER_MS = 4 * 60_000L
private const val DETAILS_FETCH_TIMEOUT_MS = 8_000L
private const val MAX_CLIP_MS = 3 * 60_000L
private const val CLIP_RESOLVE_TIMEOUT_MS = 25_000L
private const val TRIVIA_WAIT_AFTER_TRAILERS_MS = 10_000L
private const val TRIVIA_WAIT_ALONE_MS = 6_000L
private const val TRIVIA_WIKIPEDIA_WAIT_MS = 4_000L
/** How long the drawn rating card waits for its advisory; past this it is drawn without one. */
private const val ADVISORY_WAIT_MS = 3_000L
// Long on purpose: packFor returns the moment the pack exists, so this only matters when the provider
// is struggling, and the trailers are playing anyway. It covers the retries in TriviaRepository.
private const val TRIVIA_WAIT_WHILE_TRAILERS_MS = 100_000L
/** How long the fetch may run (it has the whole trailer time) vs. how long the pre-show will wait for it. */
private const val TRIVIA_ARTWORK_FETCH_MS = 25_000L
private const val TRIVIA_ARTWORK_WAIT_MS = 2_500L

/** How long the theme song may take to be found, and how long the first trivia card waits for it. */
private const val THEME_SONG_RESOLVE_WAIT_MS = 30_000L
private const val THEME_SONG_START_WAIT_MS = 1_500L

/** How long nothing may be on screen before the "Starting your pre-show" state appears, so quick hand-offs do not flash it. */
private const val WAITING_INDICATOR_DELAY_MS = 800L

/** How long the silent stream outlives the pre-show, to bridge the film's own player opening its output. */
private const val KEEP_AUDIO_ALIVE_TAIL_MS = 6_000L

/**
 * Trailers whose tallest rendition is under this are held back for sharper ones. Heights, not 1080
 * exactly: a 2.39:1 trailer is 1920x816 at the 1080 tier, and 1280x720 or 1280x544 are the 720 tier.
 */
private const val MIN_PRESHOW_TRAILER_HEIGHT = 800
private const val MAX_SOFT_TRAILERS = 6

/** One trailer in flight: who it is for, and what to play. */
private data class GateTrailer(val subject: MetaPreview?, val source: TrailerPlaybackSource)

/**
 * Plays the pre-show before [content] (the player) is created.
 *
 * It is a gate in front of the player rather than part of it on purpose. A trailer is a native mpv
 * surface, as is the film; two of them alive at once fight over the window, and a film that is
 * already loading would also start playing behind the trailer. Here the film's player does not
 * exist until the pre-show is over, so there is nothing to race.
 *
 * Order: whatever [PreshowSettings.order] says. The parts are up to [PrerollSettings.trailerCount]
 * trailers drawn from the film's "More Like This" titles, the trivia slides and quiz if one is
 * ready, and the clip slots (cinema intro, bumper, rating card, feature presentation) that are
 * switched on. The trivia pack is requested when the gate opens, so whatever plays first is also
 * the time it spends being generated.
 *
 * Keys: Escape skips everything, Right/Enter skips the current trailer. The trailer's own Stop
 * button skips that trailer too.
 */
@Composable
fun PrerollGate(
    launchKey: String,
    metaType: String,
    metaId: String,
    eligible: Boolean,
    title: String,
    content: @Composable () -> Unit,
) {
    var finished by remember(launchKey) { mutableStateOf(!eligible) }
    if (finished) {
        content()
        return
    }

    val trailerCount by PrerollSettings.trailerCount.collectAsState()

    var shown by remember(launchKey) { mutableStateOf<GateTrailer?>(null) }
    var surfaceReady by remember(launchKey) { mutableStateOf(false) }
    var triviaPack by remember(launchKey) { mutableStateOf<TriviaPack?>(null) }
    var drawnCard by remember(launchKey) { mutableStateOf<PreshowCardModel?>(null) }
    var triviaArtwork by remember(launchKey) { mutableStateOf(TriviaArtwork()) }
    // The title's theme song, played behind the trivia cards when that is switched on. Null is silence.
    var themeSource by remember(launchKey) { mutableStateOf<TrailerPlaybackSource?>(null) }
    // Quieter while a quiz question is open, so the music is not competing with the viewer's thinking.
    var themeDucked by remember(launchKey) { mutableStateOf(false) }
    val themeVolume by ThemeSongSettings.volume.collectAsState()
    val control = remember(launchKey) { GateControl() }
    val focus = remember { FocusRequester() }

    LaunchedEffect(launchKey) {
        val order = PreshowSettings.order.value
        val clipSlots = PreshowSettings.enabledSlots.value
        // Every trailer and clip is its own player, so the audio output is closed and reopened
        // between them. On an HDMI receiver that gap is long enough for it to drop the input and
        // re-detect it, which is heard as a dip at the start of each item. A silent stream held
        // open across the whole pre-show (and a few seconds into the film) keeps the link up.
        val keepAudioAlive = PreshowSettings.keepAudioAlive.value && (trailerCount > 0 || clipSlots.isNotEmpty())
        if (keepAudioAlive) PreshowAudioKeepAlive.start()
        try {
        coroutineScope {
            // The details record is usually cached already; when playback started from somewhere that
            // never opened the details page it is fetched here instead of silently yielding no
            // trailers and no certificate.
            var detailsCache: MetaDetails? = null
            suspend fun details(): MetaDetails? {
                detailsCache?.let { return it }
                val found = MetaDetailsRepository.peekAny(metaType, metaId)
                    ?: withTimeoutOrNull(DETAILS_FETCH_TIMEOUT_MS) {
                        runCatching { MetaDetailsRepository.fetch(metaType, metaId) }.getOrNull()
                    }
                return found.also { detailsCache = it }
            }
            // The rating card's content line (the BBFC's advisory, or the MPA's reasons) comes from outside. Started
            // now so it is usually in hand by the time the card is drawn; it is only awaited, briefly, there.
            val advisoryDeferred = async {
                if (PreshowSlot.RatingCard !in clipSlots) return@async null
                val meta = details() ?: return@async null
                runCatching {
                    when (PreshowSettings.ratingRegion.value) {
                        RatingRegion.Bbfc ->
                            BbfcAdvisory.advisoryFor(title, meta.releaseInfo?.trim()?.take(4)?.takeIf { it.length == 4 && it.all(Char::isDigit) })
                        RatingRegion.Mpaa ->
                            MpaAdvisory.reasonFor(meta.imdbId ?: metaId.substringBefore(':').takeIf { it.startsWith("tt") })
                    }
                }.getOrNull()
            }
            // Anything that plays ahead of the trivia is also time the AI provider gets to answer.
            val hasLeadIn = trailerCount > 0 || clipSlots.isNotEmpty()
            val packDeferred = async {
                TriviaRepository.packFor(
                    type = metaType,
                    metaId = metaId,
                    waitMs = if (hasLeadIn) TRIVIA_WAIT_WHILE_TRAILERS_MS else TRIVIA_WAIT_ALONE_MS,
                )
            }
            // Wikipedia is the first fallback behind the AI pack. Started alongside it so it is
            // already in hand if the provider fails or is slow; free and keyless, and cached, so
            // it costs nothing after the first play. Skipped when the AI pack is already cached.
            val wikiDeferred = async {
                if (!DiscoverAiSettingsRepository.snapshot().triviaEnabled) return@async null
                val meta = details() ?: return@async null
                if (TriviaRepository.peek(meta) != null) return@async null
                runCatching { TriviaWikipedia.packFor(meta) }.getOrNull()
            }
            // The theme song is found now, while the trailers play, so it can start with the first card.
            val themeDeferred = async {
                if (!ThemeSongSettings.triviaEnabled.value) return@async null
                val meta = details()
                withTimeoutOrNull(THEME_SONG_RESOLVE_WAIT_MS) {
                    runCatching { ThemeSongRepository.resolve(metaType, meta?.name ?: title, meta?.releaseInfo, metaId) }.getOrNull()
                }
            }
            // Fanart is fetched alongside the pack, so by the time the trailers end both are ready.
            val artworkDeferred = async {
                // Only the trivia cards use it.
                if (!DiscoverAiSettingsRepository.snapshot().triviaEnabled) return@async TriviaArtwork()
                withTimeoutOrNull(TRIVIA_ARTWORK_FETCH_MS) { TriviaArtworkRepository.forTitle(metaType, metaId) }
                    ?: TriviaArtwork()
            }
            // `played` counts anything that played (it sizes the trivia wait); `trailersPlayed`
            // counts trailers only. They must be separate: clips used to bump the one counter, so a
            // cinema intro plus a bumper used up a trailer count of 2 before any trailer ran.
            var played = 0
            var trailersPlayed = 0

            suspend fun playTrailers() {
                if (trailerCount > 0) {
                    val candidates = details()?.prerollTrailerCandidates().orEmpty()
                    if (candidates.isEmpty()) log.i { "no trailer candidates for $metaType:$metaId" }
                    var index = 0
                    // Trailers whose best upload is soft (an old film, a direct-to-DVD title) are held
                    // back and only played, sharpest first, if the sharp ones run out. A pre-show
                    // trailer at 480p looks far worse than a shorter pre-show.
                    val soft = mutableListOf<GateTrailer>()
                    while (trailersPlayed < trailerCount && !control.skipAll) {
                        val trailer: GateTrailer
                        if (index < candidates.size) {
                            val candidate = candidates[index++]
                            // The logo lookup runs beside the trailer resolve, so it adds no wait.
                            val logoLookup = async { runCatching { candidate.withMetadataLogo() }.getOrDefault(candidate) }
                            val source = withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
                                HeroTrailerMetadataService.resolve(candidate.type, candidate.id, sharpest = true)
                            }
                            if (source == null) {
                                logoLookup.cancel()
                                log.i { "no trailer for ${candidate.type}:${candidate.id}, trying the next" }
                                continue
                            }
                            val subject = logoLookup.await()
                            if ((!source.reliable || source.heightPx in 1 until MIN_PRESHOW_TRAILER_HEIGHT) && soft.size < MAX_SOFT_TRAILERS) {
                                log.i { "trailer for ${candidate.type}:${candidate.id} is soft (${source.heightPx}p, reliable=${source.reliable}), holding it back" }
                                soft += GateTrailer(subject, source)
                                continue
                            }
                            trailer = GateTrailer(subject, source)
                        } else {
                            val best = soft.maxByOrNull { it.source.heightPx } ?: break
                            soft.remove(best)
                            log.i { "no sharper trailers left, playing ${best.subject?.id} at ${best.source.heightPx}p" }
                            trailer = best
                        }
                        // Warm the one after this so there is no dead air between trailers.
                        candidates.getOrNull(index)?.let { next ->
                            launch { runCatching { HeroTrailerMetadataService.resolve(next.type, next.id, sharpest = true) } }
                        }
                        val done = CompletableDeferred<Unit>()
                        control.current = done
                        control.becameReady = false
                        surfaceReady = false
                        shown = trailer
                        withTimeoutOrNull(MAX_TRAILER_MS) { done.await() }
                        if (control.becameReady) { played++; trailersPlayed++ }
                        else trailer.subject?.let { HeroTrailerMetadataService.invalidate(it.type, it.id) }
                        control.current = null
                    }
                    // Dropping the surface only now keeps it mounted across trailers; rebuilding the
                    // native surface in between comes up on a black frame.
                    shown = null
                }
            }

            suspend fun showTrivia() {
                if (!control.skipAll) {
                    val outcome = withTimeoutOrNull(
                        if (played > 0) TRIVIA_WAIT_AFTER_TRAILERS_MS else TRIVIA_WAIT_ALONE_MS,
                    ) { packDeferred.await() }
                    val aiPack = outcome?.getOrNull()
                    // The AI pack is better, but it can miss the wait; facts from the details record
                    // are instant. Only when trivia is switched on, so turning it off stays off.
                    val pack = aiPack ?: if (DiscoverAiSettingsRepository.snapshot().triviaEnabled) {
                        withTimeoutOrNull(TRIVIA_WIKIPEDIA_WAIT_MS) { wikiDeferred.await() }?.also {
                            log.i { "trivia: AI pack not ready, showing ${it.slides.size} Wikipedia slides" }
                        } ?: details()?.let(::buildMetadataTrivia)?.also {
                            log.i { "trivia: AI and Wikipedia not ready, showing ${it.slides.size} metadata slides" }
                        }
                    } else {
                        null
                    }
                    if (pack == null) {
                        // Say why, once: a pre-show that silently never appears cannot be debugged.
                        // Not-enabled is the normal state for most people and stays quiet.
                        val reason = (outcome?.exceptionOrNull() as? TriviaUnavailableException)?.reason
                        val message = when {
                            outcome == null || reason == TriviaUnavailable.TimedOut ->
                                "Trivia: the AI provider did not answer in time"
                            reason == TriviaUnavailable.NotEnabled -> null
                            reason == TriviaUnavailable.NotEnoughMetadata -> "Trivia: not enough details about this title"
                            reason == TriviaUnavailable.MetadataNotFound -> "Trivia: title details not available"
                            else -> "Trivia failed: ${outcome?.exceptionOrNull()?.message ?: "unknown error"}"
                        }
                        log.w { "trivia skipped: $message" }
                        message?.let { NuvioToastController.show(it) }
                    } else {
                        val done = CompletableDeferred<Unit>()
                        control.current = done
                        triviaArtwork = withTimeoutOrNull(TRIVIA_ARTWORK_WAIT_MS) { artworkDeferred.await() }
                            ?: TriviaArtwork()
                        // Already resolved by now in nearly every case; the short wait is for the rest.
                        val theme = withTimeoutOrNull(THEME_SONG_START_WAIT_MS) { themeDeferred.await() }
                        themeSource = theme
                        triviaPack = pack
                        done.await()
                        triviaPack = null
                        themeSource = null
                        // The player is gone the moment the pre-show ends, so let the music fade first.
                        if (theme != null) delay(THEME_SONG_FADE_MS + 50L)
                    }
                }
            }

            // A drawn card stands in when a rating or feature-presentation clip is switched on but
            // nothing could be found for it; the other clip slots just stay quiet.
            suspend fun playClipSlot(slot: PreshowSlot) {
                val source = withTimeoutOrNull(CLIP_RESOLVE_TIMEOUT_MS) { PreshowAssets.clipFor(slot, details()?.ageRating) }
                if (source != null) {
                    val done = CompletableDeferred<Unit>()
                    control.current = done
                    control.becameReady = false
                    surfaceReady = false
                    shown = GateTrailer(null, source)
                    withTimeoutOrNull(MAX_CLIP_MS) { done.await() }
                    control.current = null
                    val didPlay = control.becameReady
                    shown = null
                    if (didPlay) played++
                    if (didPlay || control.skipAll) return
                }
                val region = PreshowSettings.ratingRegion.value
                val card = when (slot) {
                    PreshowSlot.RatingCard -> normalizeCertification(details()?.ageRating, region)?.let {
                        val advisory = withTimeoutOrNull(ADVISORY_WAIT_MS) { advisoryDeferred.await() }
                        PreshowCardModel.Rating(it, region, titleWithYear(title, details()?.releaseInfo), advisory)
                    }
                    PreshowSlot.FeaturePresentation -> PreshowCardModel.FeaturePresentation(title)
                    else -> null
                } ?: return
                val done = CompletableDeferred<Unit>()
                control.current = done
                drawnCard = card
                done.await()
                drawnCard = null
                control.current = null
            }

            for (slot in order) {
                if (control.skipAll) break
                when (slot) {
                    PreshowSlot.Trailers -> playTrailers()
                    PreshowSlot.Trivia -> showTrivia()
                    else -> if (slot in clipSlots) playClipSlot(slot)
                }
            }
            // coroutineScope waits for its children, so anything still fetching (a trivia pack that missed
            // its slot, the Wikipedia fallback, artwork, the BBFC advisory) would hold the film back.
            themeDeferred.cancel()
            packDeferred.cancel()
            wikiDeferred.cancel()
            artworkDeferred.cancel()
            advisoryDeferred.cancel()
        }
        } finally {
            if (keepAudioAlive) PreshowAudioKeepAlive.stopAfter(KEEP_AUDIO_ALIVE_TAIL_MS)
        }
        finished = true
    }

    LaunchedEffect(shown?.source?.videoUrl) { runCatching { focus.requestFocus() } }

    val activeTrailer = shown
    val activePack = triviaPack
    // Between items (resolving a trailer, waiting on the trivia pack, finding a clip) nothing is on
    // screen. Past a short grace period that reads as a hang, so say what is happening.
    val nothingShowing = activeTrailer == null && activePack == null && drawnCard == null
    var showWaiting by remember(launchKey) { mutableStateOf(false) }
    LaunchedEffect(nothingShowing) {
        if (nothingShowing) {
            delay(WAITING_INDICATOR_DELAY_MS)
            showWaiting = true
        } else {
            showWaiting = false
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || activePack != null) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Escape, Key.ButtonB, Key.Back -> { control.skipEverything(); true }
                    Key.DirectionRight, Key.Enter, Key.NumPadEnter, Key.ButtonA -> { control.finishCurrent(); true }
                    else -> false
                }
            },
    ) {
        if (activeTrailer != null) {
            if (!surfaceReady) {
                val artwork = activeTrailer.subject?.let { it.banner ?: it.poster }
                if (!artwork.isNullOrBlank()) {
                    NuvioAsyncImage(
                        model = artwork,
                        contentDescription = activeTrailer.subject?.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
            }
            HomeHeroTrailerSurface(
                sourceUrl = activeTrailer.source.videoUrl,
                sourceAudioUrl = activeTrailer.source.audioUrl,
                playWhenReady = true,
                // Ignored in pre-show mode: it always plays unmuted at one fixed level.
                muted = false,
                volume = 100,
                backgroundColor = Color.Black,
                logoUrl = activeTrailer.subject?.logo,
                title = activeTrailer.subject?.name.orEmpty(),
                meta = activeTrailer.subject?.gateMetaLine().orEmpty(),
                // The chrome would otherwise print the synopsis over the picture; a pre-show wants
                // the title, logo and year line and nothing else.
                description = "",
                modifier = Modifier.fillMaxSize(),
                navDismissEdge = "none",
                onReady = {
                    surfaceReady = true
                    control.becameReady = true
                },
                onEnded = { control.finishCurrent() },
                onError = {
                    val fallback = activeTrailer.source.fallback
                    // A fallback with nothing behind it is the muxed progressive stream, which YouTube
                    // caps at 360p. It is the usual result when the adaptive URLs are refused (403),
                    // and it is worth skipping the trailer over; the HLS manifest, which has the
                    // progressive stream behind it, still carries the full ladder and is retried. Clips (no
                    // subject) keep every fallback: a soft clip beats a missing one.
                    if (fallback != null && (activeTrailer.subject == null || fallback.fallback != null)) {
                        // The preferred stream was refused; the same trailer usually plays from
                        // the HLS manifest or the muxed stream, so retry before skipping it.
                        log.i { "playback error for ${activeTrailer.subject?.type}:${activeTrailer.subject?.id}, retrying with fallback source" }
                        shown = GateTrailer(activeTrailer.subject, fallback)
                    } else {
                        log.i { "playback error for ${activeTrailer.subject?.type}:${activeTrailer.subject?.id}" }
                        control.finishCurrent()
                    }
                },
                // The native panel can leave OS focus behind; take it back so Escape still works.
                onSurfaceDisposed = { runCatching { focus.requestFocus() } },
                // Everything in the pre-show is levelled the same way, clips and trailers alike, so
                // one item is not louder than the next. The surface's one-second fade-in hides the
                // normaliser ramping up from unity gain at the start of a clip.
                normalizeAudio = true,
                preshow = true,
                // Only a trailer has a parent title to save; clips and cards have no subject.
                libraryLabel = if (activeTrailer.subject != null) "Add to library" else "",
                inLibrary = activeTrailer.subject?.let { LibraryRepository.isSaved(it.id, it.type) } == true,
                onAddToLibrary = {
                    activeTrailer.subject?.let { subject ->
                        if (!LibraryRepository.isSaved(subject.id, subject.type)) {
                            LibraryRepository.toggleSaved(subject.toLibraryItem(LibraryClock.nowEpochMs()))
                            NuvioToastController.show("Added ${subject.name} to your library")
                        }
                    }
                },
            )
        }
        if (showWaiting && nothingShowing) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                CircularProgressIndicator(color = Color.White.copy(alpha = 0.8f))
                Text(
                    text = stringResource(Res.string.preshow_starting),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White.copy(alpha = 0.85f),
                )
                Text(
                    text = stringResource(Res.string.preshow_starting_hint),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.55f),
                )
            }
        }
        ThemeSongPlayer(
            source = themeSource,
            volume = if (themeDucked) (themeVolume / 3).coerceAtLeast(5) else themeVolume,
        )
        drawnCard?.let { card ->
            PreshowCard(model = card, onFinished = { control.finishCurrent() }, modifier = Modifier.fillMaxSize())
        }
        if (activePack != null) {
            TriviaPreShow(
                pack = activePack,
                title = title,
                onFinished = { control.finishCurrent() },
                modifier = Modifier.fillMaxSize(),
                artwork = triviaArtwork,
                onQuestionActive = { themeDucked = it },
                onSkipAll = { control.skipEverything() },
                onNotRight = {
                    MetaDetailsRepository.peekAny(metaType, metaId)?.let { TriviaRepository.discard(it, activePack.origin) }
                    NuvioToastController.show(
                        if (activePack.origin == TriviaOrigin.Ai) {
                            "Got it - trivia for this film will be written again next time"
                        } else {
                            "Got it - this film's Wikipedia trivia won't be shown again"
                        },
                    )
                },
            )
        }
    }
}

/** What the keys and callbacks reach: finish whatever is showing, or everything. */
private class GateControl {
    var current: CompletableDeferred<Unit>? = null
    var becameReady: Boolean = false
    var skipAll: Boolean = false

    fun finishCurrent() {
        current?.complete(Unit)
    }

    fun skipEverything() {
        skipAll = true
        current?.complete(Unit)
    }
}

private fun MetaPreview.gateMetaLine(): String =
    listOfNotNull(
        genres.firstOrNull()?.takeIf(String::isNotBlank),
        releaseInfo?.trim()?.take(4)?.takeIf { it.length == 4 && it.all(Char::isDigit) },
    ).joinToString("   •   ")

/** "Halloween" and "2018-10-19" give "Halloween (2018)"; no usable year leaves the title alone. */
internal fun titleWithYear(title: String, releaseInfo: String?): String {
    val year = releaseInfo?.trim()?.take(4)?.takeIf { it.length == 4 && it.all(Char::isDigit) } ?: return title
    return if (title.contains("($year)")) title else "$title ($year)"
}
