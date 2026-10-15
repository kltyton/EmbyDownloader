package com.dud.embydownload.integration.theater

import com.dud.embydownload.transfer.DownloadControl
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

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
        throw SubtitleExtractionException("字幕提取需要 FFmpeg，请运行插件安装器并指定 -FFmpegPath")
    }

    fun extract(input: Path, index: Int, format: String, output: Path, control: DownloadControl,
                codec: String? = null) {
        if (!Files.isRegularFile(input)) throw SubtitleExtractionException("本地媒体文件不存在")
        run(input.toString(), null, null, index, format, codec, output, control, { _, _ -> }, { _, _ -> })
    }

    fun extractStream(input: InputStream, length: Long?, index: Int, format: String, codec: String?,
                      output: Path, control: DownloadControl, onInput: (Long, Long?) -> Unit,
                      onOutput: (Long, Long?) -> Unit) {
        run("pipe:0", input, length, index, format, codec, output, control, onInput, onOutput)
    }

    private fun run(inputName: String, input: InputStream?, length: Long?, index: Int, format: String,
                    codec: String?, output: Path, control: DownloadControl,
                    onInput: (Long, Long?) -> Unit, onOutput: (Long, Long?) -> Unit) {
        control.check()
        Files.deleteIfExists(output)
        val encoder = when {
            format == "srt" && codec?.lowercase() !in setOf("subrip", "srt") -> "srt"
            format == "vtt" && codec?.lowercase() !in setOf("webvtt", "vtt") -> "webvtt"
            else -> "copy"
        }
        val muxer = when (format) {
            "sup" -> "sup"; "ass", "ssa" -> "ass"; "srt" -> "srt"; "vtt" -> "webvtt"; else -> "matroska"
        }
        val log = Files.createTempFile(Path.of(System.getProperty("java.io.tmpdir")), "subtitle-", ".log")
        var ownedProcess: Process? = null
        var pump: Thread? = null
        val sourceFailure = AtomicReference<Exception?>()
        val pipeFailure = AtomicReference<Exception?>()
        try {
            val process = ProcessBuilder(executable().toString(), "-nostdin", "-hide_banner", "-loglevel", "error", "-n",
                "-i", inputName, "-map", "0:$index", "-vn", "-an", "-dn", "-map_metadata", "-1",
                "-map_chapters", "-1", "-c:s", encoder, "-f", muxer, output.toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(log.toFile()).start()
            ownedProcess = process
            processes.add(process)
            if (input != null) {
                pump = thread(isDaemon = true, name = "subtitle-stream") {
                    try {
                        process.outputStream.use { sink ->
                            val buffer = ByteArray(64 * 1024)
                            var count = 0L
                            onInput(0, length)
                            while (true) {
                                control.check()
                                val read = try { input.read(buffer) } catch (ex: Exception) {
                                    sourceFailure.set(ex)
                                    throw ex
                                }
                                if (read < 0) {
                                    if (length != null && count != length) {
                                        val ex = IOException("播放流传输未完成：$count / $length 字节")
                                        sourceFailure.set(ex)
                                        throw ex
                                    }
                                    break
                                }
                                sink.write(buffer, 0, read)
                                count += read
                                onInput(count, length)
                            }
                        }
                    } catch (ex: Exception) {
                        pipeFailure.set(ex)
                        if (sourceFailure.get() != null) process.destroyForcibly()
                    }
                }
            }
            while (!process.waitFor(200, TimeUnit.MILLISECONDS)) {
                if (control.isCancelled) {
                    process.destroyForcibly()
                    input?.close()
                    control.check()
                }
                if (Files.isRegularFile(output)) onOutput(Files.size(output), null)
            }
            control.check()
            val inputError = sourceFailure.get()
            if (pump?.isAlive == true) input?.close()
            pump?.join(5_000)
            inputError?.let { throw it }
            if (process.exitValue() != 0) {
                val message = Files.newBufferedReader(log).use { reader -> reader.readLine().orEmpty() }.take(200)
                throw SubtitleExtractionException("字幕导出失败：$message")
            }
            sourceFailure.get()?.let { throw it }
            pipeFailure.get()?.let { throw it }
            if (pump?.isAlive == true) throw SubtitleExtractionException("字幕输入流未能结束")
            if (!Files.isRegularFile(output) || Files.size(output) == 0L) throw SubtitleExtractionException("此字幕轨道没有可导出的内容")
            val size = Files.size(output)
            onOutput(size, size)
        } finally {
            ownedProcess?.let { process ->
                if (process.isAlive) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS) }
                processes.remove(process)
            }
            if (pump?.isAlive == true) { input?.close(); pump?.join(5_000) }
            Files.deleteIfExists(log)
        }
    }
}
