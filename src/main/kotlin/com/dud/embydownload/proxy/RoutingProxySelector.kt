package com.dud.embydownload.proxy

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

internal class RoutingProxySelector(
    private val proxyHost: String,
    private val proxyPort: Int,
    private val proxyType: Proxy.Type
) : ProxySelector() {
    override fun select(uri: URI?): List<Proxy> {
        if (uri == null || shouldBypass(uri)) {
            return listOf(Proxy.NO_PROXY)
        }
        return listOf(Proxy(proxyType, InetSocketAddress(proxyHost, proxyPort)))
    }

    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit

    private fun shouldBypass(uri: URI): Boolean {
        val host = uri.host?.lowercase()?.trim('[', ']') ?: return true
        val port = effectivePort(uri)

        if (port == proxyPort && (host == proxyHost.lowercase() || isLoopback(host))) {
            return true
        }
        if (isLoopback(host) || isPrivateHost(host)) {
            return true
        }
        return false
    }

    private fun effectivePort(uri: URI): Int {
        if (uri.port > 0) return uri.port
        return when (uri.scheme?.lowercase()) {
            "https" -> 443
            "http" -> 80
            else -> -1
        }
    }

    private fun isLoopback(host: String): Boolean =
        host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "0:0:0:0:0:0:0:1"

    private fun isPrivateHost(host: String): Boolean {
        if (host.endsWith(".local")) return true
        val parts = host.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size != 4) return false
        return when {
            parts[0] == 10 -> true
            parts[0] == 172 && parts[1] in 16..31 -> true
            parts[0] == 192 && parts[1] == 168 -> true
            parts[0] == 169 && parts[1] == 254 -> true
            else -> false
        }
    }
}
