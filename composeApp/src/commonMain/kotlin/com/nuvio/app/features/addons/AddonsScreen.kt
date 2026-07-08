package com.nuvio.app.features.addons

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioAsyncImage as AsyncImage
import com.nuvio.app.core.ui.NuvioIconActionButton
import com.nuvio.app.core.ui.NuvioInfoBadge
import com.nuvio.app.core.ui.NuvioInputField
import com.nuvio.app.core.ui.NuvioPrimaryButton
import com.nuvio.app.core.ui.NuvioScreen
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.NuvioSectionLabel
import com.nuvio.app.core.ui.NuvioStatusModal
import com.nuvio.app.core.ui.NuvioSurfaceCard
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.isDesktop
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
fun AddonsScreen(
    modifier: Modifier = Modifier,
    title: String? = null,
    onBack: (() -> Unit)? = null,
) {
    NuvioScreen(modifier = modifier) {
        stickyHeader {
            NuvioScreenHeader(
                title = title ?: stringResource(Res.string.addon_title),
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
    val enterAddonUrlMessage = stringResource(Res.string.addons_error_enter_url)

    val installAddon: () -> Unit = {
        val requestedUrl = addonUrl.trim()
        if (requestedUrl.isBlank()) {
            formMessage = enterAddonUrlMessage
        } else {
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
        }
    }

    if (isDesktop) {
        DesktopAddonsManager(
            addons = uiState.addons,
            addonUrl = addonUrl,
            formMessage = formMessage,
            onAddonUrlChange = {
                addonUrl = it
                formMessage = null
            },
            onAddClick = installAddon,
            openConfigureUrl = { configureUrl ->
                runCatching {
                    uriHandler.openUri(configureUrl)
                }
            },
            modifier = modifier,
        )
    } else {
        val overview = remember(uiState.addons) { uiState.addons.toOverview() }

        Column(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SectionHeader(stringResource(Res.string.addons_section_overview))
            OverviewCard(overview = overview)

            SectionHeader(stringResource(Res.string.addons_section_add_addon))
            AddAddonCard(
                addonUrl = addonUrl,
                formMessage = formMessage,
                onAddonUrlChange = {
                    addonUrl = it
                    formMessage = null
                },
                onAddClick = installAddon,
            )

            SectionHeader(stringResource(Res.string.addons_section_installed))
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
                        onEnabledChange = { enabled ->
                            AddonRepository.setAddonEnabled(addon.manifestUrl, enabled)
                        },
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
    }

    val modalState = installModalState
    if (modalState != null) {
        val modalTitle = when (modalState) {
            AddonInstallModalState.Checking -> stringResource(Res.string.addons_modal_checking_title)
            is AddonInstallModalState.Success -> stringResource(Res.string.addons_modal_success_title)
            is AddonInstallModalState.Error -> stringResource(Res.string.addons_modal_failure_title)
        }
        val modalMessage = when (modalState) {
            AddonInstallModalState.Checking -> stringResource(Res.string.addons_modal_checking_message)
            is AddonInstallModalState.Success -> stringResource(
                Res.string.addons_modal_success_message,
                modalState.addonName,
            )
            is AddonInstallModalState.Error -> modalState.reason
        }
        val modalConfirmText = when (modalState) {
            AddonInstallModalState.Checking -> stringResource(Res.string.addon_installing)
            is AddonInstallModalState.Success -> stringResource(Res.string.action_done)
            is AddonInstallModalState.Error -> stringResource(Res.string.action_close)
        }
        NuvioStatusModal(
            title = modalTitle,
            message = modalMessage,
            isVisible = true,
            isBusy = modalState.isBusy,
            confirmText = modalConfirmText,
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
                label = stringResource(Res.string.addons_overview_addons),
                modifier = Modifier.weight(1f),
            )
            VerticalSeparator()
            OverviewStat(
                value = overview.activeAddons.toString(),
                label = stringResource(Res.string.addons_overview_active),
                modifier = Modifier.weight(1f),
            )
            VerticalSeparator()
            OverviewStat(
                value = overview.totalCatalogs.toString(),
                label = stringResource(Res.string.addons_overview_catalogs),
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
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
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
            .height(44.dp)
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NuvioInputField(
                value = addonUrl,
                onValueChange = onAddonUrlChange,
                placeholder = stringResource(Res.string.addons_input_placeholder),
                modifier = Modifier.weight(1f),
            )
            NuvioPrimaryButton(
                text = stringResource(Res.string.addons_install_button),
                enabled = addonUrl.isNotBlank(),
                onClick = onAddClick,
                modifier = Modifier.width(148.dp),
            )
        }
        formMessage?.let { message ->
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

private sealed interface AddonInstallModalState {
    val isBusy: Boolean

    data object Checking : AddonInstallModalState {
        override val isBusy: Boolean = true
    }

    data class Success(
        val addonName: String,
    ) : AddonInstallModalState {
        override val isBusy: Boolean = false
    }

    data class Error(
        val reason: String,
    ) : AddonInstallModalState {
        override val isBusy: Boolean = false
    }
}

private enum class DesktopAddonSort(val label: String) {
    Order("Order"),
    Name("Name"),
    Resources("Resources"),
    Catalogs("Catalogs"),
    Status("Status"),
}

private data class DesktopAddonRow(
    val addon: ManagedAddon,
    val index: Int,
)

@Composable
private fun DesktopAddonsManager(
    addons: List<ManagedAddon>,
    addonUrl: String,
    formMessage: String?,
    onAddonUrlChange: (String) -> Unit,
    onAddClick: () -> Unit,
    openConfigureUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var activeOnly by rememberSaveable { mutableStateOf(false) }
    var catalogsOnly by rememberSaveable { mutableStateOf(false) }
    var configurableOnly by rememberSaveable { mutableStateOf(false) }
    var showAddAddon by rememberSaveable { mutableStateOf(false) }
    var sort by rememberSaveable { mutableStateOf(DesktopAddonSort.Order) }
    // Computed inline (not remembered) so it re-filters on every keystroke — reading `query`
    // here subscribes this composable directly to the search text.
    val trimmedQuery = query.trim()
    val visibleRows = addons
        .mapIndexed { index, addon -> DesktopAddonRow(addon = addon, index = index) }
        .filter { row ->
            val addon = row.addon
            val manifest = addon.manifest
            val matchesQuery = trimmedQuery.isBlank() ||
                addon.displayTitle.contains(trimmedQuery, ignoreCase = true) ||
                addon.manifestUrl.contains(trimmedQuery, ignoreCase = true) ||
                manifest?.name?.contains(trimmedQuery, ignoreCase = true) == true ||
                manifest?.description?.contains(trimmedQuery, ignoreCase = true) == true
            val matchesActive = !activeOnly || addon.isActive
            val matchesCatalogs = !catalogsOnly || (manifest?.catalogs?.isNotEmpty() == true)
            val matchesConfigurable = !configurableOnly ||
                manifest?.behaviorHints?.configurable == true ||
                manifest?.behaviorHints?.configurationRequired == true
            matchesQuery && matchesActive && matchesCatalogs && matchesConfigurable
        }
        .let { rows ->
            when (sort) {
                DesktopAddonSort.Order -> rows.sortedBy(DesktopAddonRow::index)
                DesktopAddonSort.Name -> rows.sortedBy { it.addon.displayTitle.lowercase() }
                DesktopAddonSort.Resources -> rows.sortedByDescending { it.addon.manifest?.resources?.size ?: 0 }
                DesktopAddonSort.Catalogs -> rows.sortedByDescending { it.addon.manifest?.catalogs?.size ?: 0 }
                DesktopAddonSort.Status -> rows.sortedByDescending { it.addon.isActive }
            }
        }

    val tokens = MaterialTheme.nuvio
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Title row: "Installed Addons  N installed"  ····  [+ Add Addon]
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(Res.string.addons_section_installed),
                    style = MaterialTheme.typography.headlineSmall,
                    color = tokens.colors.textPrimary,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    maxLines = 1,
                )
                Text(
                    text = "${addons.size} installed",
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.colors.textMuted,
                    modifier = Modifier.padding(bottom = 2.dp),
                    maxLines = 1,
                )
            }
            DesktopAddAddonButton(
                selected = showAddAddon,
                onClick = { showAddAddon = !showAddAddon },
            )
        }
        // Controls row: search · segmented filters · order
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DesktopAddonSearchField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f),
            )
            DesktopSegmentedFilters(
                activeOnly = activeOnly,
                onActiveOnlyChange = { activeOnly = it },
                catalogsOnly = catalogsOnly,
                onCatalogsOnlyChange = { catalogsOnly = it },
                configurableOnly = configurableOnly,
                onConfigurableOnlyChange = { configurableOnly = it },
            )
            DesktopAddonSortMenu(
                sort = sort,
                onSortChange = { sort = it },
                modifier = Modifier.width(116.dp),
            )
        }
        if (showAddAddon) {
            DesktopAddAddonPopover(
                addonUrl = addonUrl,
                formMessage = formMessage,
                onAddonUrlChange = onAddonUrlChange,
                onAddClick = onAddClick,
            )
        }
        DesktopAddonsTable(
            rows = visibleRows,
            totalAddonCount = addons.size,
            openConfigureUrl = openConfigureUrl,
        )
    }
}

