package com.nuvio.app.core.ui

/** Copies plain text to the system clipboard from non-Composable code (e.g. player event handlers). */
internal expect fun copyPlainTextToClipboard(text: String)
