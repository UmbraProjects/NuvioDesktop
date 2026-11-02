package com.nuvio.app.features.music.potoken

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.util.Base64

/*
 * fork:potoken
 *
 * The wire side of YouTube's BotGuard attestation, as the web player does it. No browser is involved
 * here; see [BotGuardBrowser] for the part that runs Google's script.
 *
 *   Create      POST [ requestKey ]                      -> a challenge: interpreter script, program, VM name
 *   (browser)   run the script, take a snapshot          -> a "botguard response" string
 *   GenerateIT  POST [ requestKey, botguardResponse ]    -> [ integrityToken, ttlSeconds, refreshAfter, fallback ]
 *   (browser)   build a minter from the integrity token  -> mint(identifier) = a PO token bound to it
 *
 * Written against the shape of BitChord's PoTokenGenerator (GPL-3.0) and checked live against the
 * service. The service answers `GenerateIT` with a null integrity token (and only a fallback) when the
 * environment that ran the script does not look like a real browser; [parseIntegrityToken] turns that
 * into a clear error.
 */

internal class PoTokenException(message: String, cause: Throwable? = null) : Exception(message, cause)

internal object PoTokenProtocol {
    const val REQUEST_KEY = "O43z0dpjhgX20SCx4KAo"
    const val API_KEY = "AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw"
    const val CREATE_URL = "https://www.youtube.com/api/jnn/v1/Create"
    const val GENERATE_IT_URL = "https://www.youtube.com/api/jnn/v1/GenerateIT"
    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/130.0.0.0 Safari/537.36"

    /** What the service wants to be sent; the same headers the web player's own requests carry. */
    val requestHeaders: Map<String, String> = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "application/json",
        "Content-Type" to "application/json+protobuf",
        "x-goog-api-key" to API_KEY,
        "x-user-agent" to "grpc-web-javascript/0.1",
    )

    class Challenge(
        val messageId: String,
        /** The interpreter script, run as global code in the page. */
        val script: String,
        val program: String,
        /** The global the script defines; its `.a` loads [program]. */
        val globalName: String,
    )

    class IntegrityToken(val bytes: ByteArray, val ttlSeconds: Long)

    fun createBody(): String = JsonArray(listOf(JsonPrimitive(REQUEST_KEY))).toString()

    fun generateItBody(botguardResponse: String): String =
        JsonArray(listOf(JsonPrimitive(REQUEST_KEY), JsonPrimitive(botguardResponse))).toString()

    /**
     * The service sends the challenge either plain (`[[...]]`) or scrambled (`[null, "<base64>"]`, each
     * byte plus 97 once decoded).
     */
    fun parseChallenge(raw: String): Challenge {
        val outer = try {
            Json.parseToJsonElement(raw).jsonArray
        } catch (error: Exception) {
            throw PoTokenException("The challenge was not JSON", error)
        }
        val data = try {
            val second = outer.getOrNull(1)
            if (outer.size > 1 && second is JsonPrimitive && second.isString) {
                Json.parseToJsonElement(descramble(second.content)).jsonArray
            } else {
                outer[0].jsonArray
            }
        } catch (error: PoTokenException) {
            throw error
        } catch (error: Exception) {
            throw PoTokenException("The challenge had an unexpected shape", error)
        }
        fun text(index: Int): String? =
            (data.getOrNull(index) as? JsonPrimitive)?.takeIf { it.isString }?.content
        fun firstString(index: Int): String? =
            (data.getOrNull(index) as? JsonArray)
                ?.firstNotNullOfOrNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString && p.content.isNotEmpty() }?.content }

        val script = firstString(1)
            ?: throw PoTokenException(
                if (firstString(2) != null) "The challenge sent its script as a URL, which is not supported"
                else "The challenge had no interpreter script",
            )
        return Challenge(
            messageId = text(0).orEmpty(),
            script = script,
            program = text(4) ?: throw PoTokenException("The challenge had no program"),
            globalName = text(5) ?: throw PoTokenException("The challenge named no VM global"),
        )
    }

    fun parseIntegrityToken(raw: String): IntegrityToken {
        val array = try {
            Json.parseToJsonElement(raw).jsonArray
        } catch (error: Exception) {
            throw PoTokenException("The integrity token response was not JSON", error)
        }
        val token = array.getOrNull(0)?.takeUnless { it is JsonNull }?.jsonPrimitive?.contentOrNull
        if (token.isNullOrEmpty()) {
            // [null, ttl, null, "<fallback>"]: the script ran somewhere the service does not trust.
            throw PoTokenException("The service refused to issue an integrity token for this browser")
        }
        val ttl = try {
            array[1].jsonPrimitive.long
        } catch (error: Exception) {
            throw PoTokenException("The integrity token had no lifetime", error)
        }
        return IntegrityToken(decodeWebSafe(token), ttl)
    }

    /** YouTube's base64: URL-safe alphabet, `.` standing for padding. */
    fun decodeWebSafe(text: String): ByteArray =
        try {
            Base64.getDecoder().decode(text.replace('-', '+').replace('_', '/').replace('.', '='))
        } catch (error: IllegalArgumentException) {
            throw PoTokenException("Not valid base64", error)
        }

    fun encodeWebSafe(bytes: ByteArray): String =
        Base64.getEncoder().encodeToString(bytes).replace('+', '-').replace('/', '_')

    private fun descramble(scrambled: String): String {
        val bytes = decodeWebSafe(scrambled)
        return String(ByteArray(bytes.size) { (bytes[it] + 97).toByte() }, Charsets.UTF_8)
    }
}
