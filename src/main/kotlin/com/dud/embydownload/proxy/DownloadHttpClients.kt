package com.dud.embydownload.proxy

import com.dud.embydownload.settings.AppSettings
import com.dud.embydownload.settings.ProxyNode
import java.net.Authenticator
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.URI
import java.net.http.HttpClient
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

object DownloadHttpClients {
    private val cache = ConcurrentHashMap<String, HttpClient>()

    fun defaultClient(): HttpClient = cache.computeIfAbsent("default") {
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
    }

    fun downloadClient(): HttpClient = clientForNode(AppSettings.proxyConfig.downloadNode())

    fun loginClient(): HttpClient = clientForNode(AppSettings.proxyConfig.loginNode())

    fun searchClient(): HttpClient = clientForNode(AppSettings.proxyConfig.searchNode())

    fun resolveDownloadUri(originalUrl: String): URI {
        val node = AppSettings.proxyConfig.downloadNode()
        return if (node != null && node.type.equals("relay", true) && !node.relayUrl.isNullOrBlank()) {
            URI.create(ProxyNodeClient.buildRelayDownloadUrl(node.relayUrl!!, originalUrl))
        } else {
            URI.create(originalUrl)
        }
    }

    fun resolveApiUri(originalUrl: String, node: ProxyNode?): URI {
        return if (node != null && node.type.equals("relay", true) && !node.relayUrl.isNullOrBlank()) {
            URI.create(ProxyNodeClient.buildRelayDownloadUrl(node.relayUrl!!, originalUrl))
        } else {
            URI.create(originalUrl)
        }
    }

    fun activeNodeLabel(): String? = AppSettings.proxyConfig.downloadNode()?.displayLabel()

    fun invalidate() {
        cache.keys.filter { it != "default" }.forEach { cache.remove(it) }
    }

    fun shutdownEmbeddedCore() {
        EmbeddedProxyCore.stop()
        invalidate()
    }

    fun loginTimeoutSeconds(): Long =
        if (AppSettings.proxyConfig.loginNode() != null) 120 else 30

    private fun clientForNode(node: ProxyNode?): HttpClient {
        if (node == null) return defaultClient()
        return when (node.type.lowercase()) {
            "relay" -> defaultClient()
            else -> cache.computeIfAbsent(cacheKey(node)) { buildProxyClient(node) }
        }
    }

    private fun cacheKey(node: ProxyNode): String =
        "${node.type}|${node.host}|${node.port}|${node.username}|${node.id}|embedded=${node.requiresEmbeddedCore()}"

    private fun buildProxyClient(node: ProxyNode): HttpClient {
        val (host, port) = resolveProxyEndpoint(node)
        val proxyType = when {
            node.requiresEmbeddedCore() -> Proxy.Type.SOCKS
            node.type.lowercase() in setOf("socks", "socks5") -> Proxy.Type.SOCKS
            else -> Proxy.Type.HTTP
        }
        val selector = RoutingProxySelector(host, port, proxyType)

        if (!node.username.isNullOrBlank() && !node.requiresEmbeddedCore()) {
            Authenticator.setDefault(object : Authenticator() {
                override fun getPasswordAuthentication(): PasswordAuthentication? {
                    if (requestingHost.equals(host, true) && requestingPort == port) {
                        return PasswordAuthentication(
                            node.username,
                            (node.password ?: "").toCharArray()
                        )
                    }
                    return null
                }
            })
        }

        return HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .proxy(selector)
            .build()
    }

    private fun resolveProxyEndpoint(node: ProxyNode): Pair<String, Int> {
        if (node.requiresEmbeddedCore()) {
            val endpoint = EmbeddedProxyCore.ensureRunning(node, AppSettings.proxyConfig.subscriptionUrl)
            return endpoint.host to endpoint.port
        }
        val host = node.host ?: throw IllegalArgumentException("节点缺少 host")
        val port = node.port ?: throw IllegalArgumentException("节点缺少 port")
        return host to port
    }
}
