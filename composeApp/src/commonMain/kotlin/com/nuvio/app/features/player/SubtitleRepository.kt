package com.nuvio.app.features.player

import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.AddonResource
import com.nuvio.app.features.addons.ManagedAddon
import com.nuvio.app.features.addons.buildAddonResourceUrl
import com.nuvio.app.features.addons.enabledAddons
import com.nuvio.app.features.addons.httpGetText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.compose_player_no_subtitles_found
import nuvio.composeapp.generated.resources.player_addon_subtitle_display_format
import org.jetbrains.compose.resources.getString

private const val ADDON_SUBTITLE_TIMEOUT_MS = 15_000L

object SubtitleRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json { ignoreUnknownKeys = true }

    private val _addonSubtitles = MutableStateFlow<List<AddonSubtitle>>(emptyList())
    val addonSubtitles: StateFlow<List<AddonSubtitle>> = _addonSubtitles.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var activeFetchJob: Job? = null

    /**
     * [extras] tells the addons which file is playing (see [SubtitleRequestExtras]). [resolveExtras],
     * when given, is asked first for a fuller set (the player computes the file's hash there); it
     * falls back to [extras] when it returns null or fails.
     */
    fun fetchAddonSubtitles(
        type: String,
        videoId: String,
        extras: SubtitleRequestExtras? = null,
        resolveExtras: (suspend () -> SubtitleRequestExtras?)? = null,
    ): Job {
        activeFetchJob?.cancel()
        activeFetchJob = scope.launch {
            val requestType = canonicalSubtitleType(type)
            _isLoading.value = true
            _error.value = null
            _addonSubtitles.value = emptyList()

            // fork:youtube-mode - a YouTube tab video: its own uploaded and auto-generated tracks, not addons'.
            if (type == com.nuvio.app.features.youtube.YOUTUBE_META_TYPE) {
                val tracks = try {
                    com.nuvio.app.features.youtube.youTubeCaptionSubtitles(videoId)
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    emptyList()
                }
                _addonSubtitles.value = tracks
                if (tracks.isEmpty()) _error.value = getString(Res.string.compose_player_no_subtitles_found)
                _isLoading.value = false
                return@launch
            }

            val requestExtras = resolveExtras?.let { resolve ->
                try {
                    resolve()
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    null
                }
            } ?: extras

            val addons = AddonRepository.uiState.value.addons.enabledAddons()
            val subtitleAddons = addons.filter { addon ->
                val manifest = addon.manifest ?: return@filter false
                val subtitleResource = manifest.resources.find { it.name.isSubtitleResourceName() }
                    ?: return@filter false
                subtitleResource.supportsSubtitleType(requestType, videoId)
            }

            // Every addon is asked at once, so the wait is the slowest addon rather than the sum of
            // all of them. Results are still joined in addon order: the list the user sees, and the
            // tie-breaks auto-selection makes over it, do not depend on which addon answered first.
            val allSubs = coroutineScope {
                subtitleAddons.map { addon ->
                    async { fetchFromAddon(addon, requestType, videoId, requestExtras) }
                }.awaitAll().flatten()
            }

            _addonSubtitles.value = allSubs
            if (allSubs.isEmpty() && addons.any { addon ->
                    addon.manifest?.resources?.any { resource -> resource.name.isSubtitleResourceName() } == true
                }
            ) {
                _error.value = getString(Res.string.compose_player_no_subtitles_found)
            }
            _isLoading.value = false
        }
        return activeFetchJob!!
    }

    private suspend fun fetchFromAddon(
        addon: ManagedAddon,
        requestType: String,
        videoId: String,
        extras: SubtitleRequestExtras?,
    ): List<AddonSubtitle> {
        val manifest = addon.manifest ?: return emptyList()
        val subtitleUrl = buildAddonResourceUrl(
            manifestUrl = manifest.transportUrl,
            resource = "subtitles",
            type = requestType,
            id = videoId,
            extraPathSegment = extras?.toPathSegment(),
        )
        return try {
            // Bounded so one hung addon cannot hold the whole list back now that they are joined.
            val response = withTimeoutOrNull(ADDON_SUBTITLE_TIMEOUT_MS) {
                withContext(Dispatchers.Default) { httpGetText(subtitleUrl) }
            } ?: return emptyList()
            val subtitlesArray = json.parseToJsonElement(response).jsonObject["subtitles"]?.jsonArray
                ?: return emptyList()
            val subs = mutableListOf<AddonSubtitle>()
            for (element in subtitlesArray) {
                val obj = element.jsonObject
                val id = obj.stringValue("id") ?: "${manifest.id}_${subs.size}"
                val url = obj.stringValue("url") ?: continue
                val rawLang = obj.subtitleLanguage() ?: "unknown"
                val normalizedLang = normalizeLanguageCode(rawLang) ?: rawLang
                subs.add(
                    AddonSubtitle(
                        id = id,
                        url = url,
                        language = normalizedLang,
                        display = getString(
                            Res.string.player_addon_subtitle_display_format,
                            getLanguageLabelForCode(rawLang),
                            addon.displayTitle,
                        ),
                        addonName = addon.displayTitle,
                    )
                )
            }
            subs
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            emptyList()
        }
    }

    fun clear() {
        activeFetchJob?.cancel()
        _addonSubtitles.value = emptyList()
        _isLoading.value = false
        _error.value = null
    }
}

private fun canonicalSubtitleType(type: String): String = canonicalSubtitleRequestType(type)

private fun String.isSubtitleResourceName(): Boolean =
    equals("subtitles", ignoreCase = true) || equals("subtitle", ignoreCase = true)

private fun AddonResource.supportsSubtitleType(type: String, videoId: String): Boolean {
    val canonicalType = canonicalSubtitleType(type)
    val typeMatches = types.isEmpty() || types.any { declaredType ->
        canonicalSubtitleType(declaredType).equals(canonicalType, ignoreCase = true)
    }
    if (!typeMatches) return false
    return idPrefixes.isEmpty() || idPrefixes.any { prefix -> videoId.startsWith(prefix) }
}

private fun JsonObject.subtitleLanguage(): String? =
    stringValue("lang")
        ?: stringValue("language")
        ?: stringValue("languageCode")
        ?: stringValue("locale")
        ?: stringValue("label")

private fun JsonObject.stringValue(name: String): String? =
    this[name]
        ?.jsonPrimitive
        ?.contentOrNull
        ?.trim()
        ?.takeIf { it.isNotBlank() }
