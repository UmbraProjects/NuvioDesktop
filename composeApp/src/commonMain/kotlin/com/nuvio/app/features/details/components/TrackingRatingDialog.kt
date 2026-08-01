package com.nuvio.app.features.details.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nuvio.app.core.ui.NuvioDialogSurface
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_cancel
import nuvio.composeapp.generated.resources.details_rating_clear
import nuvio.composeapp.generated.resources.details_rating_high_score
import nuvio.composeapp.generated.resources.details_rating_low_score
import org.jetbrains.compose.resources.stringResource

private val RatingAccent = Color(0xFFF2B84B)
private val RatingDialogGradient = Brush.horizontalGradient(
    0f to Color(0xFF181D22),
    0.55f to Color(0xFF15191E),
    1f to Color(0xFF1C1914),
)

/**
 * The shared title-rating picker used before playback and by the post-playback prompt.
 *
 * The palette is intentionally fixed rather than derived from artwork or the selected score. This
 * keeps the panel visually stable while the amber ring moves between values.
 */
@Composable
fun TrackingRatingDialog(
    visible: Boolean,
    kicker: String,
    headline: String,
    body: String,
    isPending: Boolean,
    errorMessage: String?,
    onRate: (Int) -> Unit,
    onDismiss: () -> Unit,
    onClear: (() -> Unit)? = null,
    dismissLabel: String? = null,
) {
    if (!visible) return

    var selectedScore by remember(visible, headline) { mutableStateOf<Int?>(null) }
    val closeLabel = dismissLabel ?: stringResource(Res.string.action_cancel)

    Dialog(
        onDismissRequest = { if (!isPending) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        NuvioDialogSurface(
            modifier = Modifier
                .widthIn(max = 860.dp)
                .fillMaxWidth(0.92f),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(RatingDialogGradient),
            ) {
                IconButton(
                    onClick = onDismiss,
                    enabled = !isPending,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(14.dp)
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.055f)),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = closeLabel,
                        tint = Color(0xFF8F979F),
                        modifier = Modifier.size(19.dp),
                    )
                }

                Column(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 64.dp, end = 64.dp, top = 32.dp, bottom = 26.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Text(
                            text = kicker,
                            color = Color(0xFFB7BDC3),
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            text = headline,
                            color = Color(0xFFF5F6F7),
                            style = MaterialTheme.typography.headlineLarge.copy(
                                fontSize = 28.sp,
                                lineHeight = 34.sp,
                            ),
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = body,
                            color = Color(0xFF929AA2),
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (!errorMessage.isNullOrBlank()) {
                            Text(
                                text = errorMessage,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(Color.White.copy(alpha = 0.035f)),
                    )

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.Black.copy(alpha = 0.08f))
                            .padding(start = 48.dp, end = 48.dp, top = 34.dp, bottom = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        RatingScoreRail(
                            selectedScore = selectedScore,
                            enabled = !isPending,
                            onScoreSelected = { score ->
                                selectedScore = score
                                onRate(score)
                            },
                        )
                        Spacer(Modifier.height(15.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = stringResource(Res.string.details_rating_low_score),
                                color = Color(0xFF858D95),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                text = stringResource(Res.string.details_rating_high_score),
                                color = RatingAccent,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }

                        when {
                            isPending -> {
                                Spacer(Modifier.height(16.dp))
                                CircularProgressIndicator(
                                    modifier = Modifier.size(22.dp),
                                    color = RatingAccent,
                                    strokeWidth = 2.dp,
                                )
                            }
                            onClear != null -> {
                                Spacer(Modifier.height(8.dp))
                                TextButton(
                                    onClick = {
                                        selectedScore = null
                                        onClear()
                                    },
                                ) {
                                    Text(
                                        text = stringResource(Res.string.details_rating_clear),
                                        color = Color(0xFF929AA2),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RatingScoreRail(
    selectedScore: Int?,
    enabled: Boolean,
    onScoreSelected: (Int) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        if (maxWidth >= 620.dp) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                (1..10).forEach { score ->
                    RatingScore(
                        score = score,
                        selected = score == selectedScore,
                        enabled = enabled,
                        onClick = { onScoreSelected(score) },
                    )
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf(1..5, 6..10).forEach { scores ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        scores.forEach { score ->
                            RatingScore(
                                score = score,
                                selected = score == selectedScore,
                                enabled = enabled,
                                onClick = { onScoreSelected(score) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RatingScore(
    score: Int,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(58.dp)
            .clip(CircleShape)
            .background(
                if (selected) RatingAccent.copy(alpha = 0.13f) else Color.Black.copy(alpha = 0.08f),
            )
            .border(
                width = 1.dp,
                color = if (selected) RatingAccent else Color(0xFF3A4148),
                shape = CircleShape,
            )
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = score.toString(),
            color = if (selected) RatingAccent else Color(0xFFF0F2F3),
            style = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp),
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}
