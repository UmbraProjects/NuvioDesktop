package com.nuvio.app.features.locallibrary

import java.text.Normalizer

internal actual fun decomposeUnicodeForTitleMatching(value: String): String =
    Normalizer.normalize(value, Normalizer.Form.NFKD)
