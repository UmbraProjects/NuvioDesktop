package com.nuvio.app.features.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.AppTheme
import com.nuvio.app.core.ui.NuvioFieldIconButton
import com.nuvio.app.core.ui.NuvioNumberStepper
import com.nuvio.app.core.ui.NuvioTextField
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.core.ui.nuvio
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

/**
 * Renders the shared inputs offscreen so the design can be reviewed as a picture rather than by
 * building and hunting for the row. Not an assertion - it only writes a PNG.
 */
@OptIn(ExperimentalComposeUiApi::class)
class SettingsInputFieldsPreviewRender {

    @Test
    fun `render settings input preview`() {
        val scene = ImageComposeScene(width = 1180, height = 1180, density = Density(1.5f)) {
            NuvioTheme(darkTheme = true, appTheme = AppTheme.CRIMSON) {
                val tokens = MaterialTheme.nuvio
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(tokens.colors.background)
                        .padding(28.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    SectionLabel("Secret field - settings row")
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Personal API key",
                            style = MaterialTheme.typography.bodyLarge,
                            color = tokens.colors.textPrimary,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = "Enter your TMDB v3 API key.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = tokens.colors.textMuted,
                        )
                    }
                    NuvioTextField(
                        value = "8f2c19a4d77b0e5136ca9be4f0d8127e",
                        onValueChange = {},
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = "API Key",
                        secret = true,
                    )

                    SectionLabel("Empty, error, disabled")
                    NuvioTextField(
                        value = "",
                        onValueChange = {},
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = "API Key",
                        secret = true,
                    )
                    NuvioTextField(
                        value = "#not-a-hex",
                        onValueChange = {},
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = "Accent colour",
                        isError = true,
                        supportingText = "Example: #1E88E5",
                    )
                    NuvioTextField(
                        value = "Needs an API key first",
                        onValueChange = {},
                        modifier = Modifier.fillMaxWidth(),
                        enabled = false,
                    )

                    SectionLabel("Leading icon, trailing action, multi-line")
                    NuvioTextField(
                        value = "",
                        onValueChange = {},
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = "Search this catalog",
                        leadingContent = {
                            Icon(
                                Icons.Rounded.Search,
                                contentDescription = null,
                                tint = tokens.colors.textMuted,
                                modifier = Modifier.size(20.dp),
                            )
                        },
                    )
                    NuvioTextField(
                        value = "Interstellar",
                        onValueChange = {},
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = "Search TMDB",
                        trailingContent = {
                            NuvioFieldIconButton(
                                icon = Icons.Rounded.Refresh,
                                contentDescription = "Search",
                                onClick = {},
                            )
                        },
                    )
                    NuvioTextField(
                        value = "https://example.com/image/{tmdbId}/poster.jpg?lang={lang}",
                        onValueChange = {},
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = false,
                        minLines = 2,
                        maxLines = 6,
                    )

                    Spacer(Modifier.height(2.dp))
                    SectionLabel("Number input")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        NuvioNumberStepper(
                            value = 900.0,
                            step = 5.0,
                            min = -1000.0,
                            max = 1000.0,
                            onChange = {},
                            format = { if (it > 0) "+${it.toInt()}" else it.toInt().toString() },
                            valueColor = tokens.colors.accent,
                        )
                        NuvioNumberStepper(
                            value = -40.0,
                            step = 5.0,
                            min = -1000.0,
                            max = 1000.0,
                            onChange = {},
                            format = { if (it > 0) "+${it.toInt()}" else it.toInt().toString() },
                            valueColor = MaterialTheme.colorScheme.error,
                        )
                        NuvioNumberStepper(
                            value = 0.0,
                            step = 5.0,
                            min = -1000.0,
                            max = 1000.0,
                            onChange = {},
                            format = { if (it > 0) "+${it.toInt()}" else it.toInt().toString() },
                            valueColor = tokens.colors.textMuted,
                        )
                        NuvioNumberStepper(
                            value = 12.0,
                            step = 1.0,
                            min = 0.0,
                            max = 200.0,
                            onChange = {},
                            format = { "${it.toInt()} GB" },
                            fieldWidth = 78.dp,
                        )
                        NuvioNumberStepper(
                            value = 0.0,
                            step = 1.0,
                            min = 0.0,
                            max = 200.0,
                            enabled = false,
                            onChange = {},
                            format = { "${it.toInt()} GB" },
                            fieldWidth = 78.dp,
                        )
                    }
                    Spacer(Modifier.width(1.dp))
                }
            }
        }
        val image = scene.render()
        val data = image.encodeToData(EncodedImageFormat.PNG) ?: error("encode failed")
        val out = File("build/settings-inputs-preview.png").absoluteFile
        out.parentFile.mkdirs()
        out.writeBytes(data.bytes)
        println("PREVIEW_WRITTEN " + out.path)
        scene.close()
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.nuvio.colors.textMuted,
        fontWeight = FontWeight.SemiBold,
    )
}
