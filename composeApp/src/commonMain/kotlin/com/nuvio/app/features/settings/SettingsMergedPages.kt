package com.nuvio.app.features.settings

import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/**
 * Desktop settings pages that no longer have a sidebar entry of their own: each now shows as a
 * section of the page it maps to. Plugins sits under the addon table, Continue Watching inside Home
 * Layout above hero sources and the catalog list, Detail Page at the end of Layout, and the
 * downloads list at the top of Downloads (the page that used to be Auto Downloads).
 *
 * The pages themselves stay in [SettingsPage], because saved favourites, category order, hidden
 * categories and search results name them. Opening one lands on its host instead, and its sections
 * render through [withSettingsPage] so their heading ids, which favourites and collapsed sections
 * are keyed by, are the ones they had on their own page.
 */
internal fun SettingsPage.desktopMergedInto(): SettingsPage? = when (this) {
    SettingsPage.Plugins -> SettingsPage.Addons
    SettingsPage.ContinueWatching -> SettingsPage.Homescreen
    SettingsPage.MetaScreen -> SettingsPage.Appearance
    SettingsPage.Downloads -> SettingsPage.AutoDownloads
    else -> null
}

/**
 * Runs [content] with every item it adds seeing [page] as [LocalSettingsPage], for a section that
 * moved onto another page but must keep the identity it had on its own.
 */
internal fun LazyListScope.withSettingsPage(page: SettingsPage, content: LazyListScope.() -> Unit) {
    PageScopedLazyListScope(this, page).content()
}

private class PageScopedLazyListScope(
    private val delegate: LazyListScope,
    private val page: SettingsPage,
) : LazyListScope {
    override fun item(
        key: Any?,
        contentType: Any?,
        content: @Composable LazyItemScope.() -> Unit,
    ) {
        val page = page
        delegate.item(key, contentType) {
            CompositionLocalProvider(LocalSettingsPage provides page) { content() }
        }
    }

    override fun items(
        count: Int,
        key: ((index: Int) -> Any)?,
        contentType: (index: Int) -> Any?,
        itemContent: @Composable LazyItemScope.(index: Int) -> Unit,
    ) {
        val page = page
        delegate.items(count, key, contentType) { index ->
            CompositionLocalProvider(LocalSettingsPage provides page) { itemContent(index) }
        }
    }

    override fun stickyHeader(
        key: Any?,
        contentType: Any?,
        content: @Composable LazyItemScope.(Int) -> Unit,
    ) {
        val page = page
        delegate.stickyHeader(key, contentType) { index ->
            CompositionLocalProvider(LocalSettingsPage provides page) { content(index) }
        }
    }
}
