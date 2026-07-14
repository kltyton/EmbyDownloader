package com.dud.embydownload.controller

import com.dud.embydownload.emby.model.BaseItemDto
import com.dud.embydownload.emby.model.DownloadPhase
import com.dud.embydownload.proxy.DownloadHttpClients
import com.dud.embydownload.transfer.TransferItem
import com.dud.embydownload.transfer.TransferItemState
import com.dud.embydownload.transfer.TransferSession
import com.dud.embydownload.ui.AppShell
import com.dud.embydownload.ui.MaterialDialog
import javafx.beans.binding.Bindings
import javafx.collections.FXCollections
import javafx.collections.ObservableSet
import javafx.collections.SetChangeListener
import javafx.fxml.FXML
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.CheckBox
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.control.ListView
import javafx.scene.control.ProgressBar
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import java.nio.file.Path
import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow

class TransferController {
    @FXML
    private lateinit var backButton: Button

    @FXML
    private lateinit var subtitleLabel: Label

    @FXML
    private lateinit var pathLabel: Label

    @FXML
    private lateinit var nodeLabel: Label

    @FXML
    private lateinit var taskList: ListView<TransferItem>

    @FXML
    private lateinit var editModeButton: Button

    @FXML
    private lateinit var deleteCompletedButton: Button

    @FXML
    private lateinit var batchBar: HBox

    @FXML
    private lateinit var selectAllCheck: CheckBox

    @FXML
    private lateinit var selectionHintLabel: Label

    private var editMode = false
    private val selectedItems: ObservableSet<TransferItem> = FXCollections.observableSet()

    @FXML
    private fun initialize() {
        pathLabel.text = "保存目录：${TransferSession.directory}"
        nodeLabel.text = DownloadHttpClients.activeNodeLabel()?.let { "下载节点：$it" } ?: "下载节点：直连"
        subtitleLabel.textProperty().bind(TransferSession.summaryProperty)

        TransferSession.directoryProperty.addListener { _, _, dir ->
            pathLabel.text = "保存目录：$dir"
        }

        selectedItems.addListener(SetChangeListener {
            selectionHintLabel.text = "已选 ${selectedItems.size} 项"
            val selectable = TransferSession.items.toList()
            selectAllCheck.isSelected = selectable.isNotEmpty() && selectedItems.containsAll(selectable)
        })

        taskList.items = TransferSession.items
        taskList.setCellFactory { TransferItemCell() }
    }

    @FXML
    private fun onBack() {
        if (editMode) {
            setEditMode(false)
            return
        }
        if (!AppShell.goBack()) {
            AppShell.navigateReplace<HomeController>("home-view.fxml", "Emby Download · 首页")
        }
    }

    @FXML
    private fun onToggleEditMode() {
        setEditMode(!editMode)
    }

    @FXML
    private fun onToggleSelectAll() {
        if (selectAllCheck.isSelected) {
            selectedItems.addAll(TransferSession.items)
        } else {
            selectedItems.clear()
        }
        taskList.refresh()
    }

    @FXML
    private fun onBatchPause() {
        TransferSession.pauseItems(selectedItems.filter { it.state == TransferItemState.RUNNING })
        taskList.refresh()
    }

    @FXML
    private fun onBatchResume() {
        TransferSession.resumeItems(
            selectedItems.filter {
                it.state == TransferItemState.PAUSED ||
                    it.state == TransferItemState.QUEUED ||
                    it.state == TransferItemState.FAILED ||
                    it.state == TransferItemState.CANCELLED
            }
        )
        taskList.refresh()
    }

    @FXML
    private fun onBatchCancel() {
        val targets = selectedItems.filter {
            it.state == TransferItemState.RUNNING ||
                it.state == TransferItemState.PAUSED ||
                it.state == TransferItemState.QUEUED
        }
        if (targets.isEmpty()) return
        val ok = MaterialDialog.confirm(
            AppShell.getStage(),
            "批量取消",
            "确定取消选中的 ${targets.size} 项下载吗？",
            "未完成的文件会被删除。"
        )
        if (!ok) return
        TransferSession.cancelItems(targets)
        selectedItems.removeAll(targets.toSet())
        taskList.refresh()
    }

    @FXML
    private fun onBatchDelete() {
        if (selectedItems.isEmpty()) return
        val result = MaterialDialog.confirmWithOption(
            owner = AppShell.getStage(),
            title = "批量删除",
            message = "确定从列表中删除选中的 ${selectedItems.size} 项吗？",
            optionLabel = "同时删除已下载的文件",
            detail = "进行中的任务会被取消。"
        )
        if (!result.confirmed) return
        val snapshot = selectedItems.toList()
        TransferSession.removeItems(snapshot, deleteFiles = result.optionChecked)
        selectedItems.clear()
        taskList.refresh()
    }

