// Ported from NuvioTV PR 4679 (built-in Usenet, haaihond/NuvioTV usenet @ e0255815).
package com.nuvio.app.features.usenet

import java.io.IOException

/** Control-plane failures only; media errors still use the player's recovery ladder. */
internal class UsenetPreparationException(
    message: String,
    val scope: Scope = Scope.SOURCE,
    /** The engine's X-Usenet-Failure kind, such as "missing-article", when it sent one. */
    val failure: String? = null,
    /** The engine's X-Usenet-Providers report: what each provider did for an open that failed. */
    val providers: String? = null,
    /** The engine refused the release itself (HTTP 422): another try with it fails the same way. */
    val releaseUnplayable: Boolean = false,
) : IOException(message) {
    enum class Scope { SOURCE, PROVIDER, ENGINE }
}
