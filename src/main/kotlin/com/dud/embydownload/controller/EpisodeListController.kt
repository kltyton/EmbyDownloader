package com.dud.embydownload.controller

import com.dud.embydownload.AppSession
import com.dud.embydownload.emby.EmbyApiException
import com.dud.embydownload.emby.model.BaseItemDto
import com.dud.embydownload.emby.model.SeasonOption
import com.dud.embydownload.settings.AppSettings
import com.dud.embydownload.transfer.TransferSession
import com.dud.embydownload.ui.AppShell
import com.dud.embydownload.ui.MaterialDialog
import javafx.collections.FXCollections
import javafx.collections.ObservableSet
import javafx.concurrent.Task
import javafx.fxml.FXML
import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.CheckBox
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.control.ListView
import javafx.scene.control.ProgressIndicator
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import java.nio.file.Files
import java.nio.file.Path

class EpisodeListController {
    @FXML
    private lateinit var titleLabel: Label

    @FXML
    private lateinit var subtitleLabel: Label

    @FXML
    private lateinit var statusLabel: Label

    @FXML
    private lateinit var selectionLabel: Label

    @FXML
    private lateinit var selectAllCheck: CheckBox

    @FXML
    private lateinit var selectRangeButton: Button

    @FXML
    private lateinit var downloadButton: Button

    @FXML
    private lateinit var loadingIndicator: ProgressIndicator

    @FXML
    private lateinit var episodeList: ListView<BaseItemDto>

    @FXML
    private lateinit var seasonRow: HBox

    @FXML
    private lateinit var seasonCombo: ComboBox<SeasonOption>

    private lateinit var parentItem: BaseItemDto
    private var seriesId: String? = null
    private val selectedItems: ObservableSet<BaseItemDto> = FXCollections.observableSet()
    private var updatingSelectAll = false
    private var suppressingSeasonChange = false

    fun setParentItem(item: BaseItemDto) {
        parentItem = item
        titleLabel.text = item.displayTitle()
        subtitleLabel.text = item.displaySubtitle().ifBlank { "选择要下载的内容" }

        if (item.Type == "Series" && !item.Id.isNullOrBlank()) {
            seriesId = item.Id
            showSeasonSelector(true)
            loadSeasonsAndEpisodes()
        } else {
            seriesId = null
            showSeasonSelector(false)
            loadItems()
        }
    }

    @FXML
    private fun initialize() {
        selectedItems.addListener(javafx.collections.SetChangeListener {
            refreshSelectionUi()
            episodeList.refresh()
        })

        seasonCombo.setCellFactory {
            object : ListCell<SeasonOption>() {
                override fun updateItem(item: SeasonOption?, empty: Boolean) {
                    super.updateItem(item, empty)
                    text = if (empty || item == null) null else item.label
                }
            }
        }
        seasonCombo.buttonCell = object : ListCell<SeasonOption>() {
            override fun updateItem(item: SeasonOption?, empty: Boolean) {
                super.updateItem(item, empty)
                text = if (empty || item == null) null else item.label
            }
        }

        episodeList.setCellFactory {
            object : ListCell<BaseItemDto>() {
                private val checkBox = CheckBox()
                private val title = Label().apply { styleClass += "item-title" }
                private val subtitle = Label().apply { styleClass += "item-subtitle" }
                private val textBox = VBox(2.0, title, subtitle).apply {
                    alignment = Pos.CENTER_LEFT
                    HBox.setHgrow(this, Priority.ALWAYS)
                }
                private val root = HBox(12.0, checkBox, textBox).apply {
                    alignment = Pos.CENTER_LEFT
                }

                init {
                    checkBox.setOnAction {
                        val item = item ?: return@setOnAction
                        if (checkBox.isSelected) {
                            selectedItems.add(item)
                        } else {
                            selectedItems.remove(item)
                        }
                    }
                }

                override fun updateItem(item: BaseItemDto?, empty: Boolean) {
                    super.updateItem(item, empty)
                    if (empty || item == null) {
                        graphic = null
                        return
                    }
                    title.text = item.displayTitle()
                    subtitle.text = item.displaySubtitle()
                    checkBox.isSelected = selectedItems.contains(item)
                    graphic = root
                }
            }
        }
    }

