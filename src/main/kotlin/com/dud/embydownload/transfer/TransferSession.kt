package com.dud.embydownload.transfer

import com.dud.embydownload.AppSession
import com.dud.embydownload.emby.EmbyApiException
import com.dud.embydownload.emby.EmbyClient
import com.dud.embydownload.emby.model.BaseItemDto
import com.dud.embydownload.emby.model.DownloadPhase
import com.dud.embydownload.settings.AppSettings
import javafx.application.Platform
import javafx.beans.property.ObjectProperty
import javafx.beans.property.SimpleLongProperty
import javafx.beans.property.SimpleObjectProperty
import javafx.beans.property.SimpleStringProperty
import javafx.collections.FXCollections
import javafx.collections.ObservableList
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

enum class TransferItemState {
    QUEUED, RUNNING, PAUSED, COMPLETED, FAILED, CANCELLED
}

class TransferItem(
    val id: String = UUID.randomUUID().toString(),
    val embyItem: BaseItemDto,
    val directory: Path
) {
    val displayName: String = embyItem.displayTitle()
    val stateProperty: ObjectProperty<TransferItemState> = SimpleObjectProperty(TransferItemState.QUEUED)
    val phaseProperty: ObjectProperty<DownloadPhase> = SimpleObjectProperty(DownloadPhase.DOWNLOADING)
    val downloadedProperty = SimpleLongProperty(0)
    val totalProperty = SimpleLongProperty(embyItem.MediaSources?.firstOrNull()?.Size ?: -1L)
    val speedProperty = SimpleLongProperty(0)
    val errorProperty = SimpleStringProperty("")
    val fileNameProperty = SimpleStringProperty("")

    @Volatile
    var control = DownloadControl()

    @Volatile
    var workerRunning = false

    val state: TransferItemState
        get() = stateProperty.get()

    val isActive: Boolean
        get() = state == TransferItemState.RUNNING || state == TransferItemState.PAUSED

    val finalFile: Path
        get() = directory.resolve(
            fileNameProperty.get().ifBlank {
                embyItem.Name ?: id
            }
        )

    fun markQueuedFileName(name: String) {
        fileNameProperty.set(name)
    }
}

object TransferSession {
    private const val MAX_CONCURRENT = 2
    private const val MAX_AUTO_RETRIES = 5

