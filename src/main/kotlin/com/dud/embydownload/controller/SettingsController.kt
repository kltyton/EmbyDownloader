package com.dud.embydownload.controller

import com.dud.embydownload.settings.AppSettings
import com.dud.embydownload.settings.SettingsData
import com.dud.embydownload.settings.MediaSaveMode
import com.dud.embydownload.transfer.DownloadStaging
import com.dud.embydownload.transfer.TransferSession
import com.dud.embydownload.ui.AppShell
import com.dud.embydownload.ui.MaterialDialog
import javafx.collections.FXCollections
import javafx.fxml.FXML
import javafx.scene.control.Button
import javafx.scene.control.CheckBox
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.control.TextField
import javafx.stage.DirectoryChooser
import java.nio.file.Files
import java.nio.file.Path

class SettingsController {
    enum class ReturnTarget { LOGIN, HOME }

    @FXML
    private lateinit var downloadPathField: TextField

    @FXML
    private lateinit var stagingDownloadCheck: CheckBox

    @FXML
    private lateinit var stagingPathField: TextField

    @FXML
    private lateinit var stagingHintLabel: Label

    @FXML
    private lateinit var concurrentCombo: ComboBox<Int>

    @FXML
    private lateinit var saveModeCombo: ComboBox<MediaSaveMode>

    @FXML
    private lateinit var saveModeHint: Label

    @FXML
    private lateinit var episodeSeriesNameCheck: CheckBox

    @FXML
    private lateinit var darkModeCheck: CheckBox

    @FXML
    private lateinit var proxyHintLabel: Label

    @FXML
    private lateinit var accountsHintLabel: Label

    @FXML
    private lateinit var clearAccountsButton: Button

    private var returnTarget: ReturnTarget = ReturnTarget.HOME
    private var suppressingConcurrentChange = false
    private var suppressingSaveModeChange = false

    fun setReturnTarget(target: ReturnTarget) {
        returnTarget = target
    }

    @FXML
    private fun initialize() {
        downloadPathField.text = AppSettings.downloadPath
        stagingDownloadCheck.isSelected = AppSettings.stagingDownloadEnabled
        stagingPathField.text = AppSettings.stagingPath
        updateStagingControls()
        setupConcurrentCombo()
        suppressingSaveModeChange = true
        saveModeCombo.items = FXCollections.observableArrayList(MediaSaveMode.entries)
        saveModeCombo.value = AppSettings.mediaSaveMode
        suppressingSaveModeChange = false
        refreshSaveModeHint()
        episodeSeriesNameCheck.isSelected = AppSettings.episodeFileNameIncludeSeries
        darkModeCheck.isSelected = AppSettings.darkMode
        refreshAccountsHint()
        refreshProxyHint()
    }

    private fun setupConcurrentCombo() {
        suppressingConcurrentChange = true
        concurrentCombo.items = FXCollections.observableArrayList(
            (SettingsData.MIN_CONCURRENT_DOWNLOADS..SettingsData.MAX_CONCURRENT_DOWNLOADS).toList()
        )
        concurrentCombo.value = AppSettings.maxConcurrentDownloads
        suppressingConcurrentChange = false
    }

    @FXML
    private fun onSaveModeChanged() {
        if (suppressingSaveModeChange) return
        AppSettings.mediaSaveMode = saveModeCombo.value ?: return
        refreshSaveModeHint()
    }

    private fun refreshSaveModeHint() {
        saveModeHint.text = when (saveModeCombo.value) {
            MediaSaveMode.PLAYBACK_CACHE -> "保存服务器允许直接播放的完整媒体流，保留播放源格式；不使用原文件下载接口。"
            else -> "使用服务器原文件下载接口，需要账号拥有媒体下载权限。"
        }
    }

    @FXML
    private fun onConcurrentChanged() {
        if (suppressingConcurrentChange) return
        val value = concurrentCombo.value ?: return
        AppSettings.maxConcurrentDownloads = value
        TransferSession.refreshScheduler()
    }

