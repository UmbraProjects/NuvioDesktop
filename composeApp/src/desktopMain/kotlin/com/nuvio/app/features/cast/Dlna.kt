package com.nuvio.app.features.cast

import co.touchlab.kermit.Logger
import java.io.IOException
import java.io.StringReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource

private val dlnaLog = Logger.withTag("Cast")

/** A discovered renderer and the control endpoint playback commands go to. */
internal data class DlnaRenderer(
    val device: CastDevice,
    val host: String,
    val avTransportControl: URI,
) {
    /**
     * Kodi plays the source as it is (Dolby Vision MKV included, where its hardware can show it)
     * and seeks by byte offset through ffmpeg's demuxer, which cannot find fragments in a
     * length-less live remux. So the Dolby Vision remux only ever makes Kodi worse.
     */
    val isKodi: Boolean
        get() = listOfNotNull(device.model, device.name).any { it.contains("kodi", ignoreCase = true) || it.contains("xbmc", ignoreCase = true) }
}

internal val dlnaHttp: HttpClient by lazy {
    HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(4))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()
}

internal object DlnaDiscovery {
    private const val SSDP_HOST = "239.255.255.250"
    private const val SSDP_PORT = 1900
    private const val RENDERER = "urn:schemas-upnp-org:device:MediaRenderer:1"

    /**
     * Sends an SSDP search from every IPv4 LAN address (a PC with a VPN or Hyper-V adapter would
     * otherwise search only on whichever one the OS picks) and reads each answering renderer's
     * description for its name and AVTransport control URL.
     */
    fun discover(timeoutMs: Int = 3_000): List<DlnaRenderer> {
        val locations = linkedMapOf<String, URI>()
        val sockets = localIpv4Addresses().mapNotNull { address ->
            runCatching { DatagramSocket(InetSocketAddress(address, 0)).apply { soTimeout = 250 } }.getOrNull()
        }.ifEmpty { listOfNotNull(runCatching { DatagramSocket().apply { soTimeout = 250 } }.getOrNull()) }
        try {
            val search = (
                "M-SEARCH * HTTP/1.1\r\nHOST: $SSDP_HOST:$SSDP_PORT\r\nMAN: \"ssdp:discover\"\r\n" +
                    "MX: 2\r\nST: $RENDERER\r\n\r\n"
                ).toByteArray(Charsets.US_ASCII)
            val target = InetSocketAddress(InetAddress.getByName(SSDP_HOST), SSDP_PORT)
            repeat(2) { sockets.forEach { runCatching { it.send(DatagramPacket(search, search.size, target)) } } }
            val deadline = System.currentTimeMillis() + timeoutMs
            val buffer = ByteArray(2048)
            while (System.currentTimeMillis() < deadline) {
                sockets.forEach { socket ->
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                        val reply = String(packet.data, 0, packet.length, Charsets.US_ASCII)
                        val headers = parseSsdpHeaders(reply)
                        val usn = headers["usn"]?.substringBefore("::") ?: return@forEach
                        val location = headers["location"]?.let { runCatching { URI(it.trim()) }.getOrNull() } ?: return@forEach
                        locations.putIfAbsent(usn, location)
                    } catch (_: SocketTimeoutException) {
                    } catch (_: IOException) {
                    }
                }
            }
        } finally {
            sockets.forEach { it.close() }
        }
        return locations.mapNotNull { (usn, location) ->
            runCatching { describe(usn, location) }
                .onFailure { dlnaLog.w(it) { "Could not read renderer description at ${location.host}" } }
                .getOrNull()
        }.also { found -> dlnaLog.i { "DLNA search found ${found.size} renderer(s): ${found.joinToString { it.device.name }}" } }
    }

    internal fun parseSsdpHeaders(reply: String): Map<String, String> =
        reply.lineSequence()
            .drop(1)
            .mapNotNull { line ->
                val colon = line.indexOf(':')
                if (colon <= 0) null else line.substring(0, colon).trim().lowercase(Locale.ROOT) to line.substring(colon + 1).trim()
            }
            .toMap()

    private fun describe(usn: String, location: URI): DlnaRenderer? {
        val response = dlnaHttp.send(
            HttpRequest.newBuilder(location).timeout(Duration.ofSeconds(5)).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        if (response.statusCode() != 200) return null
        return parseDescription(usn, location, response.body())
    }

    internal fun parseDescription(usn: String, location: URI, xml: String): DlnaRenderer? {
        val document = DocumentBuilderFactory.newInstance()
            .apply {
                isNamespaceAware = false
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            }
            .newDocumentBuilder()
            .parse(InputSource(StringReader(xml)))
        val root = document.documentElement
        val base = root.firstText("URLBase")?.let { runCatching { URI(it) }.getOrNull() } ?: location
        val device = root.getElementsByTagName("device").item(0) as? Element ?: return null
        val services = device.getElementsByTagName("service")
        val control = (0 until services.length)
            .map { services.item(it) as Element }
            .firstOrNull { it.firstText("serviceType")?.contains("AVTransport") == true }
            ?.firstText("controlURL")
            ?: return null
        val name = device.firstText("friendlyName") ?: location.host
        val model = listOfNotNull(device.firstText("manufacturer"), device.firstText("modelName"))
            .joinToString(" ")
            .takeIf { it.isNotBlank() }
        return DlnaRenderer(
            device = CastDevice(id = usn, name = name, model = model),
            host = location.host,
            avTransportControl = base.resolve(control),
        )
    }

    private fun Element.firstText(tag: String): String? =
        getElementsByTagName(tag).item(0)?.textContent?.trim()?.takeIf { it.isNotEmpty() }

    private fun localIpv4Addresses(): List<InetAddress> =
        runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual && it.supportsMulticast() }
                .flatMap { it.inetAddresses.toList() }
                .filter { it is Inet4Address && it.isSiteLocalAddress }
        }.getOrDefault(emptyList())
}