    val items: ObservableList<TransferItem> = FXCollections.observableArrayList()
    val directoryProperty = SimpleObjectProperty(Path.of(AppSettings.downloadPath))
    val summaryProperty = SimpleStringProperty("暂无传输任务")

    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r, "emby-download-${workerSeq.incrementAndGet()}").apply { isDaemon = true }
    }
    private val workerSeq = AtomicInteger(0)
    private val scheduleLock = Any()

    val isBusy: Boolean
        get() = items.any { it.isActive || it.state == TransferItemState.QUEUED }

    val isRunning: Boolean
        get() = isBusy

    val itemCount: Int
        get() = items.size

    var directory: Path
        get() = directoryProperty.get()
        private set(value) {
            directoryProperty.set(value)
        }

    fun enqueue(embyItems: List<BaseItemDto>, targetDirectory: Path): Boolean {
        if (embyItems.isEmpty()) return false
        Files.createDirectories(targetDirectory)
        directory = targetDirectory

        val client = AppSession.createClient()
        val useStaging = DownloadStaging.shouldStage(targetDirectory)
        if (useStaging) {
            Files.createDirectories(DownloadStaging.stagingDirectory())
        }

        val created = embyItems.map { item ->
            TransferItem(embyItem = item, directory = targetDirectory).also { task ->
                val fileName = client.suggestFileName(item)
                task.markQueuedFileName(fileName)
            }
        }
        Platform.runLater {
            items.addAll(created)
            refreshSummary()
        }
        // 列表可能尚在 FX 线程更新；调度放在稍后
        Platform.runLater { kickScheduler() }
        return true
    }

    /** 兼容旧调用：开始一批任务。允许在已有任务时追加。 */
    fun start(embyItems: List<BaseItemDto>, targetDirectory: Path): Boolean =
        enqueue(embyItems, targetDirectory)

    fun pause(item: TransferItem) {
        if (item.state != TransferItemState.RUNNING) return
        item.control.pause()
        setState(item, TransferItemState.PAUSED)
        item.speedProperty.set(0)
        refreshSummary()
    }

    fun resume(item: TransferItem) {
        when (item.state) {
            TransferItemState.PAUSED -> {
                item.control.resume()
                setState(item, TransferItemState.RUNNING)
                refreshSummary()
            }
            TransferItemState.FAILED, TransferItemState.CANCELLED, TransferItemState.QUEUED -> {
                setState(item, TransferItemState.QUEUED)
                item.errorProperty.set("")
                kickScheduler()
            }
            else -> Unit
        }
    }

    fun cancel(item: TransferItem) {
        when (item.state) {
            TransferItemState.QUEUED -> {
                setState(item, TransferItemState.CANCELLED)
                refreshSummary()
                kickScheduler()
            }
            TransferItemState.RUNNING, TransferItemState.PAUSED -> {
                item.control.cancel()
                setState(item, TransferItemState.CANCELLED)
                item.speedProperty.set(0)
                refreshSummary()
            }
            else -> Unit
        }
    }

    fun retry(item: TransferItem) {
        if (item.state != TransferItemState.FAILED && item.state != TransferItemState.CANCELLED) return
        item.errorProperty.set("")
        setState(item, TransferItemState.QUEUED)
        kickScheduler()
    }

    fun removeFinished() {
        Platform.runLater {
            items.removeIf {
                it.state == TransferItemState.COMPLETED ||
                    it.state == TransferItemState.CANCELLED
            }
            refreshSummary()
        }
    }

    fun removeItem(item: TransferItem, deleteFile: Boolean) {
        if (item.isActive) {
            item.control.cancel()
        }
        when {
            item.state == TransferItemState.COMPLETED && deleteFile -> cleanupPartial(item, delete = true)
            item.state != TransferItemState.COMPLETED && item.state != TransferItemState.QUEUED ->
                cleanupPartial(item, delete = true)
        }
        Platform.runLater {
            items.remove(item)
            refreshSummary()
            kickScheduler()
        }
    }

    fun removeItems(targets: Collection<TransferItem>, deleteFiles: Boolean) {
        val snapshot = targets.toList()
        snapshot.forEach { item ->
            if (item.isActive) item.control.cancel()
            when {
                item.state == TransferItemState.COMPLETED && deleteFiles -> cleanupPartial(item, delete = true)
                item.state != TransferItemState.COMPLETED && item.state != TransferItemState.QUEUED ->
                    cleanupPartial(item, delete = true)
            }
        }
        Platform.runLater {
            items.removeAll(snapshot)
            refreshSummary()
            kickScheduler()
        }
    }

    fun removeCompleted(deleteFiles: Boolean): Int {
        val completed = items.filter { it.state == TransferItemState.COMPLETED }
        if (completed.isEmpty()) return 0
        if (deleteFiles) {
            completed.forEach { cleanupPartial(it, delete = true) }
        }
        Platform.runLater {
            items.removeAll(completed)
            refreshSummary()
        }
        return completed.size
    }

    fun pauseItems(targets: Collection<TransferItem>) {
        targets.forEach { pause(it) }
    }

    fun resumeItems(targets: Collection<TransferItem>) {
        targets.forEach { resume(it) }
    }

    fun cancelItems(targets: Collection<TransferItem>) {
        targets.forEach { cancel(it) }
    }

    private fun kickScheduler() {
        synchronized(scheduleLock) {
            val runningCount = items.count { it.state == TransferItemState.RUNNING || it.state == TransferItemState.PAUSED }
            var slots = (MAX_CONCURRENT - runningCount).coerceAtLeast(0)
            if (slots <= 0) return
            for (item in items.toList()) {
                if (slots <= 0) break
                if (item.state != TransferItemState.QUEUED) continue
                if (item.workerRunning) continue
                launchItem(item)
                slots--
            }
        }
        refreshSummary()
    }

    private fun launchItem(item: TransferItem) {
        item.workerRunning = true
        item.control = DownloadControl()
        setState(item, TransferItemState.RUNNING)
        executor.execute {
            try {
                downloadWithRetry(item)
                if (item.control.isCancelled || item.state == TransferItemState.CANCELLED) {
                    cleanupPartial(item, delete = true)
                    setState(item, TransferItemState.CANCELLED)
                } else {
                    setState(item, TransferItemState.COMPLETED)
                    item.speedProperty.set(0)
                }
            } catch (ex: DownloadCancelledException) {
                cleanupPartial(item, delete = true)
                setState(item, TransferItemState.CANCELLED)
                item.speedProperty.set(0)
            } catch (ex: Exception) {
                if (item.control.isCancelled) {
                    cleanupPartial(item, delete = true)
                    setState(item, TransferItemState.CANCELLED)
                } else {
                    val message = when (ex) {
                        is EmbyApiException -> ex.message
                        else -> ex.message ?: "未知错误"
                    }
                    item.errorProperty.set(message ?: "下载失败")
                    setState(item, TransferItemState.FAILED)
                    item.speedProperty.set(0)
                }
            } finally {
                item.workerRunning = false
                Platform.runLater {
                    refreshSummary()
                    kickScheduler()
                }
            }
        }
    }

    private fun downloadWithRetry(item: TransferItem) {
        var attempt = 0
        var lastError: Exception? = null
        while (attempt <= MAX_AUTO_RETRIES) {
            if (item.control.isCancelled) throw DownloadCancelledException()
            try {
                runDownload(item)
                return
            } catch (ex: DownloadCancelledException) {
                throw ex
            } catch (ex: Exception) {
                lastError = ex
                attempt++
                if (attempt > MAX_AUTO_RETRIES || item.control.isCancelled) break
                item.errorProperty.set("连接中断，正在重试（$attempt/$MAX_AUTO_RETRIES）…")
                Thread.sleep((1_000L * attempt).coerceAtMost(8_000L))
                item.errorProperty.set("")
            }
        }
        throw lastError ?: EmbyApiException("下载失败")
    }

    private fun runDownload(item: TransferItem) {
        val client = AppSession.createClient()
        val fileName = item.fileNameProperty.get().ifBlank { client.suggestFileName(item.embyItem) }
        Platform.runLater { item.fileNameProperty.set(fileName) }

        val finalTarget = item.directory.resolve(fileName)
        val useStaging = DownloadStaging.shouldStage(item.directory)
        val writeTarget = if (useStaging) DownloadStaging.stagingFileFor(finalTarget) else finalTarget
        Files.createDirectories(writeTarget.parent)

        var lastBytes = item.downloadedProperty.get()
        var lastTimeNs = System.nanoTime()

        fun emit(downloaded: Long, total: Long?, phase: DownloadPhase) {
            val now = System.nanoTime()
            val elapsed = now - lastTimeNs
            if (elapsed >= 200_000_000L) {
                val speed = ((downloaded - lastBytes) * 1_000_000_000L) / elapsed
                lastBytes = downloaded
                lastTimeNs = now
                Platform.runLater {
                    item.downloadedProperty.set(downloaded)
                    if (total != null && total > 0) item.totalProperty.set(total)
                    item.speedProperty.set(speed.coerceAtLeast(0))
                    item.phaseProperty.set(phase)
                }
            } else {
                Platform.runLater {
                    item.downloadedProperty.set(downloaded)
                    if (total != null && total > 0) item.totalProperty.set(total)
                    item.phaseProperty.set(phase)
                }
            }
        }

        client.downloadItem(
            item = item.embyItem,
            targetFile = writeTarget,
            control = item.control,
            onProgress = { downloaded, total ->
                emit(downloaded, total, DownloadPhase.DOWNLOADING)
            }
        )

        if (useStaging) {
            lastBytes = 0
            lastTimeNs = System.nanoTime()
            Platform.runLater { item.phaseProperty.set(DownloadPhase.SAVING_TO_DESTINATION) }
            DownloadStaging.moveToFinal(writeTarget, finalTarget, item.control) { moved, total ->
                emit(moved, total, DownloadPhase.SAVING_TO_DESTINATION)
            }
        }

        val finalSize = runCatching { Files.size(finalTarget) }.getOrDefault(item.downloadedProperty.get())
        Platform.runLater {
            item.downloadedProperty.set(finalSize)
            if (item.totalProperty.get() <= 0) item.totalProperty.set(finalSize)
            item.speedProperty.set(0)
            item.phaseProperty.set(DownloadPhase.DOWNLOADING)
            item.errorProperty.set("")
        }
    }

    private fun cleanupPartial(item: TransferItem, delete: Boolean) {
        if (!delete) return
        val fileName = item.fileNameProperty.get()
        if (fileName.isBlank()) return
        val finalTarget = item.directory.resolve(fileName)
        val staging = DownloadStaging.stagingFileFor(finalTarget)
        runCatching { Files.deleteIfExists(staging) }
        runCatching { Files.deleteIfExists(finalTarget) }
        Platform.runLater { item.downloadedProperty.set(0) }
    }

    private fun setState(item: TransferItem, state: TransferItemState) {
        if (Platform.isFxApplicationThread()) {
            item.stateProperty.set(state)
        } else {
            Platform.runLater { item.stateProperty.set(state) }
        }
    }

    private fun refreshSummary() {
        val total = items.size
        val done = items.count { it.state == TransferItemState.COMPLETED }
        val failed = items.count { it.state == TransferItemState.FAILED }
        val active = items.count { it.isActive }
        val queued = items.count { it.state == TransferItemState.QUEUED }
        summaryProperty.set(
            when {
                total == 0 -> "暂无传输任务"
                active > 0 -> "下载中 $active · 排队 $queued · 完成 $done/${total}"
                queued > 0 -> "排队 $queued · 完成 $done/${total}"
                failed > 0 -> "有 $failed 项失败 · 完成 $done/${total}"
                done == total -> "全部完成（$done）"
                else -> "完成 $done/${total}"
            }
        )
    }
}
