package com.nuvio.app.features.iptv

import com.nuvio.app.core.network.DesktopIPv4FirstDns
import java.io.IOException
import java.io.InputStream
import java.net.Authenticator
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request

// Its own client: no response size cap (bodies are streamed), and a longer read timeout than the
// addon client because some providers take a while to start sending a large `get_live_streams`.
private val iptvHttpClient = OkHttpClient.Builder()
    .dns(DesktopIPv4FirstDns())
    .connectTimeout(20, TimeUnit.SECONDS)
    .readTimeout(90, TimeUnit.SECONDS)
    .followRedirects(true)
    .followSslRedirects(true)
    .build()

/** The client for the current [IptvProxy]; rebuilt only when the setting changes. */
internal object IptvProxiedClients {
    private var cachedFor: IptvProxy? = null
    private var cached: OkHttpClient = iptvHttpClient

    @Synchronized
    fun clientFor(proxy: IptvProxy): OkHttpClient {
        if (!proxy.enabled) return iptvHttpClient
        if (proxy == cachedFor) return cached
        val builder = iptvHttpClient.newBuilder()
        when (proxy.type) {
            IptvProxyType.Http -> {
                builder.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(proxy.host, proxy.port)))
                if (proxy.username.isNotEmpty()) {
                    val credential = Credentials.basic(proxy.username, proxy.password)
                    builder.proxyAuthenticator { _, response ->
                        // Asked again after sending credentials: they are wrong, so stop (407 surfaces).
                        if (response.request.header("Proxy-Authorization") != null) {
                            null
                        } else {
                            response.request.newBuilder().header("Proxy-Authorization", credential).build()
                        }
                    }
                }
            }
            IptvProxyType.Socks -> {
                // Unresolved, so the proxy does the DNS lookup too: a block that also poisons DNS
                // is bypassed. OkHttp's Dns is not consulted for SOCKS.
                builder.proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved(proxy.host, proxy.port)))
                SocksCredentials.set(proxy)
            }
            IptvProxyType.Off -> Unit
        }
        cached = builder.build()
        cachedFor = proxy
        return cached
    }
}

/**
 * The JDK's SOCKS5 client asks the process-wide [Authenticator] for credentials; OkHttp has no
 * hook of its own for it. This one answers only for the configured IPTV SOCKS proxy and hands
 * every other request to whatever authenticator was installed before it.
 */
private object SocksCredentials : Authenticator() {
    @Volatile private var proxy: IptvProxy? = null
    private var previous: Authenticator? = null
    private var installed = false

    @Synchronized
    fun set(value: IptvProxy) {
        proxy = value.takeIf { it.username.isNotEmpty() }
        if (!installed && proxy != null) {
            previous = runCatching { Authenticator.getDefault() }.getOrNull()
            Authenticator.setDefault(this)
            installed = true
        }
    }

    override fun getPasswordAuthentication(): PasswordAuthentication? {
        val current = proxy
        if (current != null &&
            requestingProtocol?.startsWith("SOCKS", ignoreCase = true) == true &&
            requestingHost.equals(current.host, ignoreCase = true) &&
            requestingPort == current.port
        ) {
            return PasswordAuthentication(current.username, current.password.toCharArray())
        }
        return previous?.requestPasswordAuthenticationInstance(
            requestingHost, requestingSite, requestingPort, requestingProtocol,
            requestingPrompt, requestingScheme, requestingURL, requestorType,
        )
    }
}

internal actual object IptvHttp {
    actual suspend fun <T> get(url: String, headers: Map<String, String>, read: (InputStream) -> T): T {
        val request = try {
            Request.Builder().url(url).apply { headers.forEach { (name, value) -> header(name, value) } }.build()
        } catch (e: IllegalArgumentException) {
            throw IptvSourceException(IptvSourceException.Kind.BadUrl, "Bad URL", cause = e)
        }
        val client = IptvProxiedClients.clientFor(IptvRepository.settings.value.proxy)
        val job = currentCoroutineContext()[Job]
        return withContext(Dispatchers.IO) {
            val call = client.newCall(request)
            val cancelHandle = job?.invokeOnCompletion { if (job.isCancelled) call.cancel() }
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IptvSourceException(
                            IptvSourceException.Kind.Http,
                            "HTTP ${response.code}",
                            httpCode = response.code,
                        )
                    }
                    read(response.body!!.byteStream())
                }
            } catch (e: IptvSourceException) {
                throw e
            } catch (e: IOException) {
                if (job?.isCancelled == true) throw CancellationException("IPTV request cancelled").apply { initCause(e) }
                throw IptvSourceException(IptvSourceException.Kind.Network, e.message ?: "Network error", cause = e)
            } finally {
                cancelHandle?.dispose()
            }
        }
    }
}
