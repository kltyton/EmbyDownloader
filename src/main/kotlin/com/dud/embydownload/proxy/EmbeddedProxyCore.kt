package com.dud.embydownload.proxy

import com.dud.embydownload.settings.ProxyNode
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Duration

class EmbeddedProxyException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

object EmbeddedProxyCore {
    data class LocalEndpoint(val host: String, val port: Int)

    private val appDir: Path = Path.of(System.getProperty("user.home"), ".embydownload")
    private val configPath: Path = appDir.resolve("mihomo-config.yaml")
    private val workDir: Path = appDir.resolve("mihomo")
    private val logPath: Path = appDir.resolve("mihomo.log")

    @Volatile
    private var process: Process? = null

    @Volatile
    private var currentNodeId: String? = null

    @Volatile
    private var currentSubscriptionUrl: String? = null

    @Volatile
    private var mixedPort: Int = 0

    @Volatile
    private var controllerPort: Int = 0

    private val lock = Any()

    fun ensureRunning(
        node: ProxyNode,
        subscriptionUrl: String,
        onProgress: (String) -> Unit = {}
    ): LocalEndpoint {
        if (!node.requiresEmbeddedCore()) {
            throw EmbeddedProxyException("节点 ${node.name} 不需要内置代理核心")
        }
        if (!node.isValid()) {
            throw EmbeddedProxyException("节点 ${node.name} 配置不完整")
        }
        val url = subscriptionUrl.trim()
        if (url.isBlank()) {
            throw EmbeddedProxyException("缺少订阅链接，请重新获取节点")
        }

        synchronized(lock) {
            if (process?.isAlive == true &&
                currentSubscriptionUrl == url &&
                currentNodeId == node.id &&
                mixedPort > 0
            ) {
                return LocalEndpoint("127.0.0.1", mixedPort)
            }

            if (process?.isAlive == true && currentSubscriptionUrl == url && mixedPort > 0) {
                onProgress("正在切换节点…")
                switchNode(node)
                currentNodeId = node.id
                return LocalEndpoint("127.0.0.1", mixedPort)
            }

            stopInternal()
            MihomoInstaller.ensureInstalled(onProgress)
            mixedPort = findFreePort()
            controllerPort = findFreePort()
            onProgress("正在启动内置 mihomo…")
            startProcess(url, mixedPort, controllerPort)
            MihomoControllerApi.waitUntilReady(controllerPort, Duration.ofSeconds(20))
            onProgress("正在加载订阅节点…")
            val available = MihomoControllerApi.waitUntilProxiesLoaded(controllerPort, Duration.ofSeconds(60))
            switchNode(node, available)
            waitForMixedPort(mixedPort, Duration.ofSeconds(20))
            currentSubscriptionUrl = url
            currentNodeId = node.id
            return LocalEndpoint("127.0.0.1", mixedPort)
        }
    }

    fun stop() {
        synchronized(lock) {
            stopInternal()
        }
    }

    fun isRunning(): Boolean = process?.isAlive == true

    private fun switchNode(node: ProxyNode, available: List<String>? = null) {
        val names = available ?: MihomoControllerApi.waitUntilProxiesLoaded(controllerPort, Duration.ofSeconds(30))
        val resolved = MihomoControllerApi.resolveNodeName(node.name, names)
        MihomoControllerApi.selectProxy(controllerPort, resolved)
    }

    private fun startProcess(subscriptionUrl: String, mixedPort: Int, controllerPort: Int) {
        Files.createDirectories(workDir)
        val config = MihomoConfigBuilder.build(subscriptionUrl, mixedPort, controllerPort)
        Files.writeString(configPath, config, StandardCharsets.UTF_8)

        val executable = MihomoInstaller.executablePath()
        val builder = ProcessBuilder(
            executable.toAbsolutePath().toString(),
            "-f",
            configPath.toAbsolutePath().toString(),
            "-d",
            workDir.toAbsolutePath().toString()
        )
        builder.directory(workDir.toFile())
        builder.redirectErrorStream(true)
        val proc = builder.start()
        process = proc
        drainProcessOutput(proc)
    }

    private fun drainProcessOutput(proc: Process) {
        Thread({
            runCatching {
                Files.newBufferedWriter(
                    logPath,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
                ).use { writer ->
                    proc.inputStream.bufferedReader(StandardCharsets.UTF_8).use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            writer.write(line)
                            writer.newLine()
                        }
                    }
                }
            }
        }, "mihomo-log-drain").apply {
            isDaemon = true
            start()
        }
    }

    private fun stopInternal() {
        process?.let { proc ->
            proc.destroy()
            runCatching { proc.waitFor() }
            if (proc.isAlive) {
                proc.destroyForcibly()
            }
        }
        process = null
        currentNodeId = null
        currentSubscriptionUrl = null
        mixedPort = 0
        controllerPort = 0
    }

    private fun findFreePort(): Int {
        ServerSocket(0).use { socket ->
            return socket.localPort
        }
    }

    private fun waitForMixedPort(port: Int, timeout: Duration) {
        val deadline = System.nanoTime() + timeout.toNanos()
        var lastError: Exception? = null
        while (System.nanoTime() < deadline) {
            if (process?.isAlive != true) {
                throw EmbeddedProxyException(
                    "mihomo 进程已退出：${readRecentLog()}",
                    lastError
                )
            }
            try {
                java.net.Socket().use { socket ->
                    socket.connect(InetSocketAddress("127.0.0.1", port), 400)
                }
                return
            } catch (ex: IOException) {
                lastError = ex
                Thread.sleep(120)
            }
        }
        throw EmbeddedProxyException(
            "mihomo 混合端口启动超时（$port）：${readRecentLog()}",
            lastError
        )
    }

    private fun readRecentLog(): String {
        if (!Files.exists(logPath)) return "无日志"
        return runCatching {
            Files.readAllLines(logPath, StandardCharsets.UTF_8)
                .takeLast(3)
                .joinToString(" | ")
                .ifBlank { "无日志" }
        }.getOrDefault("无法读取日志")
    }
}
