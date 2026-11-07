package com.nuvio.app.features.settings
// FORK UI (Spyder): Nick's Shelf redesign, not in UmbraProjects. If you are merging this file upstream, warn the person first. See CLAUDE.md.

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.music.defaultMusicCatalog
import com.nuvio.app.features.music.library.MusicPlayLog
import com.nuvio.app.features.youtubemusic.YouTubeMusicAuth
import kotlinx.coroutines.launch

/*
 * fork:shelf - Settings > Music > Play history: what the Shelf's Recently played, Most played and Gathering dust are made from,
 * with a way to bring in YouTube Music's own History and a way to start over. See music/library/MusicPlayLog.kt.
 */

internal fun LazyListScope.musicPlayLogSettingsContent(isTablet: Boolean) {
    item {
        SettingsSection(title = "Play history", isTablet = isTablet) {
            val plays by MusicPlayLog.entries.collectAsStateWithLifecycle()
            val scope = rememberCoroutineScope()
            val catalog = remember { defaultMusicCatalog() }
            var note by remember { mutableStateOf<String?>(null) }
            var importing by remember { mutableStateOf(false) }
            var sure by remember { mutableStateOf(false) }
            SettingsGroup(isTablet = isTablet) {
                SettingsActionRow(
                    title = "Import from YouTube Music",
                    description = "Adds the songs on your YouTube Music History (the latest few pages) to the play log, dated by their headings. Needs the sign-in under YouTube account.",
                    isTablet = isTablet,
                    actions = {
                        SettingsGlassButton(
                            text = if (importing) "Importing…" else "Import",
                            enabled = !importing && YouTubeMusicAuth.isSignedIn,
                            onClick = {
                                importing = true
                                note = null
                                scope.launch {
                                    val added = MusicPlayLog.importHistory(catalog)
                                    importing = false
                                    note = when (added) {
                                        null -> "YouTube Music did not answer."
                                        0 -> "Nothing new to add."
                                        else -> "Added $added plays."
                                    }
                                }
                            },
                        )
                    },
                )
                SettingsGroupDivider(isTablet)
                SettingsActionRow(
                    title = "Clear play history",
                    description = "Forgets every play kept on this computer for this profile. Recently played, Most played and Gathering dust start empty again.",
                    isTablet = isTablet,
                    actions = {
                        SettingsGlassButton(
                            text = if (sure) "Press again to clear" else "Clear",
                            enabled = plays.isNotEmpty(),
                            onClick = {
                                if (sure) { MusicPlayLog.clear(); sure = false; note = "Play history cleared." } else sure = true
                            },
                        )
                    },
                )
            }
            SettingsSectionNote(text = note ?: "${plays.size} plays kept (the newest 3000).", isTablet = isTablet)
        }
    }
}