    @FXML
    private fun onToggleEpisodeSeriesName() {
        AppSettings.episodeFileNameIncludeSeries = episodeSeriesNameCheck.isSelected
    }

    @FXML
    private fun onToggleStagingDownload() {
        AppSettings.stagingDownloadEnabled = stagingDownloadCheck.isSelected
        updateStagingControls()
    }

    @FXML
    private fun onBrowseStagingPath() {
        val chooser = DirectoryChooser().apply {
            title = "选择本地缓存目录"
            val current = Path.of(AppSettings.stagingPath)
            if (Files.isDirectory(current)) {
                initialDirectory = current.toFile()
            }
        }
        val dir = chooser.showDialog(AppShell.getStage()) ?: return
        AppSettings.stagingPath = dir.absolutePath
        stagingPathField.text = dir.absolutePath
    }

    @FXML
    private fun onBrowseDownloadPath() {
        val chooser = DirectoryChooser().apply {
            title = "选择默认下载目录"
            val current = Path.of(AppSettings.downloadPath)
            if (Files.isDirectory(current)) {
                initialDirectory = current.toFile()
            }
        }
        val dir = chooser.showDialog(AppShell.getStage()) ?: return
        AppSettings.downloadPath = dir.absolutePath
        downloadPathField.text = dir.absolutePath
        updateStagingControls()
    }

    private fun updateStagingControls() {
        val enabled = stagingDownloadCheck.isSelected
        stagingPathField.isDisable = !enabled
        val downloadPath = Path.of(AppSettings.downloadPath)
        stagingHintLabel.text = when {
            !enabled -> "已关闭：将直接写入下载目录（网络盘可能较慢）"
            DownloadStaging.isLikelyNetworkPath(downloadPath) ->
                "已检测到网络盘路径，将先下载到本地缓存再移动到 NAS"
            else -> "当前下载目录看起来是本地磁盘；仅在检测到网络盘时启用缓存"
        }
    }

    @FXML
    private fun onToggleDarkMode() {
        AppShell.applyTheme(darkModeCheck.isSelected)
    }

    @FXML
    private fun onOpenProxySettings() {
        AppShell.navigate<ProxySettingsController>(
            "proxy-settings-view.fxml",
            "Emby Download · 代理节点"
        ) {
            it.openFromSettings(returnTarget)
        }
    }

    @FXML
    private fun onClearAccounts() {
        val count = AppSettings.savedAccounts().size
        if (count == 0) {
            MaterialDialog.info(AppShell.getStage(), "已保存登录", "当前没有已保存的登录信息")
            return
        }
        val ok = MaterialDialog.confirm(
            AppShell.getStage(),
            "清除登录信息",
            "确定清除全部 $count 条已保存登录吗？",
            "此操作不可恢复。"
        )
        if (!ok) return
        AppSettings.clearAccounts()
        refreshAccountsHint()
        MaterialDialog.info(AppShell.getStage(), "已清除", "已保存的登录信息已全部清除")
    }

    @FXML
    private fun onBack() {
        if (!AppShell.goBack()) {
            when (returnTarget) {
                ReturnTarget.LOGIN ->
                    AppShell.navigateReplace<LoginController>("login-view.fxml", "Emby Download")
                ReturnTarget.HOME ->
                    AppShell.navigateReplace<HomeController>("home-view.fxml", "Emby Download · 首页")
            }
        }
    }

    private fun refreshAccountsHint() {
        val count = AppSettings.savedAccounts().size
        accountsHintLabel.text = if (count == 0) {
            "暂无已保存登录"
        } else {
            "当前已保存 $count 个登录账号"
        }
        clearAccountsButton.isDisable = count == 0
    }

    private fun refreshProxyHint() {
        val config = AppSettings.proxyConfig
        val node = config.selectedNode()
        proxyHintLabel.text = when {
            config.enabled && node != null -> "已启用：${node.name}"
            config.nodes.isNotEmpty() -> "已配置 ${config.nodes.size} 个节点（未启用）"
            else -> "配置下载节点，加速或中转传输"
        }
    }
}