    @FXML
    private fun onSeasonChanged() {
        if (suppressingSeasonChange) return
        loadEpisodesForSelectedSeason()
    }

    @FXML
    private fun onToggleSelectAll() {
        if (updatingSelectAll) return
        val items = episodeList.items.orEmpty()
        if (selectAllCheck.isSelected) {
            selectedItems.addAll(items)
        } else {
            selectedItems.clear()
        }
        episodeList.refresh()
    }

    @FXML
    private fun onSelectRange() {
        val items = episodeList.items.orEmpty()
        if (items.isEmpty()) {
            MaterialDialog.info(AppShell.getStage(), "选择范围", "当前没有可勾选的内容")
            return
        }

        val numbered = items.mapIndexed { index, item ->
            episodeNumber(item, index) to item
        }
        val numbers = numbered.map { it.first }.distinct().sorted()
        val range = MaterialDialog.pickIntRange(
            owner = AppShell.getStage(),
            title = "选择范围勾选",
            values = numbers,
            startLabel = "开始集数",
            endLabel = "结束集数",
            message = "选择起止集数（包前包后），确定后勾选该范围内的项目"
        ) ?: return

        val matched = numbered
            .filter { (number, _) -> number in range }
            .map { it.second }
        selectedItems.addAll(matched)
        episodeList.refresh()
        statusLabel.text = "已按范围勾选第 ${range.first}–${range.last} 集（共 ${matched.size} 项）"
    }

    private fun episodeNumber(item: BaseItemDto, index: Int): Int =
        item.IndexNumber ?: (index + 1)

    @FXML
    private fun onBack() {
        navigateToHome()
    }

    @FXML
    private fun onOpenTransfer() {
        TransferController.openTransferPage()
    }

    @FXML
    private fun onDownloadSelected() {
        // 按列表从上到下顺序入队（selectedItems 是 Set，本身无序）
        val items = episodeList.items.orEmpty().filter { it in selectedItems }
        if (items.isEmpty()) {
            statusLabel.text = "请先勾选要下载的内容"
            return
        }

        val confirmed = MaterialDialog.confirm(
            owner = AppShell.getStage(),
            title = "确认下载",
            message = "是否下载选中的 ${items.size} 项？",
            detail = "保存目录：${AppSettings.downloadPath}"
        )
        if (!confirmed) return

        val directory = Path.of(AppSettings.downloadPath)
        try {
            Files.createDirectories(directory)
        } catch (ex: Exception) {
            MaterialDialog.error(
                AppShell.getStage(),
                "目录不可用",
                "无法创建下载目录，请先在设置中修改路径。",
                ex.message
            )
            return
        }

        if (TransferSession.isRunning) {
            val addMore = MaterialDialog.confirm(
                AppShell.getStage(),
                "追加到传输队列",
                "当前已有下载任务在进行，是否将选中的 ${items.size} 项加入队列？",
                "也可以先前往传输页查看进度。"
            )
            if (!addMore) {
                TransferController.openTransferPage()
                return
            }
        }

        TransferController.startAndOpen(items, directory)
    }

