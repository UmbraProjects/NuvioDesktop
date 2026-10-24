package com.nuvio.app.core.network

import com.nuvio.app.core.build.AppVersionConfig
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.functions.Functions
import io.github.jan.supabase.postgrest.Postgrest
import io.ktor.client.plugins.defaultRequest
import io.ktor.http.HttpHeaders
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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
        return createSupabaseClient(
            supabaseUrl = configuration.backendUrl,
            supabaseKey = configuration.publishableKey,
        ) {
            httpConfig {
                defaultRequest {
                    headers.append(HttpHeaders.UserAgent, userAgent)
                }
            }
            install(Auth)
            install(Postgrest)
            install(Functions)
        }
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