internal class UpnpException(val code: String?, message: String) : IOException(message)

/** AVTransport:1 control: the handful of actions casting needs. */
internal class DlnaAvTransport(private val control: URI) {
    fun setUri(uri: String, metadata: String) =
        call("SetAVTransportURI", "<CurrentURI>${xmlEscape(uri)}</CurrentURI><CurrentURIMetaData>${xmlEscape(metadata)}</CurrentURIMetaData>")

    /** LG answers Play only once it has buffered, so this waits longer than the other calls. */
    fun play() = call("Play", "<Speed>1</Speed>", timeoutSeconds = 45)

    fun pause() = call("Pause", "")

    fun stop() = call("Stop", "")

    fun seek(positionMs: Long) = call("Seek", "<Unit>REL_TIME</Unit><Target>${formatHms(positionMs)}</Target>", timeoutSeconds = 45)

    fun transportState(): String? = call("GetTransportInfo", "").tag("CurrentTransportState")

    /** Position and duration in ms; either is null when the renderer does not know it yet. */
    fun position(): Pair<Long?, Long?> {
        val body = call("GetPositionInfo", "")
        return parseHms(body.tag("RelTime")) to parseHms(body.tag("TrackDuration"))
    }

    private fun call(action: String, arguments: String, timeoutSeconds: Long = 10): String {
        val envelope = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body><u:$action xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\"><InstanceID>0</InstanceID>$arguments</u:$action></s:Body></s:Envelope>"
        val request = HttpRequest.newBuilder(control)
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .header("Content-Type", "text/xml; charset=\"utf-8\"")
            .header("SOAPACTION", "\"urn:schemas-upnp-org:service:AVTransport:1#$action\"")
            .POST(HttpRequest.BodyPublishers.ofString(envelope))
            .build()
        val response = try {
            dlnaHttp.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException(error)
        }
        if (response.statusCode() != 200) {
            val body = response.body()
            throw UpnpException(body.tag("errorCode"), "$action failed: ${body.tag("errorDescription") ?: "HTTP ${response.statusCode()}"}")
        }
        return response.body()
    }
}

private fun String.tag(name: String): String? =
    Regex("<(?:\\w+:)?$name>([^<]*)</(?:\\w+:)?$name>").find(this)?.groupValues?.get(1)?.trim()

internal fun xmlEscape(value: String): String = buildString(value.length) {
    value.forEach { c ->
        when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> append(c)
        }
    }
}

internal fun formatHms(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d:%02d".format(total / 3600, (total % 3600) / 60, total % 60)
}

internal fun formatHmsMillis(ms: Long): String = formatHms(ms) + ".%03d".format(ms.coerceAtLeast(0) % 1000)

/** "0:56:28", "00:56:28.385"; null for blanks and "NOT_IMPLEMENTED". */
internal fun parseHms(value: String?): Long? {
    val parts = value?.trim()?.split(':')?.takeIf { it.size == 3 } ?: return null
    val hours = parts[0].toLongOrNull() ?: return null
    val minutes = parts[1].toLongOrNull() ?: return null
    val seconds = parts[2].toDoubleOrNull() ?: return null
    return hours * 3_600_000 + minutes * 60_000 + (seconds * 1000).toLong()
}

/**
 * The DIDL-Lite item a renderer is given with the URI. LG's player takes the length from
 * `duration` when the stream itself cannot say (the length-less MP4 remux).
 */
internal fun didlMetadata(title: String, url: String, mime: String, dlnaFeatures: String, sizeBytes: Long?, durationMs: Long?): String {
    val attributes = buildString {
        append(" protocolInfo=\"http-get:*:${xmlEscape(mime)}:${xmlEscape(dlnaFeatures)}\"")
        sizeBytes?.let { append(" size=\"$it\"") }
        durationMs?.let { append(" duration=\"${formatHmsMillis(it)}\"") }
    }
    return "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
        "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\"><item id=\"0\" parentID=\"-1\" restricted=\"1\">" +
        "<dc:title>${xmlEscape(title)}</dc:title><upnp:class>object.item.videoItem.movie</upnp:class>" +
        "<res$attributes>${xmlEscape(url)}</res></item></DIDL-Lite>"
}