    @FXML
    private fun onDeleteCompleted() {
        val count = TransferSession.items.count { it.state == TransferItemState.COMPLETED }
        if (count == 0) {
            MaterialDialog.info(AppShell.getStage(), "删除已完成", "当前没有已完成的下载项目")
            return
        }
        val result = MaterialDialog.confirmWithOption(
            owner = AppShell.getStage(),
            title = "删除已完成",
            message = "确定删除全部 $count 个已完成项目吗？",
            optionLabel = "同时删除已下载的文件"
        )
        if (!result.confirmed) return
        TransferSession.removeCompleted(deleteFiles = result.optionChecked)
        selectedItems.removeIf { it.state == TransferItemState.COMPLETED }
        taskList.refresh()
    }

    private fun setEditMode(enabled: Boolean) {
        editMode = enabled
        editModeButton.text = if (enabled) "完成" else "编辑"
        batchBar.isVisible = enabled
        batchBar.isManaged = enabled
        deleteCompletedButton.isDisable = enabled
        if (!enabled) {
            selectedItems.clear()
            selectAllCheck.isSelected = false
        }
        taskList.refresh()
    }

    private fun askDeleteItem(item: TransferItem) {
        val result = MaterialDialog.confirmWithOption(
            owner = AppShell.getStage(),
            title = "删除下载项目",
            message = "确定删除「${item.displayName}」吗？",
            optionLabel = "同时删除已下载的文件",
            detail = if (item.state == TransferItemState.COMPLETED) {
                "取消勾选时仅从列表移除，保留本地文件。"
            } else {
                "未完成项目的临时文件会一并清理。"
            }
        )
        if (!result.confirmed) return
        TransferSession.removeItem(item, deleteFile = result.optionChecked)
        selectedItems.remove(item)
        taskList.refresh()
    }