    private fun loadSeasonsAndEpisodes() {
        val sid = seriesId ?: return
        setBusy(true)
        statusLabel.text = "正在加载季列表..."
        selectedItems.clear()

        val task = object : Task<List<SeasonOption>>() {
            override fun call(): List<SeasonOption> {
                val seasons = AppSession.createClient().getSeasons(sid)
                return listOf(SeasonOption.all()) + seasons.map { SeasonOption.from(it) }
            }
        }

        task.setOnSucceeded {
            val options = task.value
            suppressingSeasonChange = true
            seasonCombo.items = FXCollections.observableArrayList(options)
            // 默认选中第一季（若有），否则“全部季”
            val defaultOption = options.firstOrNull { it.season?.IndexNumber == 1 }
                ?: options.firstOrNull { it.season != null }
                ?: options.first()
            seasonCombo.selectionModel.select(defaultOption)
            suppressingSeasonChange = false
            loadEpisodesForSelectedSeason()
        }

        task.setOnFailed {
            setBusy(false)
            val error = task.exception
            statusLabel.text = when (error) {
                is EmbyApiException -> error.message
                else -> "加载季列表失败：${error?.message ?: "未知错误"}"
            }
        }

        Thread(task, "emby-load-seasons").apply { isDaemon = true }.start()
    }

    private fun loadEpisodesForSelectedSeason() {
        val sid = seriesId ?: return
        val seasonId = seasonCombo.selectionModel.selectedItem?.season?.Id

        setBusy(true)
        statusLabel.text = "正在加载分集..."
        selectedItems.clear()

        val task = object : Task<List<BaseItemDto>>() {
            override fun call(): List<BaseItemDto> {
                return AppSession.createClient().getEpisodes(seriesId = sid, seasonId = seasonId)
            }
        }

        task.setOnSucceeded {
            setBusy(false)
            applyEpisodeItems(task.value)
        }

        task.setOnFailed {
            setBusy(false)
            val error = task.exception
            statusLabel.text = when (error) {
                is EmbyApiException -> error.message
                else -> "加载失败：${error?.message ?: "未知错误"}"
            }
        }

        Thread(task, "emby-load-episodes").apply { isDaemon = true }.start()
    }

    private fun loadItems() {
        setBusy(true)
        statusLabel.text = "正在加载列表..."
        selectedItems.clear()

        val task = object : Task<List<BaseItemDto>>() {
            override fun call(): List<BaseItemDto> {
                return AppSession.createClient().getDownloadableItems(parentItem)
            }
        }

        task.setOnSucceeded {
            setBusy(false)
            applyEpisodeItems(task.value)
        }

        task.setOnFailed {
            setBusy(false)
            val error = task.exception
            statusLabel.text = when (error) {
                is EmbyApiException -> error.message
                else -> "加载失败：${error?.message ?: "未知错误"}"
            }
        }

        Thread(task, "emby-load-episodes").apply { isDaemon = true }.start()
    }

    private fun applyEpisodeItems(items: List<BaseItemDto>) {
        episodeList.items = FXCollections.observableArrayList(items)
        statusLabel.text = if (items.isEmpty()) {
            "没有可下载的内容"
        } else {
            "共 ${items.size} 项，勾选后可批量下载"
        }
        refreshSelectionUi()
    }

    private fun showSeasonSelector(show: Boolean) {
        seasonRow.isVisible = show
        seasonRow.isManaged = show
    }

    private fun refreshSelectionUi() {
        val total = episodeList.items?.size ?: 0
        val selected = selectedItems.size
        selectionLabel.text = "已选 $selected 项"
        downloadButton.isDisable = selected == 0 || loadingIndicator.isVisible

        updatingSelectAll = true
        selectAllCheck.isSelected = total > 0 && selected == total
        selectAllCheck.isIndeterminate = selected > 0 && selected < total
        updatingSelectAll = false
    }

    private fun setBusy(busy: Boolean) {
        loadingIndicator.isVisible = busy
        loadingIndicator.isManaged = busy
        downloadButton.isDisable = busy || selectedItems.isEmpty()
        selectAllCheck.isDisable = busy
        selectRangeButton.isDisable = busy
        episodeList.isDisable = busy
        seasonCombo.isDisable = busy
    }

    private fun navigateToHome() {
        if (!AppShell.goBack()) {
            AppShell.navigateReplace<HomeController>("home-view.fxml", "Emby Download · 首页")
        }
    }
}
