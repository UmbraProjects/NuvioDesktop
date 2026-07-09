package com.nuvio.app.features.mdblist

import co.touchlab.kermit.Logger
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.home.HeroCastMember
import com.nuvio.app.features.library.LibraryClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object HeroCastMetadataService {
    private const val FOUND_TTL_MS = 30L * 24L * 60L * 60L * 1000L
    private const val EMPTY_TTL_MS = 24L * 60L * 60L * 1000L
    private const val CACHE_VERSION = "v5"

    private val log = Logger.withTag("HeroCastMetadata")
    private val json = Json { ignoreUnknownKeys = true }
    private val cacheMutex = Mutex()
    private val inFlightRequests = mutableMapOf<String, CompletableDeferred<List<HeroCastMember>>>()
    private var cache: MutableMap<String, CachedCast>? = null

    fun peek(type: String, id: String): List<HeroCastMember>? {
        val cacheKey = cacheKey(type = type, id = id)
        val entry = ensureCacheLoaded()[cacheKey] ?: return null
        return entry.cast.takeIf { entry.expiresAtMs > LibraryClock.nowEpochMs() }
    }

    suspend fun fetch(type: String, id: String): List<HeroCastMember> {
        val cacheKey = cacheKey(type = type, id = id)
        val now = LibraryClock.nowEpochMs()
        var ownsRequest = false
        val pending = cacheMutex.withLock {
            val loaded = ensureCacheLoaded()
            loaded[cacheKey]?.let { entry ->
                if (entry.expiresAtMs > now) return entry.cast
            }
            inFlightRequests[cacheKey] ?: CompletableDeferred<List<HeroCastMember>>().also {
                inFlightRequests[cacheKey] = it
                ownsRequest = true
            }
        }
        if (!ownsRequest) return pending.await()

        val cast = try {
            val meta = MetaDetailsRepository.peek(type = type, id = id)
                ?: MetaDetailsRepository.fetch(type = type, id = id)
            val crewNames = meta?.let(::crewNameSet).orEmpty()
            meta?.cast.orEmpty().toHeroCast(crewNames)
        } catch (error: CancellationException) {
            cacheMutex.withLock {
                inFlightRequests.remove(cacheKey)?.completeExceptionally(error)
            }
            throw error
        } catch (error: Throwable) {
            log.w { "Cast request failed for $type/$id: ${error.message}" }
            emptyList()
        }

        val ttl = if (cast.isEmpty()) EMPTY_TTL_MS else FOUND_TTL_MS
        cacheMutex.withLock {
            val loaded = ensureCacheLoaded()
            loaded[cacheKey] = CachedCast(cast = cast, expiresAtMs = now + ttl)
            persistCache(loaded)
            inFlightRequests.remove(cacheKey)?.complete(cast)
        }
        return cast
    }

    private fun cacheKey(type: String, id: String): String = "$CACHE_VERSION:$type:$id"

    private fun crewNameSet(meta: com.nuvio.app.features.details.MetaDetails): Set<String> =
        buildSet {
            addAll(meta.director.map(::normalizeName))
            addAll(meta.writer.map(::normalizeName))
            addAll(meta.creator.map(::normalizeName))
            meta.links
                .asSequence()
                .filter { link -> link.category.isCrewCategory() }
                .map { link -> normalizeName(link.name) }
                .forEach(::add)
        }.filter(String::isNotBlank).toSet()

    private fun List<com.nuvio.app.features.details.MetaPerson>.toHeroCast(
        crewNames: Set<String>,
    ): List<HeroCastMember> {
        // People who have at least one non-crew role are actors regardless of also
        // holding a producer/creator credit. They are exempt from all crew filtering.
        // Example: Ricky Gervais (creator + David Brent), Steve Carell (exec-producer + Michael Scott).
        val actingNames: Set<String> = asSequence()
            .filter { person -> person.role?.isCrewRole() != true }
            .map { person -> normalizeName(person.name) }
            .toSet()

        return asSequence()
            .map { person ->
                HeroCastMember(
                    name = person.name.trim(),
                    photo = person.photo?.trim()?.takeIf(String::isNotBlank),
                    role = person.role?.trim()?.takeIf(String::isNotBlank),
                    tmdbId = person.tmdbId,
                )
            }
            .filter { person -> person.name.isNotBlank() }
            // Crew-only: filter out. Crew + acting credit: keep.
            .filterNot { person ->
                val n = normalizeName(person.name)
                n in crewNames && n !in actingNames
            }
            // Crew-role entry: filter out unless the person also has an acting credit.
            .filterNot { person ->
                person.role?.isCrewRole() == true && normalizeName(person.name) !in actingNames
            }
            // When a person has both credits, surface their acting credit first.
            .sortedBy { if (it.role?.isCrewRole() == true) 1 else 0 }
            .distinctBy { normalizeName(it.name) }
            .take(4)
            .toList()
    }

    private fun String.isCrewRole(): Boolean {
        val roleParts = split(Regex("""[,/;|•·]+"""))
            .map { it.trim().lowercase() }
            .filter(String::isNotBlank)
        if (roleParts.isEmpty()) return false
        return roleParts.all { part ->
            crewRoleMarkers.any(part::contains)
        }
    }

    private val crewRoleMarkers = listOf(
            "director",
            "writer",
            "creator",
            "created by",
            "screenplay",
            "showrunner",
            "producer",
    )

    private fun String.isCrewCategory(): Boolean {
        val normalized = lowercase()
        return listOf(
            "director",
            "writer",
            "creator",
            "created",
            "screenplay",
            "showrunner",
        ).any(normalized::contains)
    }

    private fun normalizeName(name: String): String =
        name.trim().lowercase()

    private fun ensureCacheLoaded(): MutableMap<String, CachedCast> {
        cache?.let { return it }
        val loaded = mutableMapOf<String, CachedCast>()
        runCatching {
            MdbListRatingsCacheStorage.loadCast()?.let { raw ->
                json.decodeFromString<Map<String, CachedCastDto>>(raw).forEach { (key, dto) ->
                    loaded[key] = CachedCast(
                        cast = dto.cast.map { person ->
                            HeroCastMember(
                                name = person.name,
                                photo = person.photo,
                                role = person.role,
                                tmdbId = person.tmdbId,
                            )
                        },
                        expiresAtMs = dto.expiresAtMs,
                    )
                }
            }
        }.onFailure { error ->
            log.w { "Failed to load hero cast cache: ${error.message}" }
        }
        // Prune superseded key schemes (older CACHE_VERSIONs are never looked up again) and expired
        // entries so the shared cache file doesn't grow unbounded. See MdbListMetadataService for the
        // full rationale; the slimmed map is written back on the next persistCache.
        val now = LibraryClock.nowEpochMs()
        val currentPrefix = "$CACHE_VERSION:"
        loaded.entries.retainAll { (key, entry) -> key.startsWith(currentPrefix) && entry.expiresAtMs > now }
        cache = loaded
        return loaded
    }

    private fun persistCache(entries: Map<String, CachedCast>) {
        val dto = entries.mapValues { (_, entry) ->
            CachedCastDto(
                cast = entry.cast.map { person ->
                    HeroCastMemberDto(
                        name = person.name,
                        photo = person.photo,
                        role = person.role,
                        tmdbId = person.tmdbId,
                    )
                },
                expiresAtMs = entry.expiresAtMs,
            )
        }
        runCatching { MdbListRatingsCacheStorage.saveCast(json.encodeToString(dto)) }
            .onFailure { error -> log.w { "Failed to save hero cast cache: ${error.message}" } }
    }
}

private data class CachedCast(
    val cast: List<HeroCastMember>,
    val expiresAtMs: Long,
)

@Serializable
private data class CachedCastDto(
    val cast: List<HeroCastMemberDto>,
    val expiresAtMs: Long,
)

@Serializable
private data class HeroCastMemberDto(
    val name: String,
    val photo: String? = null,
    val role: String? = null,
    val tmdbId: Int? = null,
)
