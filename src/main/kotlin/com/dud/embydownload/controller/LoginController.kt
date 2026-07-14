package com.dud.embydownload.controller

import com.dud.embydownload.AppSession
import com.dud.embydownload.emby.EmbyApiException
import com.dud.embydownload.emby.EmbyClient
import com.dud.embydownload.proxy.EmbeddedProxyException
import com.dud.embydownload.settings.AppSettings
import com.dud.embydownload.settings.SavedAccount
import com.dud.embydownload.ui.AppShell
import javafx.application.Platform
import javafx.collections.FXCollections
import javafx.concurrent.Task
import javafx.fxml.FXML
import javafx.scene.control.Button
import javafx.scene.control.CheckBox
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.control.PasswordField
import javafx.scene.control.ProgressIndicator
import javafx.scene.control.TextField
import javafx.scene.layout.VBox

class LoginController {
    @FXML
    private lateinit var savedAccountBox: VBox

    @FXML
    private lateinit var savedAccountCombo: ComboBox<SavedAccount>

    @FXML
    private lateinit var serverUrlField: TextField

    @FXML
    private lateinit var usernameField: TextField

    @FXML
    private lateinit var passwordField: PasswordField

    @FXML
    private lateinit var rememberCheck: CheckBox

    @FXML
    private lateinit var loginButton: Button

    @FXML
    private lateinit var loadingIndicator: ProgressIndicator

    @FXML
    private lateinit var errorLabel: Label

    private var suppressingAccountChange = false

    @FXML
    private fun initialize() {
        setupSavedAccounts()
    }

    @FXML
    private fun onSavedAccountSelected() {
        if (suppressingAccountChange) return
        val account = savedAccountCombo.selectionModel.selectedItem ?: return
        serverUrlField.text = account.serverUrl
        usernameField.text = account.username
        passwordField.text = account.password()
        rememberCheck.isSelected = true
    }

    @FXML
    private fun onOpenProxy() {
        AppShell.navigate<ProxySettingsController>(
            "proxy-settings-view.fxml",
            "Emby Download · 代理节点"
        ) {
            it.openFromLogin()
        }
    }

    @FXML
    private fun onOpenSettings() {
        AppShell.navigate<SettingsController>("settings-view.fxml", "Emby Download · 设置") {
            it.setReturnTarget(SettingsController.ReturnTarget.LOGIN)
        }
    }

    @FXML
    private fun onLogin() {
        val serverUrl = serverUrlField.text.trim()
        val username = usernameField.text.trim()
        val password = passwordField.text

        if (serverUrl.isBlank() || username.isBlank()) {
            showError("请填写服务器地址和用户名")
            return
        }

        setLoading(true)
        hideError()

        val task = object : Task<Unit>() {
            override fun call() {
                val client = EmbyClient(serverUrl)
                val result = client.authenticate(username, password)
                Platform.runLater {
                    if (rememberCheck.isSelected) {
                        AppSettings.rememberAccount(serverUrl, username, password)
                    }
                    AppSession.login(
                        serverUrl = serverUrl,
                        accessToken = result.AccessToken!!,
                        user = result.User!!,
                        serverId = result.ServerId
                    )
                    AppShell.navigateReplace<HomeController>("home-view.fxml", "Emby Download · 首页")
                }
            }
        }

        task.setOnFailed {
            setLoading(false)
            showError(formatLoginError(task.exception))
        }

        Thread(task, "emby-login").apply { isDaemon = true }.start()
    }

    private fun formatLoginError(error: Throwable?): String {
        val root = generateSequence(error) { it.cause }.last()
        val config = AppSettings.proxyConfig
        val proxyHint = when {
            config.nodes.isNotEmpty() && !config.proxyLogin ->
                "（已配置节点但未开启「登录走代理」）"
            config.proxyLogin -> config.selectedNode()?.name?.let { "（代理：$it）" } ?: "（代理已启用）"
            else -> ""
        }
        return when (root) {
            is EmbyApiException -> root.message ?: "登录失败"
            is EmbeddedProxyException -> "${root.message}$proxyHint"
            else -> "无法连接服务器$proxyHint：${root.message ?: "未知错误"}"
        }
    }

    private fun setupSavedAccounts() {
        val accounts = AppSettings.savedAccounts()
        val hasAccounts = accounts.isNotEmpty()
        savedAccountBox.isVisible = hasAccounts
        savedAccountBox.isManaged = hasAccounts

        if (!hasAccounts) {
            serverUrlField.text = "http://127.0.0.1:8096"
            return
        }

        savedAccountCombo.setCellFactory {
            object : ListCell<SavedAccount>() {
                override fun updateItem(item: SavedAccount?, empty: Boolean) {
                    super.updateItem(item, empty)
                    text = if (empty || item == null) null else item.displayLabel()
                }
            }
        }
        savedAccountCombo.buttonCell = object : ListCell<SavedAccount>() {
            override fun updateItem(item: SavedAccount?, empty: Boolean) {
                super.updateItem(item, empty)
                text = if (empty || item == null) null else item.displayLabel()
            }
        }

        suppressingAccountChange = true
        savedAccountCombo.items = FXCollections.observableArrayList(accounts)
        savedAccountCombo.selectionModel.selectFirst()
        suppressingAccountChange = false
        onSavedAccountSelected()
    }

    private fun setLoading(loading: Boolean) {
        loginButton.isDisable = loading
        serverUrlField.isDisable = loading
        usernameField.isDisable = loading
        passwordField.isDisable = loading
        rememberCheck.isDisable = loading
        savedAccountCombo.isDisable = loading
        loadingIndicator.isVisible = loading
        loadingIndicator.isManaged = loading
    }

    private fun showError(message: String) {
        errorLabel.text = message
        errorLabel.isVisible = true
        errorLabel.isManaged = true
    }

    private fun hideError() {
        errorLabel.isVisible = false
        errorLabel.isManaged = false
    }
}
