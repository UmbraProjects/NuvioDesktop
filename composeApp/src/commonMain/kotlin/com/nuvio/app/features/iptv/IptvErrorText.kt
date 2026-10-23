package com.nuvio.app.features.iptv

import androidx.compose.runtime.Composable
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.iptv_error_bad_login
import nuvio.composeapp.generated.resources.iptv_error_bad_response
import nuvio.composeapp.generated.resources.iptv_error_bad_url
import nuvio.composeapp.generated.resources.iptv_error_http
import nuvio.composeapp.generated.resources.iptv_error_inactive
import nuvio.composeapp.generated.resources.iptv_error_network
import nuvio.composeapp.generated.resources.iptv_error_no_url
import nuvio.composeapp.generated.resources.iptv_error_proxy_auth
import nuvio.composeapp.generated.resources.iptv_error_proxy_unreachable
import nuvio.composeapp.generated.resources.iptv_error_unreachable
import org.jetbrains.compose.resources.stringResource

/** What went wrong loading a playlist, in words that say what to fix. */
@Composable
internal fun iptvErrorText(error: IptvSourceException): String {
    val proxied = IptvRepository.settings.value.proxy.enabled
    return when (error.kind) {
        IptvSourceException.Kind.NoUrl -> stringResource(Res.string.iptv_error_no_url)
        IptvSourceException.Kind.BadUrl -> stringResource(Res.string.iptv_error_bad_url)
        IptvSourceException.Kind.BadLogin -> stringResource(Res.string.iptv_error_bad_login)
        IptvSourceException.Kind.Inactive -> stringResource(Res.string.iptv_error_inactive, error.status.orEmpty())
        IptvSourceException.Kind.Http -> when {
            error.httpCode == 407 -> stringResource(Res.string.iptv_error_proxy_auth)
            else -> stringResource(Res.string.iptv_error_http, error.httpCode ?: 0)
        }
        IptvSourceException.Kind.BadResponse -> stringResource(Res.string.iptv_error_bad_response)
        IptvSourceException.Kind.Network -> when {
            proxied && error.isProxyFailure() -> stringResource(Res.string.iptv_error_proxy_unreachable)
            error.isUnreachable() -> stringResource(Res.string.iptv_error_unreachable)
            else -> stringResource(Res.string.iptv_error_network, error.message.orEmpty())
        }
    }
}

/**
 * Through a proxy, a connect failure or a SOCKS refusal is most likely the proxy itself (wrong
 * address, port or type, or rejected credentials) rather than the provider behind it.
 */
private fun IptvSourceException.isProxyFailure(): Boolean =
    isUnreachable() || generateSequence<Throwable>(this) { current -> current.cause?.takeIf { it !== current } }.take(4).any {
        it.message?.contains("SOCKS", ignoreCase = true) == true || it.message?.contains("proxy", ignoreCase = true) == true
    }

/**
 * The server never accepted a connection (timed out or refused), as opposed to a failure partway
 * through. In practice that means it is down, or the user's network blocks it: UK ISPs block many
 * IPTV servers' addresses, which shows up as exactly this silent connect timeout.
 */
internal fun IptvSourceException.isUnreachable(): Boolean =
    generateSequence<Throwable>(this) { current -> current.cause?.takeIf { it !== current } }.take(4).any {
        it is java.net.ConnectException ||
            it is java.net.NoRouteToHostException ||
            (it is java.net.SocketTimeoutException && it.message?.contains("connect", ignoreCase = true) == true)
    }

/** Any failure as an [IptvSourceException], so the UI has one thing to describe. */
internal fun Throwable.asIptvError(): IptvSourceException =
    this as? IptvSourceException
        ?: IptvSourceException(
            IptvSourceException.Kind.Network,
            redactIptvCredentials(message ?: this::class.simpleName.orEmpty()),
            cause = this,
        )

/** One log line for a failure: kind, HTTP status and the cause chain, credentials masked. */
internal fun describeIptvFailure(error: Throwable): String {
    val source = error as? IptvSourceException
    val chain = generateSequence(error) { current -> current.cause?.takeIf { it !== current } }
        .take(4)
        .joinToString(" <- ") { "${it::class.qualifiedName ?: it::class.simpleName}: ${it.message}" }
    return redactIptvCredentials("kind=${source?.kind} http=${source?.httpCode} $chain")
}

private val CredentialQuery = Regex("((?:username|password)=)[^&\\s\"']*", RegexOption.IGNORE_CASE)
private val CredentialPath = Regex("(/(?:live|movie|series|timeshift)/)[^/\\s]+/[^/\\s]+/")

/** Xtream puts the login in both query strings and stream paths; neither may reach a log. */
internal fun redactIptvCredentials(text: String): String =
    CredentialPath.replace(CredentialQuery.replace(text, "$1***"), "$1***/***/")
