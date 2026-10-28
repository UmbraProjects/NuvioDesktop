package com.nuvio.app.core.i18n

import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString

/**
 * Resolves a string resource outside composition (repositories, enums feeding the native player HUD,
 * tray menus). Composables should keep using `stringResource`, which follows locale changes.
 */
fun localizedString(resource: StringResource, vararg formatArgs: Any): String =
    runBlocking { getString(resource, *formatArgs) }