/**
 * Connected segmented control holding the three list filters. Each segment toggles independently
 * (they narrow the list, so combining them is meaningful) and highlights with the accent tint when
 * on, matching the mockup's selected "Active" segment.
 */
@Composable
private fun DesktopSegmentedFilters(
    activeOnly: Boolean,
    onActiveOnlyChange: (Boolean) -> Unit,
    catalogsOnly: Boolean,
    onCatalogsOnlyChange: (Boolean) -> Unit,
    configurableOnly: Boolean,
    onConfigurableOnlyChange: (Boolean) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Surface(
        modifier = Modifier.height(40.dp),
        shape = RoundedCornerShape(10.dp),
        color = tokens.colors.surfaceCard,
        border = androidx.compose.foundation.BorderStroke(1.dp, tokens.colors.borderDefault),
    ) {
        Row(
            modifier = Modifier.padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DesktopSegmentedFilterSegment("Active", activeOnly) { onActiveOnlyChange(!activeOnly) }
            DesktopSegmentedFilterSegment("Catalogs", catalogsOnly) { onCatalogsOnlyChange(!catalogsOnly) }
            DesktopSegmentedFilterSegment("Configurable", configurableOnly) { onConfigurableOnlyChange(!configurableOnly) }
        }
    }
}

@Composable
private fun DesktopSegmentedFilterSegment(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(if (selected) tokens.colors.accent.copy(alpha = 0.16f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) tokens.colors.accent else tokens.colors.textMuted,
            fontWeight = if (selected) {
                androidx.compose.ui.text.font.FontWeight.SemiBold
            } else {
                androidx.compose.ui.text.font.FontWeight.Normal
            },
            maxLines = 1,
        )
    }
}

