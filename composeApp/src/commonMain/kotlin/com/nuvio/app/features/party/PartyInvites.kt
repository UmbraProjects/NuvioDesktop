package com.nuvio.app.features.party

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** An invite to open on the Watch party page, from a `nuvio://party/CODE` or nuvio.tv link. */
data class PartyInvite(
    val code: String,
    /** Distinguishes two opens of the same code, so the page prefills it again. */
    val id: Long,
)

/**
 * Hands an invite from the deep-link handler to the Watch party settings page, which may not be on
 * screen yet. Upstream passes the code as a route argument; this fork's page lives inside Settings.
 */
object PartyInvites {
    private val _latest = MutableStateFlow<PartyInvite?>(null)
    val latest: StateFlow<PartyInvite?> = _latest.asStateFlow()

    fun open(code: String) {
        _latest.value = PartyInvite(code = code, id = (_latest.value?.id ?: 0L) + 1L)
    }
}
