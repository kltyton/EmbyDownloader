package com.dud.embydownload.proxy

import java.nio.file.Files
import java.nio.file.Path

/**
 * 使用随安装包分发的内置 mihomo，不再联网下载。
 *
 * 期望路径（任选其一）：
 * - {安装根目录}/native/windows/mihomo.exe
 * - {安装根目录}/native/mihomo.exe
 * - 开发时：项目根目录/native/windows/mihomo.exe
 */
object MihomoInstaller {
    const val VERSION = "1.19.27"
    const val USER_AGENT = "mihomo/$VERSION"

    private const val EXE_NAME = "mihomo.exe"

    @Volatile
    private var cachedPath: Path? = null

    fun isSupportedPlatform(): Boolean =
        System.getProperty("os.name").lowercase().contains("win")

    fun executablePath(): Path =
        cachedPath ?: resolveBundledExecutable().also { cachedPath = it }

    fun isInstalled(): Boolean =
        runCatching {
            val path = executablePath()
            Files.isRegularFile(path) && Files.size(path) > 1_000_000
        }.getOrDefault(false)

    fun ensureInstalled(onProgress: (String) -> Unit = {}): Path {
        if (!isSupportedPlatform()) {
            throw EmbeddedProxyException("内置 mihomo 暂仅支持 Windows")
        }
        onProgress("正在定位内置 mihomo…")
        val path = executablePath()
        onProgress("已使用内置 mihomo：$path")
        return path
    }

    private fun resolveBundledExecutable(): Path {
        val candidates = linkedSetOf<Path>()

        fun addCandidate(path: Path?) {
            if (path == null) return
            candidates.add(path.normalize().toAbsolutePath())
        }

        val userDir = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize()
        addCandidate(userDir.resolve("native/windows/$EXE_NAME"))
        addCandidate(userDir.resolve("native/$EXE_NAME"))

        val javaHome = Path.of(System.getProperty("java.home")).toAbsolutePath().normalize()
        // jlink 镜像常见结构：image/bin/java → image/native/windows/mihomo.exe
        addCandidate(javaHome.resolve("../native/windows/$EXE_NAME"))
        addCandidate(javaHome.resolve("../../native/windows/$EXE_NAME"))
        addCandidate(javaHome.parent?.resolve("native/windows/$EXE_NAME"))

        System.getProperty("jpackage.app-path")?.let { appPath ->
            val appFile = Path.of(appPath).toAbsolutePath().normalize()
            addCandidate(appFile.parent?.resolve("native/windows/$EXE_NAME"))
            addCandidate(appFile.parent?.resolve("app/native/windows/$EXE_NAME"))
            addCandidate(appFile.parent?.parent?.resolve("native/windows/$EXE_NAME"))
        }

        // 以本类所在位置推算（IDE / jar 旁）
        runCatching {
            val location = MihomoInstaller::class.java.protectionDomain?.codeSource?.location ?: return@runCatching
            if (location.protocol != "file") return@runCatching
            var dir = Path.of(location.toURI()).toAbsolutePath().normalize()
            if (Files.isRegularFile(dir)) {
                dir = dir.parent
            }
            // build/classes/... 或 build/libs/*.jar → 向上找项目根
            repeat(8) {
                addCandidate(dir.resolve("native/windows/$EXE_NAME"))
                addCandidate(dir.resolve("native/$EXE_NAME"))
                dir = dir.parent ?: return@runCatching
            }
        }

        val found = candidates.firstOrNull { path ->
            Files.isRegularFile(path) && runCatching { Files.size(path) > 1_000_000 }.getOrDefault(false)
        }

        return found ?: throw EmbeddedProxyException(
            "未找到内置 mihomo.exe。请确认安装包中包含 native/windows/mihomo.exe，" +
                "或将文件放到：${userDir.resolve("native/windows/$EXE_NAME")}"
        )
    }
}
