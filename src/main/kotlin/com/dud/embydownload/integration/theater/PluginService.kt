package com.dud.embydownload.integration.theater

import com.dud.embydownload.emby.EmbyApiException
import com.dud.embydownload.emby.EmbyClient
import com.dud.embydownload.emby.subtitle.SubtitleContentException
import com.dud.embydownload.emby.model.BaseItemDto
import com.dud.embydownload.emby.model.ClientIdentity
import com.dud.embydownload.emby.model.MediaSourceInfo
import com.dud.embydownload.emby.model.MediaStreamInfo
import com.dud.embydownload.emby.model.SearchMediaType
import com.dud.embydownload.proxy.DownloadHttpClients
import com.dud.embydownload.settings.AppSettings
import com.dud.embydownload.settings.MediaSaveMode
import com.dud.embydownload.transfer.DownloadCancelledException
import com.dud.embydownload.transfer.DownloadControl
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.BufferedReader
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private const val KIND_MEDIA = "MEDIA"
private const val KIND_SUBTITLE = "SUBTITLE"
private const val KIND_ARTWORK = "ARTWORK"

private const val STATE_QUEUED = "QUEUED"
private const val STATE_RUNNING = "RUNNING"
private const val STATE_PAUSED = "PAUSED"
private const val STATE_COMPLETED = "COMPLETED"
private const val STATE_FAILED = "FAILED"
private const val STATE_CANCELLED = "CANCELLED"

private const val MAX_AUTO_RETRIES = 5
private const val PROGRESS_INTERVAL_MS = 300L
private const val PROGRESS_THROTTLE_NS = 200_000_000L

private val TEXT_SUBTITLE_CODECS = setOf("ass", "ssa", "srt", "subrip", "vtt", "webvtt", "mov_text", "txt")
private val ILLEGAL_FILE_CHARS = Regex("[\\\\/:*?\"<>|]")
private val URL_PATTERN = Regex("[A-Za-z][A-Za-z0-9+.-]*://[^\\s\"']+")
private val SECRET_PATTERN = Regex("(?i)(api_key|access_token|token|password|pwd)=[^\\s&\"']+")

private class Task(
    val id: String,
    val title: String,
    val kind: String,
    val client: EmbyClient,
    val embyItem: BaseItemDto,
    val saveMode: MediaSaveMode,
    @Volatile var finalFile: Path,
    val sourceId: String? = null,
    val subtitleIndex: Int? = null,
    val subtitleFormat: String? = null,
    val mediaDependency: Task? = null,
    val localMedia: Path? = null
) {
    val partFile: Path = finalFile.resolveSibling(finalFile.fileName.toString() + ".part")

    @Volatile var state: String = STATE_QUEUED
    @Volatile var downloaded: Long = 0L
    @Volatile var total: Long = -1L
    @Volatile var speed: Long = 0L
    @Volatile var error: String? = null
    @Volatile var phase: String? = null
    @Volatile var sourceBytes: Long = 0L
    @Volatile var sourceTotal: Long = -1L
    @Volatile var control: DownloadControl = DownloadControl()
    @Volatile var workerRunning: Boolean = false
    @Volatile var lastBytes: Long = 0L
    @Volatile var lastTimeNs: Long = 0L
}

object PluginService {
    private val gson = Gson()

    private val stdout = PrintWriter(
        OutputStreamWriter(FileOutputStream(FileDescriptor.out), StandardCharsets.UTF_8),
        false
    )
    private val sendLock = Any()