@Composable
private fun DesktopAddonSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val focusRequester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .height(40.dp)
            .background(tokens.colors.surfaceCard, RoundedCornerShape(10.dp))
            .border(
                width = 1.dp,
                color = if (focused) tokens.colors.borderFocus else tokens.colors.borderDefault,
                shape = RoundedCornerShape(10.dp),
            )
            // Focus the text input when anywhere in the field is clicked. Without this the
            // BasicTextField only claims its own inner bounds, so clicks on the icon/placeholder
            // leave focus on the settings root and typed keys never reach the field.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { focusRequester.requestFocus() }
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = null,
                tint = tokens.colors.textMuted,
                modifier = Modifier.size(20.dp),
            )
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester)
                    .onFocusChanged { focused = it.isFocused },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = tokens.colors.textPrimary),
                cursorBrush = SolidColor(tokens.colors.accent),
                decorationBox = { innerTextField ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (value.isBlank()) {
                            Text(
                                text = "Filter installed addons",
                                style = MaterialTheme.typography.bodyMedium,
                                color = tokens.colors.textMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        innerTextField()
                    }
                },
            )
        }
    }
}

@Composable
private fun DesktopAddAddonButton(
    selected: Boolean,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Surface(
        modifier = Modifier
            .height(44.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) tokens.colors.accentStrong else tokens.colors.accent,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Add,
                contentDescription = null,
                tint = tokens.colors.background,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = "Add Addon",
                style = MaterialTheme.typography.bodyLarge,
                color = tokens.colors.background,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun DesktopAddonSortMenu(
    sort: DesktopAddonSort,
    onSortChange: (DesktopAddonSort) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val tokens = MaterialTheme.nuvio
    Box(modifier = modifier) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .clickable { expanded = true },
            shape = RoundedCornerShape(10.dp),
            color = tokens.colors.surfaceCard,
            border = androidx.compose.foundation.BorderStroke(1.dp, tokens.colors.borderDefault),
        ) {
            Row(
                modifier = Modifier.padding(start = 14.dp, end = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = sort.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    imageVector = Icons.Rounded.KeyboardArrowDown,
                    contentDescription = null,
                    tint = tokens.colors.textMuted,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DesktopAddonSort.entries.forEach { option ->
                val isSelected = option == sort
                DropdownMenuItem(
                    text = {
                        Text(
                            text = option.label,
                            color = if (isSelected) tokens.colors.accent else tokens.colors.textPrimary,
                            fontWeight = if (isSelected) {
                                androidx.compose.ui.text.font.FontWeight.SemiBold
                            } else {
                                androidx.compose.ui.text.font.FontWeight.Normal
                            },
                        )
                    },
                    onClick = {
                        onSortChange(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun DesktopAddAddonPopover(
    addonUrl: String,
    formMessage: String?,
    onAddonUrlChange: (String) -> Unit,
    onAddClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = tokens.colors.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, tokens.colors.borderDefault),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NuvioInputField(
                    value = addonUrl,
                    onValueChange = onAddonUrlChange,
                    placeholder = stringResource(Res.string.addons_input_placeholder),
                    modifier = Modifier.weight(1f),
                )
                NuvioPrimaryButton(
                    text = stringResource(Res.string.addons_install_button),
                    enabled = addonUrl.isNotBlank(),
                    onClick = onAddClick,
                    modifier = Modifier.width(112.dp),
                )
            }
            formMessage?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun DesktopAddonsTable(
    rows: List<DesktopAddonRow>,
    totalAddonCount: Int,
    openConfigureUrl: (String) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = tokens.colors.surface,
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 16.dp)) {
            DesktopAddonTableHeader()
            HorizontalDivider(color = tokens.colors.borderDefault)
            if (totalAddonCount == 0) {
                DesktopAddonsEmptyTable(
                    title = stringResource(Res.string.addons_empty_title),
                    subtitle = stringResource(Res.string.addons_empty_subtitle),
                )
            } else if (rows.isEmpty()) {
                DesktopAddonsEmptyTable(
                    title = "No addons match these filters",
                    subtitle = "Clear the search or filter chips to show installed addons.",
                )
            } else {
                rows.forEachIndexed { visibleIndex, row ->
                    DesktopAddonTableRow(
                        row = row,
                        totalAddonCount = totalAddonCount,
                        openConfigureUrl = openConfigureUrl,
                    )
                    if (visibleIndex != rows.lastIndex) {
                        HorizontalDivider(color = tokens.colors.borderDefault.copy(alpha = 0.72f))
                    }
                }
            }
        }
    }
}

@Composable
private fun DesktopAddonTableHeader() {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DesktopHeaderText("Addon", Modifier.weight(1.85f), tokens.colors.textMuted)
        DesktopHeaderText("Resources", Modifier.weight(0.6f), tokens.colors.textMuted, TextAlign.Center)
        DesktopHeaderText("Catalogs", Modifier.weight(0.6f), tokens.colors.textMuted, TextAlign.Center)
        DesktopHeaderText("Status", Modifier.weight(0.72f), tokens.colors.textMuted, TextAlign.Center)
        DesktopHeaderText("Actions", Modifier.weight(1.7f), tokens.colors.textMuted, TextAlign.Center)
    }
}

@Composable
private fun DesktopHeaderText(
    text: String,
    modifier: Modifier,
    color: Color,
    textAlign: TextAlign = TextAlign.Start,
) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
        textAlign = textAlign,
        maxLines = 1,
    )
}

@Composable
private fun DesktopAddonTableRow(
    row: DesktopAddonRow,
    totalAddonCount: Int,
    openConfigureUrl: (String) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val addon = row.addon
    val manifest = addon.manifest
    val behaviorHints = manifest?.behaviorHints
    val showConfigureAction = behaviorHints?.configurable == true || behaviorHints?.configurationRequired == true
    val configureUrl = addon.manifestUrl.toConfigureUrl()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1.85f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            AddonMonogramBadge(
                imageUrl = manifest?.logoUrl,
                title = addon.displayTitle,
                available = manifest != null,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = addon.displayTitle,
                    style = MaterialTheme.typography.bodyLarge,
                    color = tokens.colors.textPrimary,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = manifest?.version?.let { "v$it" } ?: addon.manifestUrl,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    ),
                    color = tokens.colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                addon.errorMessage?.takeIf { manifest == null }?.let { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Text(
            text = (manifest?.resources?.size ?: 0).toString(),
            modifier = Modifier.weight(0.6f),
            style = MaterialTheme.typography.titleMedium,
            color = tokens.colors.textPrimary,
            textAlign = TextAlign.Center,
        )
        Text(
            text = (manifest?.catalogs?.size ?: 0).toString(),
            modifier = Modifier.weight(0.6f),
            style = MaterialTheme.typography.titleMedium,
            color = tokens.colors.textPrimary,
            textAlign = TextAlign.Center,
        )
        DesktopAddonStatusIndicator(
            addon = addon,
            modifier = Modifier.weight(0.72f),
        )
        DesktopAddonActionCluster(
            modifier = Modifier.weight(1.7f),
        ) {
            DesktopAddonActionButton(
                icon = Icons.Rounded.ArrowUpward,
                contentDescription = stringResource(Res.string.addons_move_up),
                enabled = row.index > 0,
                onClick = { AddonRepository.moveAddon(row.index, row.index - 1) },
            )
            DesktopAddonActionButton(
                icon = Icons.Rounded.ArrowDownward,
                contentDescription = stringResource(Res.string.addons_move_down),
                enabled = row.index < totalAddonCount - 1,
                onClick = { AddonRepository.moveAddon(row.index, row.index + 1) },
            )
            DesktopAddonActionButton(
                icon = Icons.Rounded.Refresh,
                contentDescription = stringResource(Res.string.addons_refresh),
                onClick = { AddonRepository.refreshAddon(addon.manifestUrl) },
            )
            DesktopAddonActionButton(
                icon = Icons.Rounded.Edit,
                contentDescription = stringResource(Res.string.addons_configure),
                enabled = showConfigureAction && !configureUrl.isNullOrBlank(),
                tint = tokens.colors.accent,
                onClick = {
                    if (!configureUrl.isNullOrBlank()) {
                        openConfigureUrl(configureUrl)
                    }
                },
            )
            DesktopAddonActionButton(
                icon = Icons.Rounded.Delete,
                contentDescription = stringResource(Res.string.addons_delete),
                tint = MaterialTheme.colorScheme.error,
                onClick = { AddonRepository.removeAddon(addon.manifestUrl) },
            )
        }
    }
}

/** STATUS column: a coloured dot + label, clickable to toggle enablement. */
@Composable
private fun DesktopAddonStatusIndicator(
    addon: ManagedAddon,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val text = when {
        !addon.enabled -> stringResource(Res.string.addons_badge_disabled)
        addon.isRefreshing -> stringResource(Res.string.addons_badge_refreshing)
        addon.manifest != null -> stringResource(Res.string.addons_badge_active)
        else -> stringResource(Res.string.addons_badge_unavailable)
    }
    val dotColor = when {
        !addon.enabled -> tokens.colors.textMuted
        addon.isRefreshing -> tokens.colors.accent
        addon.manifest != null -> tokens.colors.accent
        else -> MaterialTheme.colorScheme.error
    }
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { AddonRepository.setAddonEnabled(addon.manifestUrl, !addon.enabled) }
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(dotColor),
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (addon.enabled) tokens.colors.textPrimary else tokens.colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** ACTIONS column: the icon buttons grouped in a subtle bordered container, as in the mockup. */
@Composable
private fun DesktopAddonActionCluster(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = tokens.colors.surfaceCard.copy(alpha = 0.5f),
            border = androidx.compose.foundation.BorderStroke(1.dp, tokens.colors.borderDefault),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                content()
            }
        }
    }
}

