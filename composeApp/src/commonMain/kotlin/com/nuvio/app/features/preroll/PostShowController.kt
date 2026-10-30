package com.nuvio.app.features.preroll

import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import co.touchlab.kermit.Logger
import com.nuvio.app.features.player.skip.SkipInterval
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The post-show for the film now playing. One instance for the app, because a single player is on
 * screen at a time.
 *
 * The common player publishes *what is playing* ([setFilm]); the platform surface feeds in *where
 * playback is* ([onTick]) and *what was pressed* ([press]). Everything that decides anything lives
 * in [PostShowTracker] and [PostShowView.press], which are tested; this class only holds state and
 * starts the request.
 */
object PostShowController {
    private val log = Logger.withTag("PostShow")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The film being played: its identity for the request, its title for the screen, and its credits markers. */
    data class Film(
        val type: String,
        val id: String,
        val title: String,
        val intervals: List<SkipInterval>,
        val scope: PostShowScope = PostShowScope.Film,
    )

    private val tracker = PostShowTracker()
    private var nowPlaying by mutableStateOf<Film?>(null)
    private var job: Job? = null

    /** The cards have been shown, closed or abandoned for this playback; they will not come back. */
    private var spent by mutableStateOf(false)

    /** True from the moment the cards are requested until they are closed (the video stays full size while loading). */
    private var loading by mutableStateOf(false)

    var pack by mutableStateOf<PostShowPack?>(null)
        private set
    var questions by mutableStateOf<List<ShuffledQuestion>>(emptyList())
        private set
    var view by mutableStateOf(PostShowView(PreShowStage.Finished))
        private set

    /** The cards are up: the video belongs in the corner box. */
    val active: State<Boolean> = derivedStateOf { pack != null }

    val title: String get() = nowPlaying?.title.orEmpty()

    /** The cards are about a season or series finale, not a film. */
    val isFinale: Boolean get() = nowPlaying?.scope.let { it != null && it != PostShowScope.Film }

    /**
     * A finale's cards are still to come this playback. While true the player holds back the
     * next-episode card and autoplay, which would otherwise carry the viewer off the screen just as
     * the cards are due. Clears when the cards close or fail, and the player carries on as usual.
     */
    val pending: Boolean
        get() = nowPlaying?.let { it.scope != PostShowScope.Film && !spent && PostShowRepository.isReady() } == true

    fun setFilm(value: Film?) {
        nowPlaying = value
    }

    /** A new playback attempt, or the player leaving. Forgets everything. */
    fun reset() {
        job?.cancel()
        job = null
        loading = false
        pack = null
        spent = false
        tracker.reset()
    }

    fun onTick(positionMs: Long, durationMs: Long) {
        val current = nowPlaying ?: return
        if (!PreshowSettings.postShowEnabled.value) return
        tracker.onTick(positionMs, durationMs, current.intervals, showing = loading || pack != null).forEach {
            when (it) {
                PostShowSignal.Prefetch -> PostShowRepository.prefetchFor(current.type, current.id, current.scope)
                PostShowSignal.Start -> start(current)
                PostShowSignal.Stop -> close()
            }
        }
    }

    private fun start(current: Film) {
        if (!PostShowRepository.isReady()) {
            spent = true
            return
        }
        loading = true
        job?.cancel()
        job = scope.launch {
            val result = PostShowRepository.packFor(current.type, current.id, current.scope)
            val ready = result.getOrNull()?.takeUnless { it.isEmpty }
            // Closed (or reset) while the request was out: the answer is cached, but nobody wants it now.
            if (!loading) return@launch
            if (ready == null) {
                log.i { "post-show ${current.title}: nothing to show (${result.exceptionOrNull()})" }
                loading = false
                spent = true
                return@launch
            }
            show(ready)
        }
    }

    internal fun show(value: PostShowPack) {
        val asked = value.askableQuestions()
        questions = asked
        view = PostShowView(initialPreShowStage(value.cards.size, asked.size))
        if (view.stage == PreShowStage.Finished) {
            loading = false
            return
        }
        pack = value
        log.i { "post-show shown: ${value.cards.size} cards, ${asked.size} questions" }
    }

    fun close() {
        job?.cancel()
        job = null
        loading = false
        pack = null
        spent = true
    }

    fun press(code: Int) {
        PostShowKey.fromCode(code)?.let(::press)
    }

    fun press(key: PostShowKey) {
        val shown = pack ?: return
        if (key == PostShowKey.Back) {
            close()
            return
        }
        val stage = view.stage
        val options = (stage as? PreShowStage.Question)?.let { questions.getOrNull(it.index)?.options?.size } ?: 0
        view = view.press(key, shown.cards.size, questions.size, options)
        if (view.stage == PreShowStage.Finished) close()
    }

    /** The card's reading time is up: same as pressing Right. */
    fun autoAdvance() = press(PostShowKey.Right)
}