    private val workerSeq = AtomicInteger(0)
    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "emby-theater-worker-${workerSeq.incrementAndGet()}").apply { isDaemon = true }
    }

    private val taskLock = Any()
    private val metadataExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "emby-theater-metadata").apply { isDaemon = true }
    }
    private val tasks = mutableListOf<Task>()
    private val dirty = AtomicBoolean(false)

    @Volatile
    private var shuttingDown = false

    @Volatile
    private var credentials: Credentials? = null

    private data class Credentials(val serverUrl: String, val accessToken: String, val userId: String, val identity: ClientIdentity)

    private val monitor = Thread({
        while (!shuttingDown) {
            try {
                Thread.sleep(PROGRESS_INTERVAL_MS)
            } catch (_: InterruptedException) {
                break
            }
            if (dirty.get() || hasActiveTasks()) pushTasks()
        }
    }, "emby-theater-monitor").apply { isDaemon = true }

    fun run() {
        Runtime.getRuntime().addShutdownHook(Thread { DownloadHttpClients.shutdownEmbeddedCore() })
        monitor.start()
        val reader = BufferedReader(InputStreamReader(System.`in`, StandardCharsets.UTF_8))
        try {
            while (true) {
                val line = reader.readLine() ?: break
                handleLine(line)
            }
        } finally {
            beginShutdown()
        }
    }

    private fun beginShutdown() {
        shuttingDown = true
        metadataExecutor.shutdownNow()
        executor.shutdown()
        monitor.interrupt()
        try {
            SubtitleExtractor.shutdown()
            DownloadHttpClients.shutdownEmbeddedCore()
        } finally {
            System.exit(0)
        }
    }

    private fun handleLine(line: String) {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return
        var requestId: JsonElement? = null
        try {
            val root = JsonParser.parseString(trimmed)
            if (!root.isJsonObject) throw IllegalArgumentException("无效的下载请求")
            val payload = root.asJsonObject
            requestId = payload.get("requestId")
            val action = payload.stringOrNull("action")
                ?: throw IllegalArgumentException("缺少下载操作")
            if (action in setOf("search", "list", "enqueue")) {
                metadataExecutor.execute { executeRequest(payload, action) }
                return
            }
            executeRequest(payload, action)
        } catch (ex: Exception) {
            replyError(requestId, ex.message)
        }
    }

    private fun executeRequest(payload: JsonObject, action: String) {
        val requestId = payload.get("requestId")
        try {
            val result: JsonElement = when (action) {
                "configure" -> handleConfigure(payload)
                "search" -> handleSearch(payload)
                "list" -> handleList(payload)
                "enqueue" -> handleEnqueue(payload)
                "tasks" -> tasksSnapshotArray()
                "control" -> handleControl(payload)
                "settings" -> handleSettings(payload)
                "clear" -> handleClear()
                else -> throw IllegalArgumentException("不支持此下载操作")
            }
            replySuccess(requestId, result)
        } catch (ex: Exception) {
            replyError(requestId, ex.message)
        }
    }

    // --- actions ---------------------------------------------------------

    private fun handleConfigure(payload: JsonObject): JsonElement {
        val serverUrl = payload.stringOrNull("serverUrl")?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("缺少服务器地址")
        val accessToken = payload.stringOrNull("accessToken")?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("缺少访问令牌")
        val userId = payload.stringOrNull("userId")?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("缺少用户标识")
        val identity = ClientIdentity(
            payload.stringOrNull("clientName") ?: throw IllegalArgumentException("缺少客户端身份"),
            payload.stringOrNull("clientVersion") ?: throw IllegalArgumentException("缺少客户端版本"),
            payload.stringOrNull("deviceName") ?: throw IllegalArgumentException("缺少设备名称"),
            payload.stringOrNull("deviceId") ?: throw IllegalArgumentException("缺少设备标识")
        )
        credentials = Credentials(EmbyClient.normalizeBaseUrl(serverUrl), accessToken, userId, identity)
        return publicSettings()
    }

    private fun handleSearch(payload: JsonObject): JsonElement {
        val client = currentClient()
        val term = payload.stringOrNull("term")?.trim().orEmpty()
        if (term.isBlank()) throw IllegalArgumentException("请输入搜索关键词")
        val mediaType = if (payload.stringOrNull("type")?.equals("MUSIC", true) == true) {
            SearchMediaType.MUSIC
        } else {
            SearchMediaType.MOVIE
        }
        val array = JsonArray()
        client.search(term, mediaType).forEach { item ->
            val id = item.Id?.takeIf { it.isNotBlank() } ?: return@forEach
            val obj = JsonObject()
            obj.addProperty("id", id)
            obj.addProperty("title", item.displayTitle())
            obj.addProperty("type", item.Type ?: "")
            array.add(obj)
        }
        return array
    }

    private fun handleList(payload: JsonObject): JsonElement {
        val client = currentClient()
        val itemId = payload.stringOrNull("itemId")?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("缺少媒体条目")
        val parent = client.getItem(itemId)
        val array = JsonArray()
        client.getDownloadableItems(parent).forEach { leaf ->
            val id = leaf.Id?.takeIf { it.isNotBlank() } ?: return@forEach
            array.add(listItemJson(client.getItem(id), id))
        }
        return array
    }

    private fun listItemJson(item: BaseItemDto, id: String): JsonObject {
        val source = item.MediaSources?.firstOrNull()
        val obj = JsonObject()
        obj.addProperty("id", id)
        obj.addProperty("title", item.displayTitle())
        obj.addProperty("type", item.Type ?: "")
        obj.addProperty("sourceId", source?.Id ?: "")
        val subtitles = JsonArray()
        subtitleStreams(source).forEach { (stream, format) ->
            val sub = JsonObject()
            sub.addProperty("index", stream.Index ?: 0)
            sub.addProperty("label", subtitleLabel(stream))
            sub.addProperty("format", format)
            sub.addProperty("requiresMedia", !isTextSubtitle(stream))
            subtitles.add(sub)
        }
        obj.add("subtitles", subtitles)
        obj.addProperty("imageAvailable", hasArtwork(item))
        return obj
    }

    private fun handleEnqueue(payload: JsonObject): JsonElement {
        val creds = credentials ?: throw IllegalStateException("尚未连接到 Emby 服务器")
        val itemIds = payload.arrayOrNull("itemIds")
            ?.mapNotNull { if (it.isJsonPrimitive) it.asString.trim() else null }
            ?.filter { it.isNotBlank() }
            .orEmpty()
        if (itemIds.isEmpty()) throw IllegalArgumentException("请先选择媒体")

        val media = payload.booleanOrFalse("media")
        val subtitles = payload.booleanOrFalse("subtitles")
        val artwork = payload.booleanOrFalse("artwork")
        if (!media && !subtitles && !artwork) {
            throw IllegalArgumentException("请至少选择媒体、字幕或封面中的一项")
        }
        val saveMode = parseMode(payload.stringOrNull("mode"))
        val directoryValue = payload.stringOrNull("directory")?.takeIf { it.isNotBlank() }
        val baseDirectory = Path.of(directoryValue ?: AppSettings.downloadPath)
        val localMedia = payload.stringOrNull("localMediaPath")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
        if (localMedia != null && !Files.isRegularFile(localMedia)) throw IllegalArgumentException("本地媒体文件不存在")
        if (localMedia != null && itemIds.size != 1) throw IllegalArgumentException("指定本地媒体时请只选择一个条目")

        val client = EmbyClient(creds.serverUrl, creds.accessToken, creds.userId, creds.identity)
        val existingNames = synchronized(taskLock) { tasks.mapTo(mutableSetOf<Path>()) { it.finalFile } }
        val reserved = mutableSetOf<Path>()
        val skipped = mutableListOf<String>()
        val created = mutableListOf<Task>()

        itemIds.forEach { rawId ->
            try {
                val parent = client.getItem(rawId)
                if (localMedia != null && parent.isContainerItem()) throw IllegalArgumentException("指定本地媒体时请选择对应单集")
                val leaves = client.getDownloadableItems(parent)
                if (leaves.isEmpty()) {
                    skipped += "《${parent.displayTitle()}》没有可下载的媒体"
                    return@forEach
                }
                leaves.forEach leafLoop@ { summary ->
                    val leafId = summary.Id?.takeIf { it.isNotBlank() }
                    if (leafId == null) {
                        skipped += "《${parent.displayTitle()}》存在缺少标识的条目"
                        return@leafLoop
                    }
                    val leaf = client.getItem(leafId)
                    val targetDir = client.suggestTargetDirectory(baseDirectory, leaf)
                    Files.createDirectories(targetDir)
                    val mediaFileName = client.suggestFileName(leaf)
                    var mediaBase = stripExtension(mediaFileName)
                    val streams = subtitleStreams(leaf.MediaSources?.firstOrNull())
                    var mediaTask: Task? = null

                    if (media) {
                        val finalFile = allocateFinal(targetDir, mediaFileName, reserved, existingNames)
                        mediaTask = Task(
                            id = UUID.randomUUID().toString(),
                            title = leaf.displayTitle(),
                            kind = KIND_MEDIA,
                            client = client,
                            embyItem = leaf,
                            saveMode = saveMode,
                            finalFile = finalFile
                        )
                        created += mediaTask
                        mediaBase = stripExtension(finalFile.fileName.toString())
                    }
                    if (subtitles) {
                        val source = leaf.MediaSources?.firstOrNull()
                        val sourceId = source?.Id
                        when {
                            streams.isEmpty() ->
                                skipped += "《${leaf.displayTitle()}》没有字幕轨道"
                            sourceId.isNullOrBlank() ->
                                skipped += "《${leaf.displayTitle()}》缺少媒体源，无法导出字幕"
                            else -> streams.forEach { (stream, format) ->
                                val fileName = subtitleFileName(mediaBase, stream, format)
                                val finalFile = allocateFinal(targetDir, fileName, reserved, existingNames)
                                created += Task(
                                    id = UUID.randomUUID().toString(),
                                    title = "${leaf.displayTitle()} · ${subtitleLabel(stream)}",
                                    kind = KIND_SUBTITLE,
                                    client = client,
                                    embyItem = leaf,
                                    saveMode = saveMode,
                                    finalFile = finalFile,
                                    sourceId = sourceId,
                                    subtitleIndex = stream.Index,
                                    subtitleFormat = format,
                                    mediaDependency = if (!isTextSubtitle(stream) && localMedia == null) mediaTask else null,
                                    localMedia = localMedia
                                )
                            }
                        }
                    }

                    if (artwork) {
                        if (hasArtwork(leaf)) {
                            val finalFile = allocateFinal(targetDir, "$mediaBase-poster.jpg", reserved, existingNames)
                            created += Task(
                                id = UUID.randomUUID().toString(),
                                title = "${leaf.displayTitle()} · 封面",
                                kind = KIND_ARTWORK,
                                client = client,
                                embyItem = leaf,
                                saveMode = saveMode,
                                finalFile = finalFile
                            )
                        } else {
                            skipped += "《${leaf.displayTitle()}》没有可用封面"
                        }
                    }
                }
            } catch (ex: Exception) {
                skipped += "《$rawId》加载失败：${sanitize(ex.message)}"
            }
        }

        if (created.isNotEmpty()) {
            synchronized(taskLock) {
                tasks.addAll(created)
                dirty.set(true)
            }
            kickScheduler()
        }

        val result = JsonObject()
        result.addProperty("added", created.size)
        val skippedArray = JsonArray()
        skipped.forEach { skippedArray.add(it) }
        result.add("skipped", skippedArray)
        return result
    }

    private fun handleControl(payload: JsonObject): JsonElement {
        val id = payload.stringOrNull("id")?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("缺少任务标识")
        val operation = payload.stringOrNull("operation")?.lowercase()
            ?: throw IllegalArgumentException("缺少控制操作")
        val task = synchronized(taskLock) { tasks.firstOrNull { it.id == id } }
            ?: throw IllegalArgumentException("任务不存在")
        when (operation) {
            "pause" -> if (task.state == STATE_RUNNING) {
                task.control.pause()
                task.state = STATE_PAUSED
                task.speed = 0
            }
            "resume" -> when (task.state) {
                STATE_PAUSED -> {
                    task.control.resume()
                    task.state = STATE_RUNNING
                }
                STATE_FAILED, STATE_CANCELLED -> requeue(task)
                else -> Unit
            }
            "retry" -> if (task.state == STATE_FAILED || task.state == STATE_CANCELLED) requeue(task)
            "cancel" -> cancelTask(task)
            else -> throw IllegalArgumentException("不支持的控制操作")
        }
        dirty.set(true)
        return JsonObject()
    }

    private fun handleSettings(payload: JsonObject): JsonElement {
        payload.stringOrNull("directory")?.takeIf { it.isNotBlank() }?.let { AppSettings.downloadPath = it }
        payload.stringOrNull("mode")?.let { AppSettings.mediaSaveMode = parseMode(it) }
        payload.numberOrNull("concurrent")?.let { AppSettings.maxConcurrentDownloads = it }
        kickScheduler()
        return publicSettings()
    }

    private fun handleClear(): JsonElement {
        synchronized(taskLock) {
            tasks.removeAll { it.state == STATE_COMPLETED || it.state == STATE_CANCELLED }
            dirty.set(true)
        }
        return JsonObject()
    }

    // --- scheduler -------------------------------------------------------

    private fun kickScheduler() {
        if (shuttingDown) return
        synchronized(taskLock) {
            val running = tasks.count { it.state == STATE_RUNNING || it.state == STATE_PAUSED }
            var slots = (AppSettings.maxConcurrentDownloads - running).coerceAtLeast(0)
            if (slots <= 0) return
            for (task in tasks) {
                if (slots <= 0) break
                if (task.state != STATE_QUEUED || task.workerRunning) continue
                val dependency = task.mediaDependency
                if (dependency != null && dependency.state != STATE_COMPLETED) {
                    if (dependency.state == STATE_FAILED || dependency.state == STATE_CANCELLED) {
                        task.state = STATE_FAILED
                        task.error = "媒体缓存尚未完成，请先重试媒体任务"
                        dirty.set(true)
                    }
                    continue
                }
                launch(task)
                slots--
            }
        }
    }

    private fun launch(task: Task) {
        task.workerRunning = true
        task.control = DownloadControl()
        task.error = null
        task.state = STATE_RUNNING
        dirty.set(true)
        executor.execute {
            try {
                runWithRetry(task)
                if (task.control.isCancelled || shuttingDown) {
                    if (!shuttingDown) deleteOwnPart(task)
                    task.state = STATE_CANCELLED
                } else {
                    moveToFinal(task)
                    task.state = STATE_COMPLETED
                    task.downloaded = fileSizeOr(task.finalFile, task.downloaded)
                    if (task.total <= 0) task.total = task.downloaded
                }
                task.speed = 0
            } catch (ex: DownloadCancelledException) {
                if (!shuttingDown) deleteOwnPart(task)
                task.state = STATE_CANCELLED
                task.speed = 0
            } catch (ex: Exception) {
                if (task.control.isCancelled) {
                    if (!shuttingDown) deleteOwnPart(task)
                    task.state = STATE_CANCELLED
                } else {
                    task.error = sanitize(ex.message)
                    task.state = STATE_FAILED
                }
                task.speed = 0
            } finally {
                task.workerRunning = false
                dirty.set(true)
                kickScheduler()
            }
        }
    }

    private fun runWithRetry(task: Task) {
        var attempt = 0
        var lastError: Exception? = null
        while (attempt <= MAX_AUTO_RETRIES) {
            task.control.check()
            try {
                perform(task)
                return
            } catch (ex: DownloadCancelledException) {
                throw ex
            } catch (ex: Exception) {
                val status = (ex as? EmbyApiException)?.statusCode
                if (ex is SubtitleExtractionException || ex is SubtitleContentException) throw ex
                if (status != null && status in 400..499 && status != 408 && status != 429) throw ex
                lastError = ex
                attempt++
                if (attempt > MAX_AUTO_RETRIES || task.control.isCancelled) break
                task.error = "连接中断，正在重试（$attempt/$MAX_AUTO_RETRIES）…"
                Thread.sleep((1_000L * attempt).coerceAtMost(8_000L))
                task.error = null
            }
        }
        throw lastError ?: EmbyApiException("下载失败")
    }

    private fun perform(task: Task) {
        Files.createDirectories(task.partFile.parent)
        task.downloaded = fileSizeOr(task.partFile, 0L)
        val progress: (Long, Long?) -> Unit = { downloaded, total -> updateProgress(task, downloaded, total) }
        when (task.kind) {
            KIND_MEDIA -> task.client.downloadItem(
                task.embyItem,
                task.partFile,
                task.control,
                task.saveMode,
                progress
            )
            KIND_SUBTITLE -> {
                val index = task.subtitleIndex!!
                val format = task.subtitleFormat!!
                val codec = task.embyItem.MediaSources.orEmpty().firstOrNull { it.Id == task.sourceId }
                    ?.MediaStreams.orEmpty().firstOrNull { it.Index == index }?.Codec
                val localInput = task.localMedia ?: task.mediaDependency?.finalFile
                if (localInput != null) {
                    SubtitleExtractor.extract(localInput, index, format, task.partFile, task.control, codec)
                    task.client.cacheExtractedSubtitle(task.embyItem, task.sourceId!!, index, format, task.partFile, task.control)
                    val size = Files.size(task.partFile)
                    updateProgress(task, size, size)
                } else if (format in setOf("sup", "mks")) {
                    extractFromPlayback(task, codec, progress)
                } else {
                    try {
                        task.client.downloadSubtitle(task.embyItem, task.sourceId!!, index,
                            format, task.partFile, task.control, progress)
                    } catch (ex: RuntimeException) {
                        if (ex !is SubtitleContentException && (ex !is EmbyApiException || ex.statusCode != 404)) throw ex
                        extractFromPlayback(task, codec, progress)
                    }
                }
            }
            KIND_ARTWORK -> task.client.downloadArtwork(
                task.embyItem,
                task.partFile,
                task.control,
                progress
            )
            else -> throw EmbyApiException("未知任务类型")
        }
    }

    private fun extractFromPlayback(task: Task, codec: String?, progress: (Long, Long?) -> Unit) {
        task.phase = "EXTRACTING"
        task.downloaded = 0
        task.total = -1
        task.sourceBytes = 0
        task.sourceTotal = -1
        dirty.set(true)
        try {
            task.client.readPlaybackStream(task.embyItem, task.sourceId!!, task.control) { input, length ->
                SubtitleExtractor.extractStream(input, length, task.subtitleIndex!!, task.subtitleFormat!!,
                    codec, task.partFile, task.control, { bytes, total ->
                        task.sourceBytes = bytes
                        task.sourceTotal = total ?: -1
                        dirty.set(true)
                    }, progress)
            }
            task.client.cacheExtractedSubtitle(task.embyItem, task.sourceId!!, task.subtitleIndex!!,
                task.subtitleFormat!!, task.partFile, task.control)
        } finally {
            task.phase = null
            dirty.set(true)
        }
    }

    private fun updateProgress(task: Task, downloaded: Long, total: Long?) {
        task.downloaded = downloaded
        if (total != null && total > 0) task.total = total
        val now = System.nanoTime()
        val last = task.lastTimeNs
        if (last <= 0L) {
            task.lastBytes = downloaded
            task.lastTimeNs = now
        } else {
            val elapsed = now - last
            if (elapsed >= PROGRESS_THROTTLE_NS) {
                task.speed = ((downloaded - task.lastBytes) * 1_000_000_000L) / elapsed
                task.lastBytes = downloaded
                task.lastTimeNs = now
            }
        }
        dirty.set(true)
    }

    private fun requeue(task: Task) {
        task.control = DownloadControl()
        task.error = null
        task.state = STATE_QUEUED
        task.downloaded = fileSizeOr(task.partFile, 0L)
        task.speed = 0L
        task.lastBytes = 0L
        task.lastTimeNs = 0L
        task.phase = null
        task.sourceBytes = 0
        task.sourceTotal = -1
        kickScheduler()
    }

    private fun cancelTask(task: Task) {
        when (task.state) {
            STATE_QUEUED -> {
                deleteOwnPart(task)
                task.state = STATE_CANCELLED
            }
            STATE_RUNNING, STATE_PAUSED -> {
                task.control.cancel()
                task.speed = 0
            }
            else -> Unit
        }
    }

    private fun moveToFinal(task: Task) {
        val part = task.partFile
        if (!Files.exists(part)) throw EmbyApiException("下载的临时文件已丢失，请重试")
        var target = task.finalFile
        if (Files.exists(target)) {
            target = collideFree(target)
            synchronized(taskLock) { task.finalFile = target }
        }
        try {
            Files.move(part, target)
        } catch (ex: java.nio.file.FileAlreadyExistsException) {
            val fallback = collideFree(target)
            synchronized(taskLock) { task.finalFile = fallback }
            Files.move(part, fallback)
        }
    }

    private fun deleteOwnPart(task: Task) {
        try {
            val part = task.partFile
            if (part.fileName.toString().endsWith(".part")) {
                Files.deleteIfExists(part)
            }
        } catch (_: Exception) {
            // 仅清理自身临时文件，失败不阻塞
        }
    }

    // --- snapshots / push ------------------------------------------------

    private fun hasActiveTasks(): Boolean = synchronized(taskLock) {
        tasks.any {
            it.state == STATE_RUNNING || it.state == STATE_PAUSED || it.state == STATE_QUEUED
        }
    }

    private fun pushTasks() {
        val array = tasksSnapshotArray()
        dirty.set(false)
        val obj = JsonObject()
        obj.addProperty("event", "tasks")
        obj.add("tasks", array)
        send(obj)
    }

    private fun tasksSnapshotArray(): JsonArray {
        val snapshot = synchronized(taskLock) { tasks.toList() }
        val array = JsonArray()
        snapshot.forEach { array.add(taskJson(it)) }
        return array
    }

    private fun taskJson(task: Task): JsonObject {
        val obj = JsonObject()
        obj.addProperty("id", task.id)
        obj.addProperty("title", task.title)
        obj.addProperty("kind", task.kind)
        obj.addProperty("file", task.finalFile.toString())
        obj.addProperty("state", task.state)
        obj.addProperty("downloaded", task.downloaded)
        obj.addProperty("total", task.total)
        obj.addProperty("speed", task.speed)
        obj.addProperty("error", task.error ?: "")
        obj.addProperty("phase", task.phase)
        obj.addProperty("sourceBytes", task.sourceBytes)
        obj.addProperty("sourceTotal", task.sourceTotal)
        return obj
    }

    // --- helpers ---------------------------------------------------------

    private fun currentClient(): EmbyClient {
        val creds = credentials ?: throw IllegalStateException("尚未连接到 Emby 服务器")
        return EmbyClient(creds.serverUrl, creds.accessToken, creds.userId, creds.identity)
    }

    private fun publicSettings(): JsonObject {
        val obj = JsonObject()
        obj.addProperty("directory", AppSettings.downloadPath)
        obj.addProperty("mode", AppSettings.mediaSaveMode.name)
        obj.addProperty("concurrent", AppSettings.maxConcurrentDownloads)
        return obj
    }

    private fun parseMode(value: String?): MediaSaveMode =
        if (value.equals("ORIGINAL_FILE", ignoreCase = true)) {
            MediaSaveMode.ORIGINAL_FILE
        } else {
            MediaSaveMode.PLAYBACK_CACHE
        }

    private fun subtitleStreams(source: MediaSourceInfo?): List<Pair<MediaStreamInfo, String>> =
        source?.MediaStreams.orEmpty()
            .filter { it.Type.equals("Subtitle", true) && it.Index != null }
            .map { it to if (isTextSubtitle(it)) subtitleFormatFor(it.Codec) else if (it.Codec.orEmpty().lowercase().contains("pgs")) "sup" else "mks" }

    private fun isTextSubtitle(stream: MediaStreamInfo): Boolean {
        if (!stream.Type.equals("Subtitle", ignoreCase = true)) return false
        if (stream.IsTextSubtitleStream == false) return false
        if (stream.IsTextSubtitleStream == true) return true
        val codec = stream.Codec?.trim()?.lowercase()
        return codec != null && codec in TEXT_SUBTITLE_CODECS
    }

    private fun subtitleFormatFor(codec: String?): String =
        when (codec?.trim()?.lowercase()) {
            "ass" -> "ass"
            "ssa" -> "ssa"
            "vtt", "webvtt" -> "vtt"
            else -> "srt"
        }

    private fun subtitleLabel(stream: MediaStreamInfo): String {
        stream.DisplayTitle?.takeIf { it.isNotBlank() }?.let { return it }
        stream.Language?.takeIf { it.isNotBlank() }?.let { return it }
        return "字幕 ${stream.Index}"
    }

    private fun hasArtwork(item: BaseItemDto): Boolean =
        !item.ImageTags?.get("Primary").isNullOrBlank() ||
            (!item.SeriesPrimaryImageTag.isNullOrBlank() && !item.SeriesId.isNullOrBlank()) ||
            (!item.AlbumPrimaryImageTag.isNullOrBlank() && !item.AlbumId.isNullOrBlank())

    private fun stripExtension(fileName: String): String {
        val dot = fileName.lastIndexOf('.')
        return if (dot > 0) fileName.substring(0, dot) else fileName
    }

    private fun subtitleFileName(mediaBase: String, stream: MediaStreamInfo, format: String): String {
        val language = stream.Language
            ?.replace(Regex("[^A-Za-z0-9]+"), "-")
            ?.trim('-')
            ?.takeIf { it.isNotBlank() }
        val suffix = language ?: stream.Index?.toString() ?: "sub"
        return "$mediaBase.$suffix.$format"
    }

    private fun allocateFinal(
        directory: Path,
        fileName: String,
        reserved: MutableSet<Path>,
        existing: Set<Path>
    ): Path {
        val safeName = sanitizeFileName(fileName)
        val dot = safeName.lastIndexOf('.')
        val base = if (dot > 0) safeName.substring(0, dot) else safeName
        val ext = if (dot > 0) safeName.substring(dot) else ""
        var index = 0
        var candidate = directory.resolve(safeName).toAbsolutePath().normalize()
        while (isTaken(candidate, reserved, existing)) {
            index++
            candidate = directory.resolve("$base ($index)$ext").toAbsolutePath().normalize()
        }
        reserved.add(candidate)
        return candidate
    }

    private fun isTaken(candidate: Path, reserved: MutableSet<Path>, existing: Set<Path>): Boolean {
        if (candidate in reserved || candidate in existing) return true
        if (Files.exists(candidate)) return true
        val part = candidate.resolveSibling(candidate.fileName.toString() + ".part")
        return Files.exists(part)
    }

    private fun collideFree(target: Path): Path {
        if (!Files.exists(target)) return target
        val directory = target.parent ?: target.root
        val name = target.fileName.toString()
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var index = 1
        while (true) {
            val candidate = directory.resolve("$base ($index)$ext")
            if (!Files.exists(candidate) &&
                !Files.exists(candidate.resolveSibling(candidate.fileName.toString() + ".part"))
            ) {
                return candidate
            }
            index++
        }
    }

    private fun fileSizeOr(file: Path, fallback: Long): Long = try {
        Files.size(file)
    } catch (_: Exception) {
        fallback
    }

    private fun sanitizeFileName(name: String): String =
        name.replace(ILLEGAL_FILE_CHARS, "_").trim().ifBlank { "emby-item" }

    private fun sanitize(message: String?): String {
        val raw = message?.trim().orEmpty().ifBlank { "下载操作失败" }
        val withoutUrl = URL_PATTERN.replace(raw, "[已隐藏地址]")
        val withoutSecret = SECRET_PATTERN.replace(withoutUrl) { match ->
            "${match.groupValues[1]}=[已隐藏]"
        }
        return withoutSecret.take(300)
    }

    private fun replySuccess(requestId: JsonElement?, result: JsonElement) {
        val obj = JsonObject()
        if (requestId != null && !requestId.isJsonNull) obj.add("requestId", requestId)
        obj.addProperty("ok", true)
        obj.add("result", result)
        send(obj)
    }

    private fun replyError(requestId: JsonElement?, message: String?) {
        val obj = JsonObject()
        if (requestId != null && !requestId.isJsonNull) obj.add("requestId", requestId)
        obj.addProperty("ok", false)
        obj.addProperty("error", sanitize(message))
        send(obj)
    }

    private fun send(obj: JsonObject) {
        val json = gson.toJson(obj)
        synchronized(sendLock) {
            stdout.print(json)
            stdout.print('\n')
            stdout.flush()
        }
    }

    private fun JsonObject.stringOrNull(name: String): String? {
        val element = get(name) ?: return null
        if (!element.isJsonPrimitive) return null
        return element.asString
    }

    private fun JsonObject.booleanOrFalse(name: String): Boolean {
        val element = get(name) ?: return false
        if (!element.isJsonPrimitive) return false
        val primitive = element.asJsonPrimitive
        return when {
            primitive.isBoolean -> primitive.asBoolean
            primitive.isString -> primitive.asString.equals("true", ignoreCase = true)
            else -> false
        }
    }

    private fun JsonObject.numberOrNull(name: String): Int? {
        val element = get(name) ?: return null
        if (!element.isJsonPrimitive) return null
        val primitive = element.asJsonPrimitive
        return when {
            primitive.isNumber -> primitive.asInt
            primitive.isString -> primitive.asString.toIntOrNull()
            else -> null
        }
    }

    private fun JsonObject.arrayOrNull(name: String): JsonArray? {
        val element = get(name) ?: return null
        return if (element.isJsonArray) element.asJsonArray else null
    }
}
