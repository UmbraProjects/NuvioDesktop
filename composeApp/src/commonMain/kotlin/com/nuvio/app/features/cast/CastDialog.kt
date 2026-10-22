package com.nuvio.app.features.cast

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioModalDialog
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.nuvio
import kotlinx.coroutines.launch

/** Hosts the "Play on device" picker once, at the app root. See [CastController]. */
@Composable
fun CastDialogHost() {
    val pending by CastController.pending.collectAsStateWithLifecycle()
    pending?.let { CastDialog(request = it, onDismiss = CastController::dismiss) }
}

@Composable
private fun CastDialog(
    request: CastRequest,
    onDismiss: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val scope = rememberCoroutineScope()
    var scanGeneration by remember { mutableIntStateOf(0) }
    var scanning by remember { mutableStateOf(true) }
    var devices by remember { mutableStateOf<List<CastDevice>>(emptyList()) }
    var startingOn by remember { mutableStateOf<CastDevice?>(null) }
    val dolbyVisionAvailable = remember { DeviceCastPlatform.dolbyVisionRemuxAvailable() }
    var dolbyVision by remember { mutableStateOf(DeviceCastPlatform.dolbyVisionEnabled && dolbyVisionAvailable) }

    LaunchedEffect(scanGeneration) {
        scanning = true
        devices = runCatching { DeviceCastPlatform.discover() }.getOrDefault(emptyList())
        scanning = false
    }

    fun castTo(device: CastDevice) {
        if (startingOn != null) return
        startingOn = device
        scope.launch {
            when (val result = DeviceCastPlatform.cast(device, request, dolbyVision)) {
                CastStartResult.Started -> {
                    NuvioToastController.show("Playing on ${device.name}")
                    onDismiss()
                }
                is CastStartResult.Failed -> {
                    NuvioToastController.show(result.message)
                    startingOn = null
                }
            }
        }
    }

    NuvioModalDialog(
        onDismissRequest = onDismiss,
        title = "Play on device",
        subtitle = request.title,
        maxWidth = 520.dp,
        actions = {
            TextButton(onClick = { scanGeneration++ }, enabled = !scanning && startingOn == null) { Text("Search again") }
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            devices.forEach { device ->
                CastDeviceRow(
                    device = device,
                    starting = startingOn == device,
                    enabled = startingOn == null,
                    onClick = { castTo(device) },
                )
            }
            when {
                scanning -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(vertical = 6.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("Looking for TVs and players on your network…", color = tokens.colors.textMuted)
                }
                devices.isEmpty() -> Text(
                    "No devices found. The TV or player must be on, on the same network, and have DLNA " +
                        "(media sharing / UPnP) enabled. Windows may also ask to let Nuvio through its firewall.",
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.textMuted,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Dolby Vision (beta)", style = MaterialTheme.typography.bodyLarge, color = tokens.colors.textPrimary)
                Text(
                    text = if (dolbyVisionAvailable) {
                        "Repackages the stream as MP4 so TVs that ignore Dolby Vision in MKV can use it. " +
                            "Seeking is slower and less tested. Kodi always gets the original stream."
                    } else {
                        "Needs ffmpeg installed on this PC (for example: winget install Gyan.FFmpeg)."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.textMuted,
                )
            }
            Switch(
                checked = dolbyVision,
                enabled = dolbyVisionAvailable && startingOn == null,
                onCheckedChange = {
                    dolbyVision = it
                    DeviceCastPlatform.dolbyVisionEnabled = it
                },
            )
        }
    }
}

@Composable
private fun CastDeviceRow(
    device: CastDevice,
    starting: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                brush = SolidColor(tokens.colors.surfaceCard.copy(alpha = tokens.opacity.medium)),
                shape = tokens.shapes.compactCard,
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(imageVector = Icons.Rounded.Tv, contentDescription = null, tint = tokens.colors.textMuted)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = device.name,
                style = MaterialTheme.typography.bodyLarge,
                color = tokens.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            device.model?.takeIf { it.isNotBlank() && it != device.name }?.let {
                Text(text = it, style = MaterialTheme.typography.bodySmall, color = tokens.colors.textMuted, maxLines = 1)
            }
        }
        if (starting) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
            Icon(
                imageVector = Icons.Rounded.Cast,
                contentDescription = "Play here",
                tint = if (enabled) tokens.colors.accent else tokens.colors.textDisabled,
            )
        }
    }
}
