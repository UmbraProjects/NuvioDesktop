package com.nuvio.app.core.network

import com.nuvio.app.core.build.AppVersionConfig
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.MemorySessionManager
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.functions.Functions
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.websocket.KtorRealtimeWebsocketFactory
import io.github.jan.supabase.supabaseJson
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.KotlinxWebsocketSerializationConverter
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalAtomicApi::class)
object SupabaseProvider {
    /**
     * Atomic rather than a plain field: upstream's `cachedClient ?: create().also { … }` lets two
     * concurrent first-touches each build a client, and only one of them is ever retained - the
     * other leaks its HTTP engine and auth refresh loop. That was visible in nuvio.log as two
     * "SupabaseClient created" lines with no close between them.
     */
    private val cachedClient = AtomicReference<SupabaseClient?>(null)

    /** SupabaseClient.close() is suspend, but [client] is not - discarded clients close here. */
    private val disposalScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Set before the first [client] read by the desktop native smoke test. The session then lives
     * in memory only, so a smoke launch starts signed out. The default session store is
     * machine-wide (Java preferences), not the data directory, so a throwaway profile otherwise
     * came up signed in to the user's account with sync running (2026-10-17).
     */
    var memoryOnlySession: Boolean = false

    val client: SupabaseClient
        get() {
            cachedClient.load()?.let { return it }
            val candidate = createClient()
            if (cachedClient.compareAndSet(null, candidate)) return candidate
            // Another caller published first; drop ours rather than leaving it running.
            disposalScope.launch { candidate.close() }
            return cachedClient.load() ?: candidate
        }

    @OptIn(SupabaseInternal::class)
    private fun createClient(): SupabaseClient {
        val configuration = ServerConfigurationRepository.active.value
        val userAgent = "NuvioMobile/${AppVersionConfig.VERSION_NAME.ifBlank { "dev" }}"
        lateinit var client: SupabaseClient
        client = createSupabaseClient(
            supabaseUrl = configuration.backendUrl,
            supabaseKey = configuration.publishableKey,
        ) {
            httpConfig {
                defaultRequest {
                    headers.append(HttpHeaders.UserAgent, userAgent)
                }
                install(com.nuvio.app.core.perf.perfTelemetryPlugin("supabase"))
                install(WebSockets) {
                    contentConverter = KotlinxWebsocketSerializationConverter(supabaseJson)
                }
            }
            install(Auth) {
                if (memoryOnlySession) sessionManager = MemorySessionManager()
            }
            install(Postgrest)
            install(Functions)
            // Watch parties only (features/party). The socket opens on the first channel
            // subscribe, so nothing connects until someone starts or joins a party.
            install(Realtime) {
                customUrl = realtimeUrl(configuration.backendUrl)
                reconnectDelay = 3.seconds
                websocketFactory = RealtimeSocketFactory { url ->
                    KtorRealtimeWebsocketFactory(client.httpClient.httpClient).create(url)
                }
            }
        }
        return client
    }

    /**
     * Upstream keeps this in SupabaseEndpointConfig, which this fork does not have. The query
     * marker matches upstream so the backend can tell party sockets apart.
     */
    private fun realtimeUrl(backendUrl: String): String {
        val base = backendUrl.trim().trimEnd('/')
        val socketBase = when {
            base.startsWith("https://", ignoreCase = true) -> "wss://" + base.substring("https://".length)
            base.startsWith("http://", ignoreCase = true) -> "ws://" + base.substring("http://".length)
            else -> base
        }
        return "$socketBase/realtime/v1?nuvio_realtime=1"
    }

    /**
     * Discards the current client so the next [client] read builds one against the active server.
     *
     * Callers must stop anything collecting from the old client first - see
     * `AuthRepository.prepareForServerSwitch`, which runs before this.
     */
    suspend fun reset() {
        cachedClient.exchange(null)?.close()
    }
}
