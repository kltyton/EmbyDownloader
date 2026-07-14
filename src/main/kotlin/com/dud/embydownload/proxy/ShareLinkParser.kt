package com.dud.embydownload.proxy

import com.dud.embydownload.settings.ProxyNode
import com.google.gson.JsonParser
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID

object ShareLinkParser {
    fun parse(line: String, defaultName: String): ProxyNode? {
        val value = line.trim()
        return when {
            value.startsWith("ss://", ignoreCase = true) -> parseShadowsocks(value, defaultName)
            value.startsWith("vmess://", ignoreCase = true) -> parseVmess(value, defaultName)
            value.startsWith("trojan://", ignoreCase = true) -> parseTrojan(value, defaultName)
            value.startsWith("vless://", ignoreCase = true) -> parseVless(value, defaultName)
            value.startsWith("hysteria2://", ignoreCase = true) -> parseHysteria2(value, defaultName)
            value.startsWith("hysteria://", ignoreCase = true) -> parseHysteria2(value, defaultName)
            else -> null
        }
    }

    fun parseLines(body: String): List<ProxyNode> =
        body.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .mapIndexedNotNull { index, line ->
                parse(line, "节点 ${index + 1}")
            }
            .toList()

    private fun parseShadowsocks(raw: String, defaultName: String): ProxyNode? {
        val withoutScheme = raw.removePrefix("ss://").removePrefix("SS://")
        val hashIndex = withoutScheme.indexOf('#')
        val name = if (hashIndex >= 0) {
            decodePart(withoutScheme.substring(hashIndex + 1)) ?: defaultName
        } else {
            defaultName
        }
        val main = if (hashIndex >= 0) withoutScheme.substring(0, hashIndex) else withoutScheme

        val atIndex = main.lastIndexOf('@')
        if (atIndex >= 0) {
            val userInfo = decodePart(main.substring(0, atIndex)) ?: main.substring(0, atIndex)
            val hostPort = main.substring(atIndex + 1)
            val colon = userInfo.indexOf(':')
            if (colon <= 0) return null
            val method = userInfo.substring(0, colon)
            val password = userInfo.substring(colon + 1)
            val (host, port) = parseHostPort(hostPort) ?: return null
            return node(
                defaultName = name,
                type = "ss",
                host = host,
                port = port,
                cipher = method,
                password = password,
                rawLink = raw
            )
        }

        val decoded = decodePart(main) ?: return null
        val legacyAt = decoded.lastIndexOf('@')
        if (legacyAt >= 0) {
            val userInfo = decoded.substring(0, legacyAt)
            val hostPort = decoded.substring(legacyAt + 1)
            val colon = userInfo.indexOf(':')
            if (colon <= 0) return null
            val (host, port) = parseHostPort(hostPort) ?: return null
            return node(
                defaultName = name,
                type = "ss",
                host = host,
                port = port,
                cipher = userInfo.substring(0, colon),
                password = userInfo.substring(colon + 1),
                rawLink = raw
            )
        }
        return null
    }

    private fun parseVmess(raw: String, defaultName: String): ProxyNode? {
        val payload = raw.removePrefix("vmess://").removePrefix("VMESS://")
        val jsonText = decodePart(payload) ?: return null
        val obj = runCatching { JsonParser.parseString(jsonText).asJsonObject }.getOrNull() ?: return null
        val host = obj.get("add")?.asString ?: return null
        val port = obj.get("port")?.asString?.toIntOrNull()
            ?: runCatching { obj.get("port")?.asInt }.getOrNull() ?: return null
        val uuid = obj.get("id")?.asString ?: return null
        val name = obj.get("ps")?.asString?.takeIf { it.isNotBlank() } ?: defaultName
        val network = obj.get("net")?.asString ?: "tcp"
        val tlsEnabled = obj.get("tls")?.asString.equals("tls", ignoreCase = true)
        return node(
            defaultName = name,
            type = "vmess",
            host = host,
            port = port,
            uuid = uuid,
            alterId = obj.get("aid")?.asString?.toIntOrNull() ?: 0,
            cipher = obj.get("scy")?.asString ?: "auto",
            network = network,
            wsPath = obj.get("path")?.asString,
            wsHost = obj.get("host")?.asString,
            tls = tlsEnabled,
            sni = obj.get("sni")?.asString ?: obj.get("host")?.asString,
            rawLink = raw
        )
    }

