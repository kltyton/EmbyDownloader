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

    fun defaultClient(redirects: HttpClient.Redirect = HttpClient.Redirect.NORMAL): HttpClient = cache.computeIfAbsent("default:$redirects") {
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(redirects)
            .build()
    }

    fun downloadClient(): HttpClient = clientForNode(AppSettings.proxyConfig.downloadNode(), HttpClient.Redirect.NEVER)

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
        cache.keys.filter { !it.startsWith("default:") }.forEach { cache.remove(it) }
    }

    fun shutdownEmbeddedCore() {
        EmbeddedProxyCore.stop()
        invalidate()
    }

    fun loginTimeoutSeconds(): Long =
        if (AppSettings.proxyConfig.loginNode() != null) 120 else 30

    private fun clientForNode(node: ProxyNode?, redirects: HttpClient.Redirect = HttpClient.Redirect.NORMAL): HttpClient {
        if (node == null) return defaultClient(redirects)
        return when (node.type.lowercase()) {
            "relay" -> defaultClient(redirects)
            else -> cache.computeIfAbsent("${cacheKey(node)}|$redirects") { buildProxyClient(node, redirects) }
        }
    }

    private fun cacheKey(node: ProxyNode): String =
        "${node.type}|${node.host}|${node.port}|${node.username}|${node.id}|embedded=${node.requiresEmbeddedCore()}"

    private fun buildProxyClient(node: ProxyNode, redirects: HttpClient.Redirect): HttpClient {
        val (host, port) = resolveProxyEndpoint(node)
        val proxyType = when {
            node.requiresEmbeddedCore() -> Proxy.Type.HTTP
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
            .followRedirects(redirects)
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
