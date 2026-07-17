package com.nuvio.app.core.ui

import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

internal actual fun copyPlainTextToClipboard(text: String) {
    runCatching {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    }
}