    private inner class TransferItemCell : ListCell<TransferItem>() {
        private val selectCheck = CheckBox()
        private val title = Label().apply {
            styleClass += "item-title"
            maxWidth = Double.MAX_VALUE
        }
        private val status = Label().apply { styleClass += "meta-label" }
        private val sizeLabel = Label().apply { styleClass += "meta-label" }
        private val speedLabel = Label().apply { styleClass += "meta-label" }
        private val progressBar = ProgressBar(0.0).apply {
            maxWidth = Double.MAX_VALUE
            prefHeight = 6.0
            styleClass += "transfer-progress"
        }
        private val pauseButton = Button("暂停").apply { styleClass += "compact-button" }
        private val cancelButton = Button("取消").apply { styleClass += "compact-danger-button" }
        private val retryButton = Button("重试").apply { styleClass += "compact-button" }
        private val deleteButton = Button("删除").apply { styleClass += "compact-danger-button" }

        private val actions = HBox(6.0, pauseButton, cancelButton, retryButton, deleteButton).apply {
            alignment = Pos.CENTER_RIGHT
        }
        private val titleRow = HBox(8.0, title, actions).apply {
            alignment = Pos.CENTER_LEFT
            HBox.setHgrow(title, Priority.ALWAYS)
        }
        private val metaRow = HBox(12.0, status, sizeLabel, speedLabel).apply {
            alignment = Pos.CENTER_LEFT
        }
        private val content = VBox(4.0, titleRow, progressBar, metaRow).apply {
            HBox.setHgrow(this, Priority.ALWAYS)
        }
        private val root = HBox(10.0, selectCheck, content).apply {
            alignment = Pos.CENTER_LEFT
            styleClass += "transfer-item-card"
            padding = Insets(8.0, 10.0, 8.0, 10.0)
        }

        private var boundItem: TransferItem? = null
        private val stateListener = javafx.beans.value.ChangeListener<TransferItemState> { _, _, _ ->
            refreshButtons()
        }

        init {
            selectCheck.setOnAction {
                val current = boundItem ?: return@setOnAction
                if (selectCheck.isSelected) selectedItems.add(current) else selectedItems.remove(current)
            }
            pauseButton.setOnAction {
                val current = boundItem ?: return@setOnAction
                when (current.state) {
                    TransferItemState.RUNNING -> TransferSession.pause(current)
                    TransferItemState.PAUSED,
                    TransferItemState.FAILED,
                    TransferItemState.CANCELLED,
                    TransferItemState.QUEUED -> TransferSession.resume(current)
                    else -> Unit
                }
            }
            cancelButton.setOnAction {
                val current = boundItem ?: return@setOnAction
                val ok = MaterialDialog.confirm(
                    AppShell.getStage(),
                    "取消下载",
                    "确定取消「${current.displayName}」吗？",
                    "未完成的文件会被删除。"
                )
                if (ok) TransferSession.cancel(current)
            }
            retryButton.setOnAction {
                val current = boundItem ?: return@setOnAction
                TransferSession.retry(current)
            }
            deleteButton.setOnAction {
                val current = boundItem ?: return@setOnAction
                askDeleteItem(current)
            }
        }

        override fun updateItem(item: TransferItem?, empty: Boolean) {
            super.updateItem(item, empty)
            unbindCurrent()
            if (empty || item == null) {
                graphic = null
                text = null
                return
            }
            boundItem = item
            title.text = item.displayName
            selectCheck.isVisible = editMode
            selectCheck.isManaged = editMode
            selectCheck.isSelected = selectedItems.contains(item)
            actions.isVisible = !editMode
            actions.isManaged = !editMode
            bindProgress(item)
            bindStatus(item)
            item.stateProperty.addListener(stateListener)
            refreshButtons()
            graphic = root
            text = null
        }

        private fun unbindCurrent() {
            progressBar.progressProperty().unbind()
            sizeLabel.textProperty().unbind()
            speedLabel.textProperty().unbind()
            status.textProperty().unbind()
            boundItem?.stateProperty?.removeListener(stateListener)
            boundItem = null
        }

        private fun bindProgress(item: TransferItem) {
            progressBar.progressProperty().bind(
                Bindings.createDoubleBinding(
                    {
                        val total = item.totalProperty.get()
                        val downloaded = item.downloadedProperty.get()
                        if (total <= 0L) {
                            if (item.state == TransferItemState.RUNNING) ProgressBar.INDETERMINATE_PROGRESS else 0.0
                        } else {
                            (downloaded.toDouble() / total.toDouble()).coerceIn(0.0, 1.0)
                        }
                    },
                    item.downloadedProperty,
                    item.totalProperty,
                    item.stateProperty
                )
            )
            sizeLabel.textProperty().bind(
                Bindings.createStringBinding(
                    {
                        val total = item.totalProperty.get()
                        val totalText = if (total > 0) formatBytes(total) else "--"
                        "${formatBytes(item.downloadedProperty.get())} / $totalText"
                    },
                    item.downloadedProperty,
                    item.totalProperty
                )
            )
            speedLabel.textProperty().bind(
                Bindings.createStringBinding(
                    {
                        when (item.state) {
                            TransferItemState.RUNNING -> "${formatBytes(item.speedProperty.get())}/s"
                            else -> ""
                        }
                    },
                    item.speedProperty,
                    item.stateProperty
                )
            )
        }

        private fun bindStatus(item: TransferItem) {
            status.textProperty().bind(
                Bindings.createStringBinding(
                    {
                        val err = item.errorProperty.get()
                        when (item.state) {
                            TransferItemState.QUEUED -> "排队中"
                            TransferItemState.RUNNING -> when (item.phaseProperty.get()) {
                                DownloadPhase.SAVING_TO_DESTINATION -> "保存中…"
                                else -> if (err.isNullOrBlank()) "下载中" else err
                            }
                            TransferItemState.PAUSED -> "已暂停"
                            TransferItemState.COMPLETED -> "已完成"
                            TransferItemState.FAILED -> "失败：${err.ifBlank { "未知错误" }}"
                            TransferItemState.CANCELLED -> "已取消"
                        }
                    },
                    item.stateProperty,
                    item.phaseProperty,
                    item.errorProperty
                )
            )
        }

        private fun refreshButtons() {
            val item = boundItem ?: return
            if (editMode) {
                pauseButton.isVisible = false
                pauseButton.isManaged = false
                cancelButton.isVisible = false
                cancelButton.isManaged = false
                retryButton.isVisible = false
                retryButton.isManaged = false
                deleteButton.isVisible = false
                deleteButton.isManaged = false
                return
            }
            when (item.state) {
                TransferItemState.RUNNING -> {
                    pauseButton.text = "暂停"
                    showAction(pause = true, cancel = true)
                }
                TransferItemState.PAUSED -> {
                    pauseButton.text = "继续"
                    showAction(pause = true, cancel = true)
                }
                TransferItemState.QUEUED -> {
                    pauseButton.text = "开始"
                    showAction(pause = true, cancel = true)
                }
                TransferItemState.FAILED -> showAction(retry = true)
                TransferItemState.CANCELLED -> {
                    retryButton.text = "重新下载"
                    showAction(retry = true, delete = true)
                }
                TransferItemState.COMPLETED -> showAction(delete = true)
            }
        }

        private fun showAction(
            pause: Boolean = false,
            cancel: Boolean = false,
            retry: Boolean = false,
            delete: Boolean = false
        ) {
            pauseButton.isVisible = pause
            pauseButton.isManaged = pause
            cancelButton.isVisible = cancel
            cancelButton.isManaged = cancel
            retryButton.isVisible = retry
            retryButton.isManaged = retry
            if (retry) retryButton.text = if (boundItem?.state == TransferItemState.CANCELLED) "重新下载" else "重试"
            deleteButton.isVisible = delete
            deleteButton.isManaged = delete
        }
    }

    companion object {
        fun openTransferPage() {
            AppShell.navigate<TransferController>("transfer-view.fxml", "Emby Download · 传输")
        }

        fun startAndOpen(items: List<BaseItemDto>, directory: Path) {
            TransferSession.enqueue(items, directory)
            openTransferPage()
        }

        fun formatBytes(bytes: Long): String {
            if (bytes < 1024) return "$bytes B"
            val exp = (ln(bytes.toDouble()) / ln(1024.0)).toInt().coerceAtMost(4)
            val unit = arrayOf("KB", "MB", "GB", "TB")[exp - 1]
            val value = bytes / 1024.0.pow(exp.toDouble())
            return String.format(Locale.US, "%.2f %s", value, unit)
        }
    }
}
