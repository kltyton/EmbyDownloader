package com.dud.embydownload.controller

import com.dud.embydownload.AppSession
import com.dud.embydownload.emby.EmbyApiException
import com.dud.embydownload.emby.EmbyImages
import com.dud.embydownload.emby.model.BaseItemDto
import com.dud.embydownload.emby.model.SearchMediaType
import com.dud.embydownload.ui.AppShell
import javafx.collections.FXCollections
import javafx.concurrent.Task
import javafx.fxml.FXML
import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.control.ListView
import javafx.scene.control.ProgressIndicator
import javafx.scene.control.TextField
import javafx.scene.image.ImageView
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.scene.shape.Rectangle

class HomeController {
    @FXML
    private lateinit var userInfoLabel: Label

    @FXML
    private lateinit var serverInfoLabel: Label

    @FXML
    private lateinit var searchField: TextField

    @FXML
    private lateinit var mediaTypeCombo: ComboBox<SearchMediaType>

    @FXML
    private lateinit var searchButton: Button

    @FXML
    private lateinit var searchLoading: ProgressIndicator

    @FXML
    private lateinit var statusLabel: Label

    @FXML
    private lateinit var resultList: ListView<BaseItemDto>

    @FXML
    private fun initialize() {
        val user = AppSession.user
        userInfoLabel.text = user?.Name ?: "未知用户"
        serverInfoLabel.text = AppSession.serverUrl

        mediaTypeCombo.items = FXCollections.observableArrayList(*SearchMediaType.entries.toTypedArray())
        mediaTypeCombo.selectionModel.select(SearchMediaType.MOVIE)
        mediaTypeCombo.setCellFactory {
            object : ListCell<SearchMediaType>() {
                override fun updateItem(item: SearchMediaType?, empty: Boolean) {
                    super.updateItem(item, empty)
                    text = if (empty || item == null) null else item.label
                }
            }
        }
        mediaTypeCombo.buttonCell = object : ListCell<SearchMediaType>() {
            override fun updateItem(item: SearchMediaType?, empty: Boolean) {
                super.updateItem(item, empty)
                text = if (empty || item == null) null else item.label
            }
        }

        resultList.setCellFactory {
            object : ListCell<BaseItemDto>() {
                private val coverView = ImageView().apply {
                    fitWidth = 48.0
                    fitHeight = 72.0
                    isPreserveRatio = true
                    isSmooth = true
                }
                private val coverClip = Rectangle(48.0, 72.0).apply {
                    arcWidth = 8.0
                    arcHeight = 8.0
                }
                private val coverHost = StackPane(coverView).apply {
                    prefWidth = 48.0
                    prefHeight = 72.0
                    minWidth = 48.0
                    minHeight = 72.0
                    maxWidth = 48.0
                    maxHeight = 72.0
                    styleClass += "cover-placeholder"
                    clip = coverClip
                }
                private val title = Label().apply { styleClass += "item-title" }
                private val subtitle = Label().apply { styleClass += "item-subtitle" }
                private val textBox = VBox(4.0, title, subtitle).apply {
                    alignment = Pos.CENTER_LEFT
                    HBox.setHgrow(this, Priority.ALWAYS)
                }
                private val row = HBox(14.0, coverHost, textBox).apply {
                    alignment = Pos.CENTER_LEFT
                }

                override fun updateItem(item: BaseItemDto?, empty: Boolean) {
                    super.updateItem(item, empty)
                    if (empty || item == null) {
                        graphic = null
                        coverView.image = null
                        return
                    }
                    title.text = item.displayTitle()
                    subtitle.text = item.displaySubtitle()
                    coverView.image = EmbyImages.primaryCoverImage(item, maxHeight = 144)
                    graphic = row
                }
            }
        }

        resultList.setOnMouseClicked { event ->
            if (event.clickCount == 1) {
                val selected = resultList.selectionModel.selectedItem ?: return@setOnMouseClicked
                openEpisodeList(selected)
            }
        }

        restoreSearchState()
    }

    @FXML
    private fun onSearch() {
        val term = searchField.text.trim()
        if (term.isBlank()) {
            statusLabel.text = "请输入搜索关键词"
            return
        }

        val mediaType = mediaTypeCombo.selectionModel.selectedItem ?: SearchMediaType.MOVIE
        setSearching(true)
        statusLabel.text = "正在搜索「$term」..."

        val task = object : Task<List<BaseItemDto>>() {
            override fun call(): List<BaseItemDto> {
                return AppSession.createClient().search(term, mediaType)
            }
        }

        task.setOnSucceeded {
            setSearching(false)
            val items = task.value
            resultList.items = FXCollections.observableArrayList(items)
            statusLabel.text = if (items.isEmpty()) {
                "未找到相关结果"
            } else {
                "共找到 ${items.size} 条结果，点击进入列表并多选下载"
            }
            persistSearchState()
        }

        task.setOnFailed {
            setSearching(false)
            val error = task.exception
            statusLabel.text = when (error) {
                is EmbyApiException -> error.message
                else -> "搜索失败：${error?.message ?: "未知错误"}"
            }
            persistSearchState()
        }

        Thread(task, "emby-search").apply { isDaemon = true }.start()
    }

    @FXML
    private fun onOpenTransfer() {
        persistSearchState()
        TransferController.openTransferPage()
    }

    @FXML
    private fun onOpenSettings() {
        persistSearchState()
        AppShell.navigate<SettingsController>("settings-view.fxml", "Emby Download · 设置") {
            it.setReturnTarget(SettingsController.ReturnTarget.HOME)
        }
    }

    @FXML
    private fun onLogout() {
        clearSearchState()
        AppSession.logout()
        AppShell.navigateReplace<LoginController>("login-view.fxml", "Emby Download")
    }

    private fun openEpisodeList(item: BaseItemDto) {
        persistSearchState()
        AppShell.navigate<EpisodeListController>(
            "episode-list-view.fxml",
            "Emby Download · ${item.displayTitle()}"
        ) { controller ->
            controller.setParentItem(item)
        }
    }

    private fun setSearching(searching: Boolean) {
        searchButton.isDisable = searching
        searchField.isDisable = searching
        mediaTypeCombo.isDisable = searching
        searchLoading.isVisible = searching
        searchLoading.isManaged = searching
    }

    private fun persistSearchState() {
        cachedState = SearchState(
            query = searchField.text.orEmpty(),
            mediaType = mediaTypeCombo.selectionModel.selectedItem ?: SearchMediaType.MOVIE,
            status = statusLabel.text.orEmpty(),
            results = resultList.items?.toList().orEmpty()
        )
    }

    private fun restoreSearchState() {
        val state = cachedState ?: return
        searchField.text = state.query
        mediaTypeCombo.selectionModel.select(state.mediaType)
        statusLabel.text = state.status
        resultList.items = FXCollections.observableArrayList(state.results)
    }

    companion object {
        private data class SearchState(
            val query: String,
            val mediaType: SearchMediaType,
            val status: String,
            val results: List<BaseItemDto>
        )

        @Volatile
        private var cachedState: SearchState? = null

        fun clearSearchState() {
            cachedState = null
        }
    }
}
