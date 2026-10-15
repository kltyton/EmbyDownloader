package com.dud.embydownload.emby.subtitle

import com.dud.embydownload.transfer.DownloadControl
import java.nio.charset.StandardCharsets
import java.nio.charset.CharacterCodingException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

class SubtitleContentException(message: String) : RuntimeException(message)

object SubtitleFiles {
    private val cueTime = Regex("(?:\\d{2}:)?\\d{2}:\\d{2}[,.]\\d{3}\\s*-->")

    fun isValid(file: Path, format: String): Boolean {
        if (!Files.isRegularFile(file) || Files.size(file) == 0L) return false
        try {
            Files.newBufferedReader(file, StandardCharsets.UTF_8).use { reader ->
                val lines = reader.lineSequence().map { it.trim().removePrefix("\uFEFF") }
                    .filter { it.isNotEmpty() }.iterator()
                if (!lines.hasNext()) return false
                val first = lines.next()
                return when (format) {
                    "ass", "ssa" -> first.equals("[Script Info]", true) &&
                        lines.asSequence().any { it.startsWith("Dialogue:", true) }
                    "vtt" -> (first == "WEBVTT" || first.startsWith("WEBVTT ")) &&
                        lines.asSequence().any { cueTime.containsMatchIn(it) }
                    "srt" -> (first.all { it.isDigit() } || cueTime.containsMatchIn(first)) &&
                        (cueTime.containsMatchIn(first) || lines.asSequence().any { cueTime.containsMatchIn(it) })
                    else -> false
                }
            }
        } catch (_: CharacterCodingException) {
            return false
        }
    }

    fun cacheFile(directory: Path, identity: List<String>, format: String): Path {
        val digest = MessageDigest.getInstance("SHA-256")
        identity.forEach { value ->
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            digest.update("${bytes.size}:".toByteArray(StandardCharsets.US_ASCII))
            digest.update(bytes)
        }
        val key = digest.digest().joinToString("") { "%02x".format(it) }
        return directory.resolve(key.take(2)).resolve("$key.$format")
    }

    fun restore(cache: Path, target: Path, format: String, control: DownloadControl,
                onProgress: (Long, Long?) -> Unit): Boolean {
        if (!isValid(cache, format)) return false
        copy(cache, target, control, onProgress)
        return true
    }

    fun store(source: Path, cache: Path, control: DownloadControl) {
        Files.createDirectories(cache.parent)
        val temporary = cache.resolveSibling("${cache.fileName}.${UUID.randomUUID()}.part")
        try {
            copy(source, temporary, control) { _, _ -> }
            Files.move(temporary, cache, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun copy(source: Path, target: Path, control: DownloadControl,
                     onProgress: (Long, Long?) -> Unit) {
        control.check()
        Files.createDirectories(target.parent)
        val total = Files.size(source)
        var count = 0L
        Files.newInputStream(source).use { input ->
            Files.newOutputStream(target).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    control.check()
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    count += read
                    onProgress(count, total)
                }
            }
        }
        control.check()
    }
}
