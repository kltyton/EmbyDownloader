package com.dud.embydownload.controller

import com.dud.embydownload.proxy.DownloadHttpClients
import com.dud.embydownload.proxy.ProxyFetchException
import com.dud.embydownload.proxy.ProxyNodeClient
import com.dud.embydownload.settings.AppSettings
import com.dud.embydownload.settings.ProxyNode
import com.dud.embydownload.ui.AppShell
import com.dud.embydownload.ui.MaterialDialog
import javafx.collections.FXCollections
import javafx.concurrent.Task
import javafx.fxml.FXML
import javafx.scene.control.Button
import javafx.scene.control.CheckBox
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.control.ProgressIndicator
import javafx.scene.control.TextArea
import javafx.scene.control.TextField

class ProxySettingsController {
    enum class BackTarget { LOGIN, SETTINGS }

    @FXML
    private lateinit var subscriptionUrlField: TextField

    @FXML
    private lateinit var fetchButton: Button

    @FXML
    private lateinit var fetchLoading: ProgressIndicator

    @FXML
    private lateinit var nodeCombo: ComboBox<ProxyNode>

    @FXML
    private lateinit var nodeInfoArea: TextArea

    @FXML
    private lateinit var enableCheck: CheckBox

    @FXML
    private lateinit var proxyLoginCheck: CheckBox

    @FXML
    private lateinit var proxySearchCheck: CheckBox

    @FXML
    private lateinit var statusLabel: Label

    private var suppressingChange = false
    private var backTarget: BackTarget = BackTarget.SETTINGS
    private var settingsReturnTarget: SettingsController.ReturnTarget = SettingsController.ReturnTarget.HOME

    fun openFromLogin() {
        backTarget = BackTarget.LOGIN
    }

    fun openFromSettings(settingsReturn: SettingsController.ReturnTarget) {
        backTarget = BackTarget.SETTINGS
        settingsReturnTarget = settingsReturn
    }

    @FXML
    private fun initialize() {
        val config = AppSettings.proxyConfig
        subscriptionUrlField.text = config.subscriptionUrl
        enableCheck.isSelected = config.enabled
        proxyLoginCheck.isSelected = config.proxyLogin
        proxySearchCheck.isSelected = config.proxySearch

        nodeCombo.setCellFactory {
            object : ListCell<ProxyNode>() {
                override fun updateItem(item: ProxyNode?, empty: Boolean) {
                    super.updateItem(item, empty)
                    text = if (empty || item == null) null else item.displayLabel()
                }
            }
        }
        nodeCombo.buttonCell = object : ListCell<ProxyNode>() {
            override fun updateItem(item: ProxyNode?, empty: Boolean) {
                super.updateItem(item, empty)
                text = if (empty || item == null) null else item.displayLabel()
            }
        }

        refreshNodeCombo(config.nodes, config.selectedNodeId)
        updateStatus()
    }

    @FXML
    private fun onFetchNodes() {
        val url = subscriptionUrlField.text.trim()
        if (url.isBlank()) {
            MaterialDialog.info(AppShell.getStage(), "提示", "请先填写节点配置 URL")
            return
        }

        setFetching(true)
        statusLabel.text = "正在获取节点..."

        val task = object : Task<List<ProxyNode>>() {
            override fun call(): List<ProxyNode> = ProxyNodeClient.fetchNodes(url)
        }

        task.setOnSucceeded {
            setFetching(false)
            val nodes = task.value
            AppSettings.updateProxy {
                subscriptionUrl = url
                this.nodes = nodes.toMutableList()
                selectedNodeId = nodes.firstOrNull()?.id
            }
            DownloadHttpClients.invalidate()
            refreshNodeCombo(nodes, nodes.firstOrNull()?.id)
            val embeddedCount = nodes.count { it.requiresEmbeddedCore() }
            statusLabel.text = "已获取 ${nodes.size} 个节点"
            val detail = if (embeddedCount > 0) {
                "共 ${nodes.size} 个节点（其中 $embeddedCount 个将通过安装包内置 mihomo 连接）。"
            } else {
                "共拉取到 ${nodes.size} 个节点，请选择后按需启用。"
            }
            MaterialDialog.info(
                AppShell.getStage(),
                "获取成功",
                detail
            )
        }

        task.setOnFailed {
            setFetching(false)
            val error = task.exception
            val message = when (error) {
                is ProxyFetchException -> error.message
                else -> "获取失败：${error?.message ?: "未知错误"}"
            }
            statusLabel.text = message
            MaterialDialog.error(AppShell.getStage(), "获取失败", message ?: "未知错误")
        }

        Thread(task, "proxy-fetch").apply { isDaemon = true }.start()
    }

