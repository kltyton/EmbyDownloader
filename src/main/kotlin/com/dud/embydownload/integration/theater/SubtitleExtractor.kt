package com.dud.embydownload.integration.theater

import com.dud.embydownload.transfer.DownloadControl
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap

class SubtitleExtractionException(message: String) : RuntimeException(message)

object SubtitleExtractor {
    private val processes = ConcurrentHashMap.newKeySet<Process>()

    fun shutdown() {
        processes.forEach { process -> process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS) }
    }
    fun executable(): Path {
        val bundled = Path.of(System.getProperty("user.dir"), "native", "windows", "ffmpeg.exe")
        if (Files.isRegularFile(bundled)) return bundled
        System.getenv("PATH").orEmpty().split(';').filter { it.isNotBlank() }.forEach { directory ->
            val candidate = Path.of(directory, "ffmpeg.exe")
            if (Files.isRegularFile(candidate)) return candidate
        }
        throw SubtitleExtractionException("图形字幕导出需要 FFmpeg，请运行插件安装器并指定 -FFmpegPath")
    }

    fun extract(input: Path, index: Int, format: String, output: Path, control: DownloadControl) {
        if (!Files.isRegularFile(input)) throw SubtitleExtractionException("本地媒体文件不存在")
        control.check()
        Files.deleteIfExists(output)
        val ffmpeg = executable()
        val log = Files.createTempFile(Path.of(System.getProperty("java.io.tmpdir")), "subtitle-", ".log")
        var ownedProcess: Process? = null
        try {
            val process = ProcessBuilder(ffmpeg.toString(), "-nostdin", "-hide_banner", "-loglevel", "error", "-n",
                "-i", input.toString(), "-map", "0:$index", "-c", "copy", "-f",
                when (format) { "sup" -> "sup"; "ass", "ssa" -> "ass"; "srt" -> "srt"; "vtt" -> "webvtt"; else -> "matroska" }, output.toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(log.toFile()).start()
            ownedProcess = process
            processes.add(process)
            while (!process.waitFor(200, TimeUnit.MILLISECONDS)) {
                if (control.isCancelled) { process.destroyForcibly(); control.check() }
            }
            control.check()
            if (process.exitValue() != 0) {
                val message = Files.newBufferedReader(log).use { reader -> reader.readLine().orEmpty() }.take(200)
                throw SubtitleExtractionException("字幕导出失败：$message")
            }
            if (!Files.isRegularFile(output) || Files.size(output) == 0L) throw SubtitleExtractionException("此字幕轨道没有可导出的内容")
        } finally {
            ownedProcess?.let { process ->
                if (process.isAlive) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS) }
                processes.remove(process)
            }
            Files.deleteIfExists(log)
        }
    }
}
