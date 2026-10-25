package com.nuvio.app.features.settings

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.mdblist.MdbListLibraryRepository
import com.nuvio.app.features.mdblist.MdbListListKind
import com.nuvio.app.features.mdblist.MdbListSettings
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.settings_mdb_list_kind_dynamic
import nuvio.composeapp.generated.resources.settings_mdb_list_kind_external
import nuvio.composeapp.generated.resources.settings_mdb_list_kind_liked
import nuvio.composeapp.generated.resources.settings_mdb_list_kind_static
import nuvio.composeapp.generated.resources.settings_mdb_section_library_lists
import nuvio.composeapp.generated.resources.settings_mdb_section_library_lists_description
import org.jetbrains.compose.resources.stringResource

/**
 * Which MDBList lists appear in the Library (upstream a9797ff89): one switch per list, the
 * watchlist excepted. A hidden list is left out of the Library and the add-to-list choices, and is
 * not downloaded; showing it again loads it.
 */
internal fun LazyListScope.mdbListLibraryListsSection(
    isTablet: Boolean,
    settings: MdbListSettings,
) {
    if (!settings.hasApiKey || !settings.trackingEnabled) return
    item {
        val state by MdbListLibraryRepository.state.collectAsStateWithLifecycle()
        val scope = rememberCoroutineScope()
        LaunchedEffect(Unit) { MdbListLibraryRepository.refresh(force = false) }
        if (state.lists.isEmpty()) return@item
        SettingsSection(
            title = stringResource(Res.string.settings_mdb_section_library_lists),
            isTablet = isTablet,
        ) {
            SettingsGroup(
                isTablet = isTablet,
                modifier = Modifier.settingsScrollAnchor(SettingsScrollAnchor.searchKey("mdb-library-lists")),
            ) {
                MdbListInfoRow(isTablet = isTablet, text = stringResource(Res.string.settings_mdb_section_library_lists_description))
                state.lists.forEach { list ->
                    SettingsGroupDivider(isTablet = isTablet)
                    val kind = when (list.kind) {
                        MdbListListKind.STATIC -> stringResource(Res.string.settings_mdb_list_kind_static)
                        MdbListListKind.DYNAMIC -> stringResource(Res.string.settings_mdb_list_kind_dynamic)
                        MdbListListKind.LIKED -> stringResource(Res.string.settings_mdb_list_kind_liked)
                        MdbListListKind.EXTERNAL -> stringResource(Res.string.settings_mdb_list_kind_external)
                    }
                    SettingsSwitchRow(
                        title = list.name,
                        description = listOfNotNull(kind, list.source?.takeIf { it.isNotBlank() }).joinToString(" · "),
                        checked = list.key !in state.hiddenListKeys,
                        isTablet = isTablet,
                        onCheckedChange = { visible ->
                            scope.launch { MdbListLibraryRepository.setListVisible(list.key, visible) }
                        },
                    )
                }
            }
        }
    }
}
