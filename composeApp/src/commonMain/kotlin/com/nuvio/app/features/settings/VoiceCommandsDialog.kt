package com.nuvio.app.features.settings

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioModalDialog
import com.nuvio.app.core.ui.NuvioPrimaryButton
import com.nuvio.app.core.ui.NuvioTextField
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.voice.VoiceCommandKind
import com.nuvio.app.features.voice.VoiceCommandPhrases
import com.nuvio.app.features.voice.VoiceControlRepository

/**
 * fork:voice-control - Settings > Voice control > Commands: every action with its built-in phrases,
 * the person's own phrases for it, and a switch that turns the built-in ones off, which makes the
 * person's phrases the only ones (a rebind). Only one action's "add" box is open at a time.
 */
@Composable
internal fun VoiceCommandsDialog(phrases: VoiceCommandPhrases, onDismiss: () -> Unit) {
    var adding by remember { mutableStateOf<VoiceCommandKind?>(null) }
    NuvioModalDialog(
        onDismissRequest = onDismiss,
        title = "Voice commands",
        subtitle = "Add a phrase you will remember, or one the speech model hears better in your accent. " +
            "Turn off the built-in phrases to use only yours.",
        maxWidth = 760.dp,
        actions = {
            SettingsGlassButton(
                text = "Reset all",
                enabled = phrases != VoiceCommandPhrases.None,
                onClick = VoiceControlRepository::resetPhrases,
            )
            NuvioPrimaryButton(text = "Done", onClick = onDismiss)
        },
    ) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            VoiceCommandKind.entries.groupBy { it.group }.forEach { (group, kinds) ->
                Text(
                    text = group,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.nuvio.colors.textMuted,
                    modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                )
                kinds.forEach { kind ->
                    VoiceCommandRow(
                        kind = kind,
                        phrases = phrases,
                        adding = adding == kind,
                        onAdd = { adding = kind },
                        onAddDone = { adding = null },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VoiceCommandRow(
    kind: VoiceCommandKind,
    phrases: VoiceCommandPhrases,
    adding: Boolean,
    onAdd: () -> Unit,
    onAddDone: () -> Unit,
) {
    val colors = MaterialTheme.nuvio.colors
    val builtInOn = kind !in phrases.builtInOff
    val own = phrases.phrasesFor(kind)
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, colors.borderSubtle, shape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(kind.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = colors.textPrimary)
                Text(
                    text = kind.examples,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (builtInOn) colors.textMuted else colors.textDisabled,
                    textDecoration = if (builtInOn) null else TextDecoration.LineThrough,
                )
            }
            Text(
                text = "Built-in",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textMuted,
                modifier = Modifier.padding(end = 8.dp),
            )
            // Off with nothing of their own would leave the action unreachable by voice.
            SettingsSquareSwitch(
                checked = builtInOn,
                enabled = !builtInOn || own.isNotEmpty(),
                onCheckedChange = { VoiceControlRepository.setBuiltInPhrases(kind, it) },
            )
        }
        if (own.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                own.forEach { phrase -> PhraseChip(phrase, onRemove = { VoiceControlRepository.removePhrase(kind, phrase) }) }
            }
        }
        if (adding) {
            AddPhraseBox(kind, onDone = onAddDone)
        } else {
            Text(
                text = "+ Add a phrase",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.accent,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onAdd).padding(vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun AddPhraseBox(kind: VoiceCommandKind, onDone: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    fun add() {
        problem = VoiceControlRepository.addPhrase(kind, text)
        if (problem == null) onDone()
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NuvioTextField(
                value = text,
                onValueChange = {
                    text = it
                    problem = null
                },
                modifier = Modifier.weight(1f),
                placeholder = when (kind.takes) {
                    VoiceCommandKind.Takes.Query -> "e.g. “find on tube”"
                    VoiceCommandKind.Takes.Duration -> if (kind == VoiceCommandKind.SeekBack) "e.g. “back up”" else "e.g. “jump on”"
                    VoiceCommandKind.Takes.Rate -> "e.g. “play at”"
                    VoiceCommandKind.Takes.Number -> "e.g. “go with”"
                    VoiceCommandKind.Takes.Nothing -> "e.g. “hold it”"
                },
                isError = problem != null,
                supportingText = problem ?: kind.usage,
                onImeAction = ::add,
            )
            SettingsGlassButton(text = "Add", accent = true, enabled = text.isNotBlank(), onClick = ::add)
        }
        Text(
            text = "Cancel",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.nuvio.colors.textMuted,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onDone).padding(vertical = 2.dp),
        )
    }
}

@Composable
private fun PhraseChip(phrase: String, onRemove: () -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    val shape = RoundedCornerShape(50)
    Row(
        modifier = Modifier
            .clip(shape)
            .border(1.dp, colors.accent.copy(alpha = 0.6f), shape)
            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("“$phrase”", style = MaterialTheme.typography.bodyMedium, color = colors.textPrimary)
        Icon(
            imageVector = Icons.Rounded.Close,
            contentDescription = "Remove “$phrase”",
            tint = colors.textMuted,
            modifier = Modifier.size(22.dp).clip(RoundedCornerShape(50)).clickable(onClick = onRemove).padding(3.dp),
        )
    }
}
