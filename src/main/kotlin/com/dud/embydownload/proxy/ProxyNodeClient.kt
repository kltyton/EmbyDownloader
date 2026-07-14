package com.dud.embydownload.proxy

import com.dud.embydownload.settings.ProxyNode
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.UUID

class ProxyFetchException(message: String) : RuntimeException(message)

object ProxyNodeClient {
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    fun fetchNodes(subscriptionUrl: String): List<ProxyNode> {
        val url = subscriptionUrl.trim()
        if (url.isBlank()) throw ProxyFetchException("请填写节点配置 URL")
        if (!url.startsWith("http://") && !url.startsWith("https://") &&
            !url.startsWith("socks5://") && !url.startsWith("socks://")
        ) {
            throw ProxyFetchException("URL 需以 http://、https:// 或 socks5:// 开头")
        }

        tryParseAsDirectProxy(url)?.let { return listOf(it) }

        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .header("Accept", "*/*")
            .header("User-Agent", MihomoInstaller.USER_AGENT)
            .GET()
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw ProxyFetchException("获取失败（HTTP ${response.statusCode()}）")
        }

        val body = response.body().trim()
        if (body.isBlank()) throw ProxyFetchException("节点接口返回为空")

        val decoded = ClashSubscriptionParser.decodeSubscriptionBody(body)
        val nodes = parseNodes(decoded)
        if (nodes.isEmpty()) {
            throw ProxyFetchException(buildEmptyNodesMessage(decoded))
        }
        return nodes
    }

    fun parseNodes(body: String): List<ProxyNode> {
        val decoded = ClashSubscriptionParser.decodeSubscriptionBody(body)

        // Clash YAML 订阅
        if (ClashSubscriptionParser.looksLikeClash(decoded)) {
            return ClashSubscriptionParser.parse(decoded)
                .filter { it.isValid() && !isPlaceholderNode(it) }
                .mapIndexed { index, node ->
                    if (node.id.isBlank()) node.copy(id = "node-$index-${UUID.randomUUID().toString().take(8)}") else node
                }
        }

        // 纯文本：分享链接或每行一个代理 URL
        if (!decoded.trimStart().startsWith("{") && !decoded.trimStart().startsWith("[")) {
            if (ClashSubscriptionParser.looksLikeShareLinkList(decoded)) {
                return ShareLinkParser.parseLines(decoded)
                    .filter { it.isValid() && !isPlaceholderNode(it) }
                    .mapIndexed { index, node ->
                        if (node.id.isBlank()) node.copy(id = "node-$index-${UUID.randomUUID().toString().take(8)}") else node
                    }
            }
            return parsePlainProxyLines(decoded)
        }

        val root = JsonParser.parseString(decoded)
        val list = mutableListOf<ProxyNode>()
        when {
            root.isJsonArray -> root.asJsonArray.forEach { list += parseNodeElement(it) }
            root.isJsonObject -> {
                val obj = root.asJsonObject
                when {
                    obj.has("nodes") && obj.get("nodes").isJsonArray ->
                        obj.getAsJsonArray("nodes").forEach { list += parseNodeElement(it) }
                    obj.has("data") && obj.get("data").isJsonArray ->
                        obj.getAsJsonArray("data").forEach { list += parseNodeElement(it) }
                    obj.has("proxies") && obj.get("proxies").isJsonArray ->
                        obj.getAsJsonArray("proxies").forEach { list += parseNodeElement(it) }
                    else -> list += parseNodeElement(obj)
                }
            }
        }
        return list.filter { it.isValid() && !isPlaceholderNode(it) }.mapIndexed { index, node ->
            if (node.id.isBlank()) node.copy(id = "node-$index-${UUID.randomUUID().toString().take(8)}") else node
        }
    }

    private fun isPlaceholderNode(node: ProxyNode): Boolean =
        node.host == "127.0.0.1" && node.port == 1111

    private fun parsePlainProxyLines(body: String): List<ProxyNode> =
        body.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .mapIndexedNotNull { index, line ->
                ShareLinkParser.parse(line, "节点 ${index + 1}")
                    ?: parseProxyUri(line, "节点 ${index + 1}")
            }
            .toList()

    private fun parseNodeElement(element: JsonElement): ProxyNode {
        if (element.isJsonPrimitive && element.asJsonPrimitive.isString) {
            return parseProxyUri(element.asString, "节点") ?: ProxyNode()
        }
        if (!element.isJsonObject) return ProxyNode()
        val obj = element.asJsonObject

        val proxyUri = stringOf(obj, "proxy", "proxyUrl", "url")
        if (!proxyUri.isNullOrBlank() && looksLikeProxyUri(proxyUri) && !obj.has("host") && !obj.has("relayUrl")) {
            return parseProxyUri(proxyUri, stringOf(obj, "name", "title", "remark") ?: "节点")
                ?: ProxyNode()
        }

        val type = (stringOf(obj, "type", "protocol") ?: "http").lowercase()
        val name = stringOf(obj, "name", "title", "remark") ?: "未命名节点"
        val host = stringOf(obj, "host", "server", "ip", "hostname")
        val port = intOf(obj, "port")
        val username = stringOf(obj, "username", "user")
        val password = stringOf(obj, "password", "pass")
        val relayUrl = stringOf(obj, "relayUrl", "relay", "downloadUrl", "endpoint")
        val remark = stringOf(obj, "remark", "description", "region")
        val id = stringOf(obj, "id") ?: ""

        val normalizedType = when {
            type.contains("sock") -> "socks5"
            type.contains("relay") || type.contains("forward") -> "relay"
            !relayUrl.isNullOrBlank() && host.isNullOrBlank() -> "relay"
            else -> "http"
        }

        return ProxyNode(
            id = id.ifBlank { UUID.randomUUID().toString() },
            name = name,
            type = normalizedType,
            host = host,
            port = port,
            username = username,
            password = password,
            relayUrl = relayUrl,
            remark = remark
        )
    }

    private fun parseProxyUri(raw: String, defaultName: String): ProxyNode? {
        val value = raw.trim()
        return try {
            when {
                value.startsWith("socks5://") || value.startsWith("socks://") -> {
                    val uri = URI(value)
                    ProxyNode(
                        id = UUID.randomUUID().toString(),
                        name = defaultName,
                        type = "socks5",
                        host = uri.host,
                        port = uri.port.takeIf { it > 0 } ?: 1080,
                        username = uri.userInfo?.substringBefore(':'),
                        password = uri.userInfo?.substringAfter(':', "")?.ifBlank { null }
                    )
                }
                value.startsWith("http://") || value.startsWith("https://") -> {
                    val uri = URI(value)
                    if (uri.path.isNullOrBlank() || uri.path == "/") {
                        ProxyNode(
                            id = UUID.randomUUID().toString(),
                            name = defaultName,
                            type = "http",
                            host = uri.host,
                            port = uri.port.takeIf { it > 0 } ?: if (value.startsWith("https")) 443 else 80,
                            username = uri.userInfo?.substringBefore(':'),
                            password = uri.userInfo?.substringAfter(':', "")?.ifBlank { null }
                        )
                    } else {
                        ProxyNode(
                            id = UUID.randomUUID().toString(),
                            name = defaultName,
                            type = "relay",
                            relayUrl = value.removeSuffix("/")
                        )
                    }
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun looksLikeProxyUri(value: String): Boolean =
        value.startsWith("http://") || value.startsWith("https://") ||
            value.startsWith("socks5://") || value.startsWith("socks://")

    private fun stringOf(obj: JsonObject, vararg keys: String): String? {
        for (key in keys) {
            val el = obj.get(key) ?: continue
            if (el.isJsonNull) continue
            if (el.isJsonPrimitive) {
                val text = el.asString.trim()
                if (text.isNotBlank()) return text
            }
        }
        return null
    }

    private fun intOf(obj: JsonObject, vararg keys: String): Int? {
        for (key in keys) {
            val el = obj.get(key) ?: continue
            if (el.isJsonNull) continue
            if (el.isJsonPrimitive) {
                return runCatching { el.asInt }.getOrNull()
                    ?: runCatching { el.asString.toInt() }.getOrNull()
            }
        }
        return null
    }

    private fun tryParseAsDirectProxy(url: String): ProxyNode? {
        if (url.startsWith("socks5://") || url.startsWith("socks://")) {
            return parseProxyUri(url, "SOCKS5 代理")
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null

        val uri = URI.create(url)
        val host = uri.host ?: return null
        val barePath = uri.path.isNullOrBlank() || uri.path == "/"
        val noQuery = uri.query.isNullOrBlank()
        if (!barePath || !noQuery) return null

        val isLocal = host == "127.0.0.1" ||
            host.equals("localhost", ignoreCase = true) ||
            host == "[::1]" ||
            host == "::1"
        if (!isLocal) return null

        val port = uri.port.takeIf { it > 0 } ?: if (url.startsWith("https")) 443 else 80
        return ProxyNode(
            id = UUID.randomUUID().toString(),
            name = "本地 Clash ($host:$port)",
            type = "http",
            host = host,
            port = port,
            username = uri.userInfo?.substringBefore(':'),
            password = uri.userInfo?.substringAfter(':', "")?.ifBlank { null }
        )
    }

    private fun buildEmptyNodesMessage(decoded: String): String {
        if (ClashSubscriptionParser.looksLikeHtml(decoded)) {
            return "接口返回的是网页内容而非节点配置，请检查订阅 URL 是否正确"
        }
        return "未解析到可用节点。支持 Clash/mihomo 订阅、JSON 或 HTTP/SOCKS5 列表。"
    }

    fun buildRelayDownloadUrl(relayUrl: String, originalUrl: String): String {
        val base = relayUrl.trim()
        val encoded = URLEncoder.encode(originalUrl, StandardCharsets.UTF_8)
        return when {
            base.contains("{url}") -> base.replace("{url}", encoded)
            base.contains("?") -> "$base&url=$encoded"
            else -> "$base?url=$encoded"
        }
    }
}
