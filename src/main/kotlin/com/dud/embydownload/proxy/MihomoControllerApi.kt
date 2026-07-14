package com.dud.embydownload.proxy

import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

object MihomoControllerApi {
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    fun waitUntilReady(controllerPort: Int, timeout: Duration) {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (System.nanoTime() < deadline) {
            if (runCatching { getVersion(controllerPort) }.getOrDefault(false)) return
            Thread.sleep(150)
        }
        throw EmbeddedProxyException("mihomo 控制 API 启动超时")
    }

    fun waitUntilProxiesLoaded(controllerPort: Int, timeout: Duration): List<String> {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (System.nanoTime() < deadline) {
            val names = listProxyNames(controllerPort)
            if (names.isNotEmpty()) return names
            Thread.sleep(250)
        }
        throw EmbeddedProxyException("订阅节点加载超时，请检查订阅链接")
    }

    fun selectProxy(controllerPort: Int, nodeName: String) {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:$controllerPort/proxies/${MihomoConfigBuilder.PROXY_GROUP}"))
            .timeout(Duration.ofSeconds(10))
            .header("Authorization", "Bearer ${MihomoConfigBuilder.CONTROLLER_SECRET}")
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString("""{"name":${jsonString(nodeName)}}"""))
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw EmbeddedProxyException("切换节点失败（HTTP ${response.statusCode()}）：$nodeName")
        }
    }

    fun resolveNodeName(requestedName: String, available: List<String>): String {
        val target = requestedName.trim()
        if (available.contains(target)) return target
        available.firstOrNull { it.equals(target, ignoreCase = true) }?.let { return it }
        available.firstOrNull { it.trim() == target }?.let { return it }
        available.firstOrNull { it.contains(target) || target.contains(it) }?.let { return it }
        val sample = available.take(3).joinToString("、")
        throw EmbeddedProxyException(
            "订阅中未找到节点「$target」。请重新获取节点；当前可用示例：$sample"
        )
    }

    private fun getVersion(controllerPort: Int): Boolean {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:$controllerPort/version"))
            .timeout(Duration.ofSeconds(3))
            .header("Authorization", "Bearer ${MihomoConfigBuilder.CONTROLLER_SECRET}")
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        return response.statusCode() in 200..299
    }

    private fun listProxyNames(controllerPort: Int): List<String> {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:$controllerPort/proxies/${MihomoConfigBuilder.PROXY_GROUP}"))
            .timeout(Duration.ofSeconds(5))
            .header("Authorization", "Bearer ${MihomoConfigBuilder.CONTROLLER_SECRET}")
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) return emptyList()
        val root = JsonParser.parseString(response.body()).asJsonObject
        val all = root.getAsJsonArray("all") ?: return emptyList()
        return all.mapNotNull { element ->
            element.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotBlank() }
        }.filterNot { it in IGNORED_PROXY_NAMES }
    }

    private val IGNORED_PROXY_NAMES = setOf(
        "DIRECT", "REJECT", "COMPATIBLE", "PASS", "GLOBAL", "PROXY",
        MihomoConfigBuilder.PROXY_GROUP
    )

    private fun jsonString(value: String): String {
        val escaped = value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        return "\"$escaped\""
    }
}
