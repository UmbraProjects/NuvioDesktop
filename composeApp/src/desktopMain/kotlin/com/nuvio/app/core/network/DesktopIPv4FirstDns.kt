package com.nuvio.app.core.network

import okhttp3.Dns
import java.net.Inet4Address
import java.net.InetAddress

internal class DesktopIPv4FirstDns(private val delegate: Dns = Dns.SYSTEM) : Dns {
    override fun lookup(hostname: String): List<InetAddress> =
        delegate.lookup(hostname).sortedBy { address -> if (address is Inet4Address) 0 else 1 }
}