@Composable
private fun DesktopAddonActionButton(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.nuvio.colors.textMuted,
    onClick: () -> Unit,
) {
    IconButton(
        enabled = enabled,
        onClick = onClick,
        modifier = Modifier.size(32.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) tint else tint.copy(alpha = 0.28f),
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * Rounded square addon badge: the addon logo when available, otherwise a two-letter monogram on a
 * per-addon tinted background (stable across launches), matching the mockup's coloured AM/CM/TO tiles.
 */
@Composable
private fun AddonMonogramBadge(
    imageUrl: String?,
    title: String,
    available: Boolean,
) {
    val tokens = MaterialTheme.nuvio
    val hasLogo = !imageUrl.isNullOrBlank()
    // A logo keeps its original colours on a neutral tile; only the monogram fallback is tinted.
    val badgeColor = when {
        hasLogo -> tokens.colors.surfaceCard
        available -> addonBadgeColor(title)
        else -> tokens.colors.surfaceCard
    }
    Box(
        modifier = Modifier
            .size(46.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(badgeColor),
        contentAlignment = Alignment.Center,
    ) {
        if (hasLogo) {
            AsyncImage(
                model = imageUrl,
                contentDescription = null,
                // Fit + inset so the whole logo sits inside the tile instead of being cropped
                // against the rounded border.
                modifier = Modifier
                    .fillMaxSize()
                    .padding(5.dp),
                contentScale = ContentScale.Fit,
            )
        } else {
            Text(
                text = addonMonogram(title),
                style = MaterialTheme.typography.titleMedium,
                color = if (available) Color.White else tokens.colors.textMuted,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            )
        }
    }
}

private val AddonBadgePalette = listOf(
    Color(0xFF1F9E8E),
    Color(0xFF3A4654),
    Color(0xFF2E5C86),
    Color(0xFF3E3E6B),
    Color(0xFF2F6B4F),
    Color(0xFF7A6320),
    Color(0xFF6B2F55),
    Color(0xFF2F5F6B),
)

private fun addonBadgeColor(key: String): Color {
    val index = (key.hashCode() and 0x7FFFFFFF) % AddonBadgePalette.size
    return AddonBadgePalette[index]
}

private fun addonMonogram(title: String): String {
    val cleaned = title.trim()
    if (cleaned.isEmpty()) return "?"
    val words = cleaned.split(' ', '-', '_', '.', '/').filter { it.isNotBlank() }
    val letters = if (words.size >= 2) {
        "${words[0].first()}${words[1].first()}"
    } else {
        cleaned.filter { it.isLetterOrDigit() }.take(2)
    }
    return letters.uppercase().ifBlank { "?" }
}

@Composable
private fun DesktopAddonsEmptyTable(
    title: String,
    subtitle: String,
) {
    val tokens = MaterialTheme.nuvio
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = tokens.colors.textPrimary,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.colors.textMuted,
        )
    }
}

@Composable
private fun EmptyStateCard() {
    NuvioSurfaceCard {
        Text(
            text = stringResource(Res.string.addons_empty_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(Res.string.addons_empty_subtitle),
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
    onEnabledChange: (Boolean) -> Unit,
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
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                manifest?.version?.let { version ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(Res.string.addons_version_format, version),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Switch(
                checked = addon.enabled,
                onCheckedChange = onEnabledChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                    uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    uncheckedTrackColor = MaterialTheme.colorScheme.outlineVariant,
                ),
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            onMoveUpClick?.let { onMoveUp ->
                NuvioIconActionButton(
                    icon = Icons.Rounded.ArrowUpward,
                    contentDescription = stringResource(Res.string.addons_move_up),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    onClick = onMoveUp,
                )
            }
            onMoveDownClick?.let { onMoveDown ->
                NuvioIconActionButton(
                    icon = Icons.Rounded.ArrowDownward,
                    contentDescription = stringResource(Res.string.addons_move_down),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    onClick = onMoveDown,
                )
            }
            NuvioIconActionButton(
                icon = Icons.Rounded.Refresh,
                contentDescription = stringResource(Res.string.addons_refresh),
                tint = MaterialTheme.colorScheme.primary,
                onClick = onRefreshClick,
            )
            onConfigureClick?.let { onConfigure ->
                NuvioIconActionButton(
                    icon = Icons.Rounded.Settings,
                    contentDescription = stringResource(Res.string.addons_configure),
                    tint = MaterialTheme.colorScheme.tertiary,
                    onClick = onConfigure,
                )
            }
            NuvioIconActionButton(
                icon = Icons.Rounded.Delete,
                contentDescription = stringResource(Res.string.addons_delete),
                tint = MaterialTheme.colorScheme.error,
                onClick = onDeleteClick,
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Spacer(modifier = Modifier.height(12.dp))

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            NuvioInfoBadge(
                text = when {
                    !addon.enabled -> stringResource(Res.string.addons_badge_disabled)
                    addon.isRefreshing -> stringResource(Res.string.addons_badge_refreshing)
                    manifest != null -> stringResource(Res.string.addons_badge_active)
                    else -> stringResource(Res.string.addons_badge_unavailable)
                },
            )
            manifest?.let {
                NuvioInfoBadge(text = stringResource(Res.string.addons_badge_resources, it.resources.size))
                NuvioInfoBadge(text = stringResource(Res.string.addons_badge_catalogs, it.catalogs.size))
                if (it.behaviorHints.configurable) {
                    NuvioInfoBadge(text = stringResource(Res.string.addons_badge_configurable))
                }
            }
        }

        when {
            addon.isRefreshing -> {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(Res.string.addons_loading_manifest_details),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            addon.errorMessage != null && manifest == null -> {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = addon.errorMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            manifest != null -> {
                Spacer(modifier = Modifier.height(12.dp))
                manifest.description.takeIf { it.isNotBlank() }?.let { description ->
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(modifier = Modifier.height(10.dp))
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
            .size(44.dp)
            .clip(RoundedCornerShape(12.dp))
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
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

@Composable
private fun manifestSummary(manifest: AddonManifest): String {
    val resources = manifest.resources.joinToString(separator = ", ") { it.name }
    val types = manifest.types.joinToString(separator = " / ") { it.replaceFirstChar(Char::uppercase) }
    return buildString {
        append(types)
        append(" • ")
        append(resources)
        if (manifest.idPrefixes.isNotEmpty()) {
            append(" • ")
            append(stringResource(Res.string.addons_summary_id_rules, manifest.idPrefixes.size))
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
