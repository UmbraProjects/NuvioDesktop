package com.nuvio.app.features.details

internal fun castAvatarSharedTransitionKey(
    personId: Int,
    occurrenceIndex: Int? = null,
): String =
    if (occurrenceIndex != null) {
        "cast-avatar:$personId:$occurrenceIndex"
    } else {
        "cast-avatar:$personId"
    }
