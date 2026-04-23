package com.nuvio.app.features.addons

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.nuvio.app.core.ui.NuvioIconActionButton
import com.nuvio.app.core.ui.NuvioInfoBadge
import com.nuvio.app.core.ui.NuvioInputField
import com.nuvio.app.core.ui.NuvioPrimaryButton
import com.nuvio.app.core.ui.NuvioScreen
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.NuvioSectionLabel
import com.nuvio.app.core.ui.NuvioStatusModal
import com.nuvio.app.core.ui.NuvioSurfaceCard
import kotlinx.coroutines.launch

@Composable
fun AddonsScreen(
    modifier: Modifier = Modifier,
    title: String = "Addons",
    onBack: (() -> Unit)? = null,
) {
    NuvioScreen(modifier = modifier) {
        stickyHeader {
            NuvioScreenHeader(
                title = title,
                onBack = onBack,
            ) {
            }
        }
        item {
            AddonsSettingsPageContent()
        }
    }
}

@Composable
internal fun AddonsSettingsPageContent(
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(Unit) {
        AddonRepository.initialize()
    }

    val uiState by AddonRepository.uiState.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    val coroutineScope = rememberCoroutineScope()
    var addonUrl by rememberSaveable { mutableStateOf("") }
    var formMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var installModalState by remember { mutableStateOf<AddonInstallModalState?>(null) }

    val overview = remember(uiState.addons) { uiState.addons.toOverview() }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionHeader("OVERVIEW")
        OverviewCard(overview = overview)

        SectionHeader("ADD ADDON")
        AddAddonCard(
            addonUrl = addonUrl,
            formMessage = formMessage,
            onAddonUrlChange = {
                addonUrl = it
                formMessage = null
            },
            onAddClick = {
                val requestedUrl = addonUrl.trim()
                if (requestedUrl.isBlank()) {
                    formMessage = "Enter an addon URL."
                    return@AddAddonCard
                }

                formMessage = null
                installModalState = AddonInstallModalState.Checking
                coroutineScope.launch {
                    val result = AddonRepository.addAddon(requestedUrl)
                    installModalState = when (result) {
                        is AddAddonResult.Success -> {
                            addonUrl = ""
                            AddonInstallModalState.Success(result.manifest.name)
                        }

                        is AddAddonResult.Error -> {
                            AddonInstallModalState.Error(result.message)
                        }
                    }
                }
            },
        )

        SectionHeader("INSTALLED ADDONS")
        if (uiState.addons.isEmpty()) {
            EmptyStateCard()
        } else {
            val lastIndex = uiState.addons.lastIndex
            uiState.addons.forEachIndexed { index, addon ->
                val manifest = addon.manifest
                val behaviorHints = manifest?.behaviorHints
                val showConfigureAction = behaviorHints?.configurable == true || behaviorHints?.configurationRequired == true
                val configureUrl = addon.manifestUrl.toConfigureUrl()
                InstalledAddonCard(
                    addon = addon,
                    onMoveUpClick = if (index > 0) {
                        { AddonRepository.moveAddon(index, index - 1) }
                    } else {
                        null
                    },
                    onMoveDownClick = if (index < lastIndex) {
                        { AddonRepository.moveAddon(index, index + 1) }
                    } else {
                        null
                    },
                    onRefreshClick = { AddonRepository.refreshAddon(addon.manifestUrl) },
                    onConfigureClick = if (showConfigureAction && !configureUrl.isNullOrBlank()) {
                        {
                            runCatching {
                                uriHandler.openUri(configureUrl)
                            }
                        }
                    } else {
                        null
                    },
                    onDeleteClick = { AddonRepository.removeAddon(addon.manifestUrl) },
                )
            }
        }
    }

    val modalState = installModalState
    if (modalState != null) {
        NuvioStatusModal(
            title = modalState.title,
            message = modalState.message,
            isVisible = true,
            isBusy = modalState.isBusy,
            confirmText = modalState.confirmText,
            onConfirm = {
                if (!modalState.isBusy) {
                    installModalState = null
                }
            },
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    NuvioSectionLabel(text = text)
}

@Composable
private fun OverviewCard(overview: AddonOverview) {
    NuvioSurfaceCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OverviewStat(
                value = overview.totalAddons.toString(),
                label = "Addons",
                modifier = Modifier.weight(1f),
            )
            VerticalSeparator()
            OverviewStat(
                value = overview.activeAddons.toString(),
                label = "Active",
                modifier = Modifier.weight(1f),
            )
            VerticalSeparator()
            OverviewStat(
                value = overview.totalCatalogs.toString(),
                label = "Catalogs",
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun OverviewStat(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun VerticalSeparator() {
    Box(
        modifier = Modifier
            .padding(horizontal = 10.dp)
            .width(1.dp)
            .height(64.dp)
            .background(MaterialTheme.colorScheme.outline),
    )
}

@Composable
private fun AddAddonCard(
    addonUrl: String,
    formMessage: String?,
    onAddonUrlChange: (String) -> Unit,
    onAddClick: () -> Unit,
) {
    NuvioSurfaceCard {
        NuvioInputField(
            value = addonUrl,
            onValueChange = onAddonUrlChange,
            placeholder = "Addon URL",
        )
        Spacer(modifier = Modifier.height(18.dp))
        NuvioPrimaryButton(
            text = "Install Addon",
            enabled = addonUrl.isNotBlank(),
            onClick = onAddClick,
        )
        formMessage?.let { message ->
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

private sealed interface AddonInstallModalState {
    val title: String
    val message: String
    val confirmText: String
    val isBusy: Boolean

    data object Checking : AddonInstallModalState {
        override val title: String = "Checking Addon"
        override val message: String = "Validating the manifest URL and loading addon details before install."
        override val confirmText: String = "Installing"
        override val isBusy: Boolean = true
    }

    data class Success(
        private val addonName: String,
    ) : AddonInstallModalState {
        override val title: String = "Addon Installed"
        override val message: String = "$addonName was validated and added successfully."
        override val confirmText: String = "Done"
        override val isBusy: Boolean = false
    }

    data class Error(
        private val reason: String,
    ) : AddonInstallModalState {
        override val title: String = "Install Failed"
        override val message: String = reason
        override val confirmText: String = "Close"
        override val isBusy: Boolean = false
    }
}

@Composable
private fun EmptyStateCard() {
    NuvioSurfaceCard {
        Text(
            text = "No addons installed yet.",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Add a manifest URL to start loading catalogs, metadata, streams or subtitles into Nuvio.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun InstalledAddonCard(
    addon: ManagedAddon,
    onMoveUpClick: (() -> Unit)?,
    onMoveDownClick: (() -> Unit)?,
    onRefreshClick: () -> Unit,
    onConfigureClick: (() -> Unit)?,
    onDeleteClick: () -> Unit,
) {
    val manifest = addon.manifest

    NuvioSurfaceCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
        ) {
            AddonIconBadge(
                imageUrl = manifest?.logoUrl,
                icon = Icons.Rounded.Extension,
                tint = if (manifest != null) Color(0xFF71BDE8) else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = addon.displayTitle,
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                manifest?.version?.let { version ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Version $version",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            onMoveUpClick?.let { onMoveUp ->
                NuvioIconActionButton(
                    icon = Icons.Rounded.ArrowUpward,
                    contentDescription = "Move addon up",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    onClick = onMoveUp,
                )
            }
            onMoveDownClick?.let { onMoveDown ->
                NuvioIconActionButton(
                    icon = Icons.Rounded.ArrowDownward,
                    contentDescription = "Move addon down",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    onClick = onMoveDown,
                )
            }
            NuvioIconActionButton(
                icon = Icons.Rounded.Refresh,
                contentDescription = "Refresh addon",
                tint = MaterialTheme.colorScheme.primary,
                onClick = onRefreshClick,
            )
            onConfigureClick?.let { onConfigure ->
                NuvioIconActionButton(
                    icon = Icons.Rounded.Settings,
                    contentDescription = "Configure addon",
                    tint = MaterialTheme.colorScheme.tertiary,
                    onClick = onConfigure,
                )
            }
            NuvioIconActionButton(
                icon = Icons.Rounded.Delete,
                contentDescription = "Delete addon",
                tint = MaterialTheme.colorScheme.error,
                onClick = onDeleteClick,
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Spacer(modifier = Modifier.height(18.dp))

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            NuvioInfoBadge(
                text = when {
                    addon.isRefreshing -> "Refreshing"
                    manifest != null -> "Active"
                    else -> "Unavailable"
                },
            )
            manifest?.let {
                NuvioInfoBadge(text = "${it.resources.size} resources")
                NuvioInfoBadge(text = "${it.catalogs.size} catalogs")
                if (it.behaviorHints.configurable) {
                    NuvioInfoBadge(text = "Configurable")
                }
            }
        }

        when {
            addon.isRefreshing -> {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Loading manifest details...",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            addon.errorMessage != null && manifest == null -> {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = addon.errorMessage,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            manifest != null -> {
                Spacer(modifier = Modifier.height(16.dp))
                manifest.description.takeIf { it.isNotBlank() }?.let { description ->
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(modifier = Modifier.height(14.dp))
                }

                Text(
                    text = manifestSummary(manifest),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                addon.errorMessage?.let { staleError ->
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = staleError,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun AddonIconBadge(
    imageUrl: String?,
    icon: ImageVector,
    tint: Color,
) {
    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (!imageUrl.isNullOrBlank()) {
            AsyncImage(
                model = imageUrl,
                contentDescription = null,
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(34.dp),
            )
        }
    }
}

private fun manifestSummary(manifest: AddonManifest): String {
    val resources = manifest.resources.joinToString(separator = ", ") { it.name }
    val types = manifest.types.joinToString(separator = " / ") { it.replaceFirstChar(Char::uppercase) }
    return buildString {
        append(types)
        append(" • ")
        append(resources)
        if (manifest.idPrefixes.isNotEmpty()) {
            append(" • ")
            append("${manifest.idPrefixes.size} id rules")
        }
        if (manifest.behaviorHints.p2p) {
            append(" • P2P")
        }
    }
}

private fun String.toConfigureUrl(): String {
    val base = substringBefore("?").trimEnd('/')
    return if (base.endsWith("/manifest.json")) {
        base.removeSuffix("/manifest.json") + "/configure"
    } else {
        "$base/configure"
    }
}
