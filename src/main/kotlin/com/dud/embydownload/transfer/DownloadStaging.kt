package com.dud.embydownload.transfer

import com.dud.embydownload.settings.AppSettings
import java.nio.file.FileStore
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

object DownloadStaging {
    private val networkDriveCache = ConcurrentHashMap<String, Boolean>()
    fun shouldStage(finalDirectory: Path): Boolean {
        if (!AppSettings.stagingDownloadEnabled) return false
        val finalRoot = finalDirectory.toAbsolutePath().normalize()
        if (!isLikelyNetworkPath(finalRoot)) return false
        val stagingRoot = stagingDirectory().toAbsolutePath().normalize()
        return !finalRoot.startsWith(stagingRoot)
    }

    fun stagingDirectory(): Path =
        Path.of(AppSettings.stagingPath).toAbsolutePath().normalize()

    fun stagingFileFor(finalFile: Path): Path =
        stagingDirectory().resolve(finalFile.fileName)

    fun isLikelyNetworkPath(path: Path): Boolean {
        val normalized = path.toAbsolutePath().normalize().toString()
        if (normalized.startsWith("\\\\")) return true
        if (isWindowsNetworkDrive(path)) return true
        return runCatching { isNetworkFileStore(Files.getFileStore(path)) }
            .recoverCatching { isNetworkFileStore(Files.getFileStore(path.root ?: path)) }
            .getOrDefault(false)
    }

    fun moveToFinal(
        stagingFile: Path,
        finalFile: Path,
        control: DownloadControl?,
        onProgress: ((movedBytes: Long, totalBytes: Long?) -> Unit)? = null
    ): Path {
        Files.createDirectories(finalFile.parent)
        val totalBytes = runCatching { Files.size(stagingFile) }.getOrNull()?.takeIf { it > 0L }

        if (sameFileStore(stagingFile, finalFile)) {
            control?.check()
            Files.move(stagingFile, finalFile, StandardCopyOption.REPLACE_EXISTING)
            onProgress?.invoke(totalBytes ?: 0L, totalBytes)
            return finalFile
        }

        var moved = 0L
        Files.newInputStream(stagingFile).buffered(1024 * 1024).use { input ->
            Files.newOutputStream(finalFile).buffered(1024 * 1024).use { output ->
                val buffer = ByteArray(1024 * 1024)
                while (true) {
                    control?.check()
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    moved += read
                    onProgress?.invoke(moved, totalBytes)
                }
            }
        }
        Files.deleteIfExists(stagingFile)
        return finalFile
    }

    private fun sameFileStore(left: Path, right: Path): Boolean =
        runCatching {
            Files.getFileStore(left).name() == Files.getFileStore(right).name()
        }.getOrDefault(false)

    private fun isNetworkFileStore(store: FileStore): Boolean {
        val type = store.type().orEmpty().uppercase()
        if (type.contains("CIFS") || type.contains("SMB") || type.contains("NFS") || type.contains("NETWORK")) {
            return true
        }
        val name = store.name().uppercase()
        return name.contains("\\\\") || name.contains("SMB") || name.contains("NAS")
    }

    private fun isWindowsNetworkDrive(path: Path): Boolean {
        val root = path.root?.toString().orEmpty()
        if (!root.matches(Regex("[A-Za-z]:\\\\"))) return false
        val letter = root.substring(0, 1).uppercase()
        return networkDriveCache.computeIfAbsent(letter) {
            runCatching {
                ProcessBuilder("cmd", "/c", "net use $letter:")
                    .redirectErrorStream(true)
                    .start()
                    .inputStream.bufferedReader()
                    .use { it.readText() }
                    .let { text ->
                        text.contains("Remote", ignoreCase = true) ||
                            text.contains("\\\\") ||
                            text.contains("远程")
                    }
            }.getOrDefault(false)
        }
    }
}
