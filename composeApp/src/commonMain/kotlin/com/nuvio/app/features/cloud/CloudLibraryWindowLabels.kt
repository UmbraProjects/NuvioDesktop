package com.nuvio.app.features.cloud

import androidx.compose.runtime.Composable
import com.nuvio.app.features.debrid.DebridCloudLibraryWindow
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.cloud_library_window_all
import nuvio.composeapp.generated.resources.cloud_library_window_days
import nuvio.composeapp.generated.resources.cloud_library_window_year
import org.jetbrains.compose.resources.stringResource

/** Shared by the debrid settings row and the cloud library's own toolbar, so both read the same. */
@Composable
internal fun cloudLibraryWindowLabel(window: DebridCloudLibraryWindow): String =
    when (window) {
        DebridCloudLibraryWindow.ALL -> stringResource(Res.string.cloud_library_window_all)
        DebridCloudLibraryWindow.DAYS_365 -> stringResource(Res.string.cloud_library_window_year)
        else -> stringResource(Res.string.cloud_library_window_days, window.days)
    }
