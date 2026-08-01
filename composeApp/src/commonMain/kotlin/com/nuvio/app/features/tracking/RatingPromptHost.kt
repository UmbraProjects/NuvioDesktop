package com.nuvio.app.features.tracking

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.details.components.TrackingRatingDialog
import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.details_rating_failed
import nuvio.composeapp.generated.resources.details_rating_kicker
import nuvio.composeapp.generated.resources.details_rating_saved
import nuvio.composeapp.generated.resources.details_rating_title
import nuvio.composeapp.generated.resources.rating_prompt_movie_body
import nuvio.composeapp.generated.resources.rating_prompt_not_now
import nuvio.composeapp.generated.resources.rating_prompt_season_finale_body
import nuvio.composeapp.generated.resources.rating_prompt_series_finale_body
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/**
 * Shows the queued "rate what you just finished" prompt.
 *
 * Hosted above the navigation graph rather than inside a screen, because the prompt outlives the
 * player it was queued from: the user finishes something, leaves the player, and gets asked
 * wherever they land. [isSuppressed] lets the caller hold it back while playback is still on
 * screen.
 */
@Composable
fun RatingPromptHost(isSuppressed: Boolean) {
    val request by RatingPromptRepository.pendingRequest.collectAsStateWithLifecycle()
    val connectedProviderIds by TrackingProviderRegistry.connectedProviderIds
        .collectAsStateWithLifecycle()
    val librarySource by remember {
        LibrarySourceRepository.ensureLoaded()
        LibrarySourceRepository.uiState
    }.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var isPending by remember(request) { mutableStateOf(false) }
    var errorMessage by remember(request) { mutableStateOf<String?>(null) }

    val pending = request ?: return
    if (isSuppressed) return

    // Re-checked at display time, not just when queued: the user may have disconnected the service
    // or switched Library source between finishing the title and this prompt being shown.
    val providerId = trackingRatingProviderFor(librarySource)
        ?.takeIf { it in connectedProviderIds } ?: return
    val writer = TrackingProviderRegistry.ratingWriter(providerId) ?: return
    val providerName = trackingRatingProviderDisplayName(providerId)

    val body = stringResource(
        when (pending.reason) {
            RatingPromptReason.MOVIE -> Res.string.rating_prompt_movie_body
            RatingPromptReason.SEASON_FINALE -> Res.string.rating_prompt_season_finale_body
            RatingPromptReason.SERIES_FINALE -> Res.string.rating_prompt_series_finale_body
        },
        providerName,
    )

    TrackingRatingDialog(
        visible = true,
        kicker = stringResource(Res.string.details_rating_kicker),
        headline = stringResource(Res.string.details_rating_title, pending.title),
        body = body,
        isPending = isPending,
        errorMessage = errorMessage,
        dismissLabel = stringResource(Res.string.rating_prompt_not_now),
        onRate = { score ->
            scope.launch {
                isPending = true
                errorMessage = null
                runCatching {
                    writer.setRating(
                        profileId = ProfileRepository.activeProfileId,
                        media = pending.media,
                        rating = score,
                    )
                }.onSuccess { result ->
                    if (result.isComplete) {
                        RatingPromptRepository.dismiss()
                        NuvioToastController.show(
                            getString(Res.string.details_rating_saved, providerName),
                        )
                    } else {
                        // The provider accepted the request but could not address this title, so
                        // nothing was recorded. Saying "saved" here would be a lie.
                        errorMessage = getString(Res.string.details_rating_failed)
                    }
                }.onFailure { error ->
                    errorMessage = error.message ?: getString(Res.string.details_rating_failed)
                }
                isPending = false
            }
        },
        onDismiss = {
            if (!isPending) RatingPromptRepository.dismiss()
        },
    )
}
