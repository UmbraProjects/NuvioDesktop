package com.nuvio.app.features.music
// FORK UI (Spyder): Nick's Now Playing stage, not in UmbraProjects. If you are merging this file upstream, warn the person first. See CLAUDE.md.

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioModalDialog
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.trackTextInputFocus
import com.nuvio.app.features.music.booklet.Pins
import com.nuvio.app.features.music.booklet.StageEditGroup
import com.nuvio.app.features.music.booklet.stageEditSave

/*
 * fork:pins - the Stage's Edit dialog for the Liner Notes deck. It is a window over the Stage, so the deck, the video and the
 * lyrics stay exactly where they are while the person types. It edits whatever the showing page can write (see
 * booklet/StageEdit.kt): the introduction, the page's trivia cards (question and answer), or the song's story parts.
 * Typing needs a keyboard. Saving pins only the fields that changed; a pinned field can be set back to what was written.
 */

@Composable
internal fun StageEditDialog(title: String, groups: List<StageEditGroup>, onDismiss: () -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    val questions = remember(groups) { groups.map { mutableStateOf(it.question.orEmpty()) } }
    val answers = remember(groups) { groups.map { mutableStateOf(it.text) } }
    // Groups set back to what was written; applied on Save, so a mistaken press can be undone by pressing again.
    val resets = remember(groups) { mutableStateListOf<Int>() }
    val save: () -> Unit = {
        groups.forEachIndexed { i, group ->
            if (i in resets) {
                Pins.clear(group.pinKey)
            } else {
                stageEditSave(group, questions[i].value, answers[i].value)?.let { Pins.save(it.pinKey, it.text, it.question, it.kind) }
            }
        }
        onDismiss()
    }
    NuvioModalDialog(
        onDismissRequest = onDismiss,
        title = title,
        subtitle = null,
        maxWidth = 640.dp,
        modifier = Modifier.width(640.dp),
        actions = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
            TextButton(onClick = save) { Text("Save") }
        },
    ) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            groups.forEachIndexed { i, group ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            group.heading.uppercase(),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.accent,
                            modifier = Modifier.weight(1f),
                        )
                        if (group.pinned) {
                            val willReset = i in resets
                            PinPill(
                                if (willReset) "Will reset: press to keep" else "Reset",
                                colors.textSecondary,
                                { if (willReset) resets.remove(i) else resets.add(i) },
                            )
                        }
                    }
                    if (group.question != null) EditBox("Question", questions[i].value, { questions[i].value = it })
                    EditBox(if (group.question != null) "Answer" else null, answers[i].value, { answers[i].value = it })
                }
            }
        }
    }
}

@Composable
private fun EditBox(label: String?, value: String, onChange: (String) -> Unit) {
    val colors = MaterialTheme.nuvio.colors
    val shape = RoundedCornerShape(10.dp)
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        if (label != null) Text(label, style = MaterialTheme.typography.labelSmall, color = colors.textMuted)
        Box(Modifier.fillMaxWidth().border(1.dp, colors.textMuted.copy(alpha = 0.4f), shape).padding(horizontal = 12.dp, vertical = 8.dp)) {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.textPrimary),
                cursorBrush = SolidColor(colors.accent),
                modifier = Modifier.fillMaxWidth().trackTextInputFocus(),
            )
        }
    }
}