    @FXML
    private fun onNodeSelected() {
        if (suppressingChange) return
        val selected = nodeCombo.selectionModel.selectedItem ?: return
        AppSettings.updateProxy { selectedNodeId = selected.id }
        DownloadHttpClients.invalidate()
        nodeInfoArea.text = selected.summary()
        updateStatus()
    }

    @FXML
    private fun onToggleEnable() {
        if (!ensureNodeReady(enableCheck)) return
        AppSettings.updateProxy { enabled = enableCheck.isSelected }
        DownloadHttpClients.invalidate()
        updateStatus()
    }

    @FXML
    private fun onToggleProxyLogin() {
        if (!ensureNodeReady(proxyLoginCheck)) return
        AppSettings.updateProxy { proxyLogin = proxyLoginCheck.isSelected }
        DownloadHttpClients.invalidate()
        updateStatus()
    }

    @FXML
    private fun onToggleProxySearch() {
        if (!ensureNodeReady(proxySearchCheck)) return
        AppSettings.updateProxy { proxySearch = proxySearchCheck.isSelected }
        DownloadHttpClients.invalidate()
        updateStatus()
    }

    @FXML
    private fun onClear() {
        val ok = MaterialDialog.confirm(
            AppShell.getStage(),
            "清除节点配置",
            "确定清除已保存的节点配置吗？"
        )
        if (!ok) return
        AppSettings.updateProxy {
            enabled = false
            proxyLogin = false
            proxySearch = false
            subscriptionUrl = ""
            selectedNodeId = null
            nodes.clear()
        }
        DownloadHttpClients.shutdownEmbeddedCore()
        subscriptionUrlField.clear()
        enableCheck.isSelected = false
        proxyLoginCheck.isSelected = false
        proxySearchCheck.isSelected = false
        refreshNodeCombo(emptyList(), null)
        statusLabel.text = "已清除节点配置"
    }

    @FXML
    private fun onBack() {
        if (!AppShell.goBack()) {
            when (backTarget) {
                BackTarget.LOGIN ->
                    AppShell.navigateReplace<LoginController>("login-view.fxml", "Emby Download")
                BackTarget.SETTINGS ->
                    AppShell.navigateReplace<SettingsController>("settings-view.fxml", "Emby Download · 设置") {
                        it.setReturnTarget(settingsReturnTarget)
                    }
            }
        }
    }

    private fun ensureNodeReady(checkBox: CheckBox): Boolean {
        if (!checkBox.isSelected) return true
        if (AppSettings.proxyConfig.nodes.isEmpty() || AppSettings.proxyConfig.selectedNode() == null) {
            checkBox.isSelected = false
            MaterialDialog.info(AppShell.getStage(), "无法启用", "请先获取并选择节点")
            return false
        }
        return true
    }

    private fun refreshNodeCombo(nodes: List<ProxyNode>, selectedId: String?) {
        suppressingChange = true
        nodeCombo.items = FXCollections.observableArrayList(nodes)
        if (nodes.isEmpty()) {
            nodeCombo.selectionModel.clearSelection()
            nodeInfoArea.text = "暂无节点信息"
        } else {
            val selected = nodes.firstOrNull { it.id == selectedId } ?: nodes.first()
            nodeCombo.selectionModel.select(selected)
            nodeInfoArea.text = selected.summary()
        }
        suppressingChange = false
        val empty = nodes.isEmpty()
        enableCheck.isDisable = empty
        proxyLoginCheck.isDisable = empty
        proxySearchCheck.isDisable = empty
    }

    private fun updateStatus() {
        val config = AppSettings.proxyConfig
        val node = config.selectedNode()
        val flags = buildList {
            if (config.enabled) add("下载")
            if (config.proxyLogin) add("登录")
            if (config.proxySearch) add("搜索")
        }
        statusLabel.text = when {
            node == null -> "未选择节点"
            flags.isEmpty() && node.requiresEmbeddedCore() ->
                "已选择 mihomo 节点（${node.displayLabel()}），启用后将使用安装目录内置核心"
            flags.isEmpty() -> "已选择节点，暂未启用任何代理用途"
            else -> "已启用（${flags.joinToString(" / ")}）：${node.displayLabel()}"
        }
    }

    private fun setFetching(loading: Boolean) {
        fetchButton.isDisable = loading
        subscriptionUrlField.isDisable = loading
        fetchLoading.isVisible = loading
        fetchLoading.isManaged = loading
    }
}
