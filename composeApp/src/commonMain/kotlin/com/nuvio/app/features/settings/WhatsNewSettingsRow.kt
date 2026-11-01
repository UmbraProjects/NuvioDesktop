package com.nuvio.app.features.settings

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.ditheredBackground
import com.nuvio.app.features.whatsnew.WhatsNewController
import com.nuvio.app.isDesktop

/**
 * "What's new", beside "Run setup again" on the Account page: brings the newest cards back whether
 * or not they have been seen. In its own file, like the other fork additions, so the Account page
 * only gains one call.
 */
@Composable
internal fun WhatsNewSettingsRow() {
    if (!isDesktop) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .ditheredBackground(SettingsSectionGlassFill, SettingsSectionGlassShape)
            .border(1.dp, SettingsSectionGlassEdge, SettingsSectionGlassShape)
            .padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SettingsRowTextGap),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "What's new",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Show the newest features again, one card each.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SettingsGlassButton(
            text = "Show",
            // Hosted globally, like the setup wizard, so it opens above Settings.
            onClick = WhatsNewController::openFromSettings,
        )
    }
}