    private fun parseTrojan(raw: String, defaultName: String): ProxyNode? {
        val uri = URI(raw)
        val password = uri.userInfo ?: return null
        val host = uri.host ?: return null
        val port = uri.port.takeIf { it > 0 } ?: 443
        val name = uri.fragment?.let { decodePart(it) } ?: defaultName
        val query = parseQuery(uri.rawQuery)
        return node(
            defaultName = name,
            type = "trojan",
            host = host,
            port = port,
            password = password,
            tls = true,
            sni = query["sni"] ?: query["peer"] ?: host,
            rawLink = raw
        )
    }

    private fun parseVless(raw: String, defaultName: String): ProxyNode? {
        val uri = URI(raw)
        val uuid = uri.userInfo ?: return null
        val host = uri.host ?: return null
        val port = uri.port.takeIf { it > 0 } ?: 443
        val name = uri.fragment?.let { decodePart(it) } ?: defaultName
        val query = parseQuery(uri.rawQuery)
        return node(
            defaultName = name,
            type = "vless",
            host = host,
            port = port,
            uuid = uuid,
            network = query["type"] ?: "tcp",
            wsPath = query["path"],
            wsHost = query["host"],
            tls = query["security"].equals("tls", ignoreCase = true) ||
                query["security"].equals("reality", ignoreCase = true),
            sni = query["sni"] ?: query["peer"] ?: host,
            flow = query["flow"],
            rawLink = raw
        )
    }

    private fun parseHysteria2(raw: String, defaultName: String): ProxyNode? {
        val uri = URI(raw.replace("hysteria://", "hysteria2://"))
        val auth = uri.userInfo ?: return null
        val host = uri.host ?: return null
        val port = uri.port.takeIf { it > 0 } ?: 443
        val name = uri.fragment?.let { decodePart(it) } ?: defaultName
        val query = parseQuery(uri.rawQuery)
        return node(
            defaultName = name,
            type = "hysteria2",
            host = host,
            port = port,
            password = auth,
            uuid = auth,
            sni = query["sni"],
            tls = query["insecure"] != "true",
            rawLink = raw
        )
    }

    private fun node(
        defaultName: String,
        type: String,
        host: String,
        port: Int,
        password: String? = null,
        cipher: String? = null,
        uuid: String? = null,
        alterId: Int? = null,
        network: String? = null,
        wsPath: String? = null,
        wsHost: String? = null,
        tls: Boolean = false,
        sni: String? = null,
        flow: String? = null,
        rawLink: String? = null
    ): ProxyNode = ProxyNode(
        id = UUID.randomUUID().toString(),
        name = defaultName,
        type = type,
        host = host,
        port = port,
        password = password,
        cipher = cipher,
        uuid = uuid,
        alterId = alterId,
        network = network,
        wsPath = wsPath,
        wsHost = wsHost,
        tls = tls,
        sni = sni,
        flow = flow,
        rawLink = rawLink,
        remark = type.uppercase()
    )

    private fun parseQuery(rawQuery: String?): Map<String, String> =
        rawQuery?.split('&')?.associate {
            val parts = it.split('=', limit = 2)
            parts[0] to (parts.getOrNull(1)?.let { v -> URLDecoder.decode(v, StandardCharsets.UTF_8) } ?: "")
        } ?: emptyMap()

    private fun parseHostPort(value: String): Pair<String, Int>? {
        val trimmed = value.trim().removePrefix("[").removeSuffix("]")
        val colon = trimmed.lastIndexOf(':')
        if (colon <= 0) return null
        val host = trimmed.substring(0, colon).removePrefix("[").removeSuffix("]")
        val port = trimmed.substring(colon + 1).toIntOrNull() ?: return null
        return host to port
    }

    private fun decodePart(value: String): String? {
        val text = value.trim()
        if (text.isBlank()) return null
        return runCatching { URLDecoder.decode(text, StandardCharsets.UTF_8) }.getOrNull()
            ?: runCatching {
                val padded = padBase64(text.replace('-', '+').replace('_', '/'))
                String(Base64.getDecoder().decode(padded), StandardCharsets.UTF_8)
            }.getOrNull()
    }

    private fun padBase64(value: String): String {
        val mod = value.length % 4
        return if (mod == 0) value else value + "=".repeat(4 - mod)
    }
}
