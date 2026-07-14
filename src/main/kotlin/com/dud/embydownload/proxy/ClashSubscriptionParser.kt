package com.dud.embydownload.proxy

import com.dud.embydownload.settings.ProxyNode
import org.yaml.snakeyaml.Yaml
import java.util.Base64
import java.util.UUID

object ClashSubscriptionParser {
    private val yaml = Yaml()

    private val SHARE_LINK_PREFIXES = listOf(
        "ss://", "ssr://", "vmess://", "vless://", "trojan://",
        "hysteria://", "hysteria2://", "tuic://", "wireguard://"
    )

    fun looksLikeClash(text: String): Boolean {
        val t = text.trim()
        return t.contains("proxies:", ignoreCase = true)
    }

    fun looksLikeHtml(text: String): Boolean {
        val t = text.trimStart().lowercase()
        return t.startsWith("<!doctype") || t.startsWith("<html")
    }

    fun looksLikeShareLinkList(text: String): Boolean {
        val lines = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .toList()
        if (lines.isEmpty()) return false
        val linkLines = lines.count { looksLikeShareLink(it) || looksLikeDirectProxyUri(it) }
        return linkLines > 0 && linkLines * 2 >= lines.size
    }

    fun countShareLinks(text: String): Int =
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .count { looksLikeShareLink(it) }

    fun decodeSubscriptionBody(body: String): String {
        var current = body.trim()
        repeat(3) {
            if (isRecognizableContent(current)) return current
            current = tryDecodeBase64(current) ?: return current
        }
        return current
    }

    @Suppress("UNCHECKED_CAST")
    fun parse(text: String): List<ProxyNode> {
        if (!looksLikeClash(text)) return emptyList()

        val root = runCatching { yaml.load<Any>(text) }.getOrNull() as? Map<String, Any?> ?: return emptyList()
        val proxies = root.entries
            .firstOrNull { it.key.toString().equals("proxies", ignoreCase = true) }
            ?.value as? List<*> ?: return emptyList()

        return proxies.mapNotNull { item ->
            val map = item as? Map<*, *> ?: return@mapNotNull null
            parseClashProxy(map)
        }
    }

    private fun parseClashProxy(map: Map<*, *>): ProxyNode? {
        val name = map.string("name") ?: return null
        val type = (map.string("type") ?: return null).lowercase()
        val server = map.string("server") ?: return null
        val port = map.int("port") ?: return null
        if (port <= 0) return null

        return when (type) {
            "http", "https" -> ProxyNode(
                id = UUID.randomUUID().toString(),
                name = name,
                type = "http",
                host = server,
                port = port,
                username = map.string("username", "user"),
                password = map.string("password", "pass"),
                remark = type.uppercase()
            )
            "socks5", "socks" -> ProxyNode(
                id = UUID.randomUUID().toString(),
                name = name,
                type = "socks5",
                host = server,
                port = port,
                username = map.string("username", "user"),
                password = map.string("password", "pass"),
                remark = type.uppercase()
            )
            "ss", "shadowsocks" -> ProxyNode(
                id = UUID.randomUUID().toString(),
                name = name,
                type = "ss",
                host = server,
                port = port,
                cipher = map.string("cipher", "method"),
                password = map.string("password"),
                remark = "SS"
            )
            "vmess" -> {
                val wsOpts = map["ws-opts"] as? Map<*, *>
                val headers = wsOpts?.get("headers") as? Map<*, *>
                ProxyNode(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    type = "vmess",
                    host = server,
                    port = port,
                    uuid = map.string("uuid", "id"),
                    alterId = map.int("alterId", "alter-id") ?: 0,
                    cipher = map.string("cipher", "security") ?: "auto",
                    network = map.string("network") ?: "tcp",
                    wsPath = wsOpts?.string("path") ?: map.string("ws-path", "path"),
                    wsHost = headers?.string("Host") ?: map.string("host"),
                    tls = map.bool("tls") || map.string("tls").equals("true", ignoreCase = true),
                    sni = map.string("servername", "sni"),
                    remark = "VMess"
                )
            }
            "trojan" -> ProxyNode(
                id = UUID.randomUUID().toString(),
                name = name,
                type = "trojan",
                host = server,
                port = port,
                password = map.string("password"),
                tls = true,
                sni = map.string("sni", "servername") ?: server,
                network = map.string("network") ?: "tcp",
                wsPath = (map["ws-opts"] as? Map<*, *>)?.string("path"),
                remark = "Trojan"
            )
            "vless" -> {
                val wsOpts = map["ws-opts"] as? Map<*, *>
                val headers = wsOpts?.get("headers") as? Map<*, *>
                ProxyNode(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    type = "vless",
                    host = server,
                    port = port,
                    uuid = map.string("uuid", "id"),
                    network = map.string("network") ?: "tcp",
                    wsPath = wsOpts?.string("path") ?: map.string("ws-path", "path"),
                    wsHost = headers?.string("Host") ?: map.string("host"),
                    tls = map.bool("tls"),
                    sni = map.string("servername", "sni") ?: server,
                    flow = map.string("flow"),
                    remark = "VLESS"
                )
            }
            else -> null
        }
    }

    private fun isRecognizableContent(text: String): Boolean =
        looksLikeClash(text) ||
            looksLikeShareLinkList(text) ||
            text.trimStart().startsWith("{") ||
            text.trimStart().startsWith("[")

    private fun looksLikeShareLink(line: String): Boolean =
        SHARE_LINK_PREFIXES.any { line.startsWith(it, ignoreCase = true) }

    private fun looksLikeDirectProxyUri(line: String): Boolean =
        line.startsWith("http://", ignoreCase = true) ||
            line.startsWith("https://", ignoreCase = true) ||
            line.startsWith("socks5://", ignoreCase = true) ||
            line.startsWith("socks://", ignoreCase = true)

    private fun tryDecodeBase64(text: String): String? {
        val compact = text.replace("\\s".toRegex(), "")
        if (compact.length < 8) return null
        if (!compact.matches(Regex("[A-Za-z0-9+/=_\\-]+"))) return null

        val variants = linkedSetOf(compact, compact.replace('-', '+').replace('_', '/'))
        for (variant in variants) {
            val padded = padBase64(variant)
            for (decoder in listOf(Base64.getDecoder(), Base64.getUrlDecoder())) {
                val decoded = runCatching {
                    String(decoder.decode(padded), Charsets.UTF_8).trim()
                }.getOrNull()?.takeIf { it.isNotBlank() && it != text } ?: continue
                if (decoded.any { ch ->
                        val code = ch.code
                        code in 0..8 || (code in 14..31 && ch != '\n' && ch != '\r' && ch != '\t')
                    }
                ) {
                    continue
                }
                return decoded
            }
        }
        return null
    }

    private fun padBase64(value: String): String {
        val mod = value.length % 4
        return if (mod == 0) value else value + "=".repeat(4 - mod)
    }

    private fun Map<*, *>.string(vararg keys: String): String? {
        for (key in keys) {
            val value = this[key] ?: continue
            val text = value.toString().trim()
            if (text.isNotBlank()) return text
        }
        return null
    }

    private fun Map<*, *>.int(vararg keys: String): Int? {
        for (key in keys) {
            val value = this[key] ?: continue
            return when (value) {
                is Number -> value.toInt()
                else -> value.toString().trim().toIntOrNull()
            }
        }
        return null
    }

    private fun Map<*, *>.bool(vararg keys: String): Boolean {
        for (key in keys) {
            val value = this[key] ?: continue
            return when (value) {
                is Boolean -> value
                else -> value.toString().equals("true", ignoreCase = true)
            }
        }
        return false
    }
}
