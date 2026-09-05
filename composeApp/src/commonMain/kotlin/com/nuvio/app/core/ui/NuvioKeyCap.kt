package com.nuvio.app.core.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight

/**
 * A single keyboard key rendered as a cap.
 *
 * Shared by the keyboard shortcuts settings page and the setup wizard's controls primer so the two
 * cannot drift apart; the authoritative shortcut *inventory* stays on the shortcuts page.
 */
@Composable
fun NuvioKeyCap(text: String, modifier: Modifier = Modifier) {
    val tokens = MaterialTheme.nuvio
    Surface(
        color = tokens.colors.surface,
        shape = RoundedCornerShape(NuvioTokens.Radius.sm),
        modifier = modifier,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = tokens.colors.textSecondary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .border(
                    BorderStroke(tokens.borders.hairline, tokens.colors.borderSubtle),
                    RoundedCornerShape(NuvioTokens.Radius.sm),
                )
                .padding(horizontal = NuvioTokens.Space.s8, vertical = NuvioTokens.Space.s4),
        )
    }
}
