package com.dud.embydownload.settings

data class ProxyNode(
    val id: String = "",
    val name: String = "未命名节点",
    val type: String = "http", // http | socks5 | relay | ss | vmess | trojan | vless | hysteria2
    val host: String? = null,
    val port: Int? = null,
    val username: String? = null,
    val password: String? = null,
    val relayUrl: String? = null,
    val remark: String? = null,
    val cipher: String? = null,
    val uuid: String? = null,
    val alterId: Int? = null,
    val network: String? = null,
    val wsPath: String? = null,
    val wsHost: String? = null,
    val tls: Boolean = false,
    val sni: String? = null,
    val flow: String? = null,
    val rawLink: String? = null
) {
    fun displayLabel(): String {
        val prefix = if (requiresEmbeddedCore()) "[mihomo] " else ""
        val detail = when (type.lowercase()) {
            "relay" -> relayUrl?.takeIf { it.isNotBlank() } ?: "中继"
            else -> listOfNotNull(host, port?.toString()).joinToString(":")
                .ifBlank { type.uppercase() }
        }
        return "$prefix$name  ·  $detail"
    }

    fun summary(): String = buildString {
        append("类型：${type.uppercase()}")
        if (requiresEmbeddedCore()) append("（内置 mihomo）")
        remark?.takeIf { it.isNotBlank() }?.let { append("  ·  $it") }
        when (type.lowercase()) {
            "relay" -> relayUrl?.let { append("\n中继：$it") }
            "hysteria2", "hysteria" -> {
                if (!host.isNullOrBlank()) append("\n地址：$host:${port ?: ""}")
                sni?.let { append("\nSNI：$it") }
            }
            "ss", "shadowsocks" -> {
                if (!host.isNullOrBlank()) append("\n地址：$host:${port ?: ""}")
                cipher?.let { append("\n加密：$it") }
            }
            "vmess", "vless" -> {
                if (!host.isNullOrBlank()) append("\n地址：$host:${port ?: ""}")
                uuid?.let { append("\nUUID：${it.take(8)}…") }
                network?.let { append("\n传输：$it") }
                if (tls) append("\nTLS：是")
            }
            "trojan" -> {
                if (!host.isNullOrBlank()) append("\n地址：$host:${port ?: ""}")
                sni?.let { append("\nSNI：$it") }
            }
            else -> {
                if (!host.isNullOrBlank()) append("\n地址：$host${port?.let { ":$it" } ?: ""}")
                if (!username.isNullOrBlank()) append("\n用户：$username")
            }
        }
    }

    fun requiresEmbeddedCore(): Boolean = type.lowercase() in EMBEDDED_TYPES

    fun isValid(): Boolean = when (type.lowercase()) {
        "relay" -> !relayUrl.isNullOrBlank()
        "http", "socks", "socks5" -> !host.isNullOrBlank() && (port ?: 0) > 0
        "ss", "shadowsocks" ->
            !host.isNullOrBlank() && (port ?: 0) > 0 &&
                !cipher.isNullOrBlank() && !password.isNullOrBlank()
        "vmess" -> !host.isNullOrBlank() && (port ?: 0) > 0 && !uuid.isNullOrBlank()
        "trojan" -> !host.isNullOrBlank() && (port ?: 0) > 0 && !password.isNullOrBlank()
        "vless" -> !host.isNullOrBlank() && (port ?: 0) > 0 && !uuid.isNullOrBlank()
        "hysteria2", "hysteria" ->
            !host.isNullOrBlank() && (port ?: 0) > 0 &&
                (!password.isNullOrBlank() || !uuid.isNullOrBlank())
        else -> !name.isBlank() && requiresEmbeddedCore()
    }

    companion object {
        val EMBEDDED_TYPES = setOf(
            "ss", "shadowsocks", "vmess", "trojan", "vless", "hysteria2", "hysteria"
        )
    }
}

data class ProxyConfig(
    var enabled: Boolean = false,
    var proxyLogin: Boolean = false,
    var proxySearch: Boolean = false,
    var subscriptionUrl: String = "",
    var selectedNodeId: String? = null,
    var nodes: MutableList<ProxyNode> = mutableListOf()
) {
    fun selectedNode(): ProxyNode? {
        if (nodes.isEmpty()) return null
        return nodes.firstOrNull { it.id == selectedNodeId } ?: nodes.firstOrNull()
    }

    fun downloadNode(): ProxyNode? = if (enabled) selectedNode() else null

    fun loginNode(): ProxyNode? = if (proxyLogin) selectedNode() else null

    fun searchNode(): ProxyNode? = if (proxySearch) selectedNode() else null
}
