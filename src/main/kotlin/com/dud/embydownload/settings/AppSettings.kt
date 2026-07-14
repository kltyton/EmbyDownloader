package com.dud.embydownload.settings

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.prefs.Preferences

data class SavedAccount(
    val serverUrl: String,
    val username: String,
    val passwordEncoded: String,
    val lastUsed: Long = System.currentTimeMillis()
) {
    fun displayLabel(): String = "$username  ·  $serverUrl"

    fun password(): String = decode(passwordEncoded)

    companion object {
        fun create(serverUrl: String, username: String, password: String): SavedAccount =
            SavedAccount(
                serverUrl = serverUrl.trim().removeSuffix("/"),
                username = username.trim(),
                passwordEncoded = encode(password),
                lastUsed = System.currentTimeMillis()
            )

        private fun encode(value: String): String =
            Base64.getEncoder().encodeToString(value.toByteArray(StandardCharsets.UTF_8))

        private fun decode(value: String): String =
            runCatching {
                String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8)
            }.getOrDefault("")
    }
}

data class SettingsData(
    var downloadPath: String = Path.of(System.getProperty("user.home"), "Downloads", "EmbyDownload").toString(),
    var stagingDownloadEnabled: Boolean = true,
    var stagingPath: String = defaultStagingPath(),
    var darkMode: Boolean = false,
    var savedAccounts: MutableList<SavedAccount> = mutableListOf(),
    var proxy: ProxyConfig? = ProxyConfig()
) {
    companion object {
        fun defaultStagingPath(): String {
            val localAppData = System.getenv("LOCALAPPDATA")
            val base = if (!localAppData.isNullOrBlank()) Path.of(localAppData) else Path.of(System.getProperty("user.home"))
            return base.resolve("EmbyDownload").resolve("staging").toString()
        }
    }
}

object AppSettings {
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val prefs: Preferences = Preferences.userRoot().node("com/dud/embydownload")
    private val filePath: Path = Path.of(System.getProperty("user.home"), ".embydownload", "settings.json")

    private var cache: SettingsData = loadInternal()

    val data: SettingsData
        get() = cache

    var downloadPath: String
        get() = cache.downloadPath.ifBlank { defaultDownloadPath() }
        set(value) {
            cache.downloadPath = value
            save()
        }

    var stagingDownloadEnabled: Boolean
        get() = cache.stagingDownloadEnabled
        set(value) {
            cache.stagingDownloadEnabled = value
            save()
        }

    var stagingPath: String
        get() = cache.stagingPath.ifBlank { SettingsData.defaultStagingPath() }
        set(value) {
            cache.stagingPath = value
            save()
        }

    var darkMode: Boolean
        get() = cache.darkMode
        set(value) {
            cache.darkMode = value
            save()
        }

    var proxyConfig: ProxyConfig
        get() {
            if (cache.proxy == null) {
                cache.proxy = ProxyConfig()
            }
            return cache.proxy!!
        }
        set(value) {
            cache.proxy = value
            save()
        }

    fun updateProxy(block: ProxyConfig.() -> Unit) {
        proxyConfig.block()
        save()
    }

    fun savedAccounts(): List<SavedAccount> =
        cache.savedAccounts.sortedByDescending { it.lastUsed }

    fun rememberAccount(serverUrl: String, username: String, password: String) {
        val account = SavedAccount.create(serverUrl, username, password)
        cache.savedAccounts.removeAll {
            it.serverUrl.equals(account.serverUrl, true) &&
                it.username.equals(account.username, true)
        }
        cache.savedAccounts.add(0, account)
        // 最多保留 20 条
        if (cache.savedAccounts.size > 20) {
            cache.savedAccounts = cache.savedAccounts.take(20).toMutableList()
        }
        save()
    }

    fun removeAccount(account: SavedAccount) {
        cache.savedAccounts.removeAll {
            it.serverUrl.equals(account.serverUrl, true) &&
                it.username.equals(account.username, true)
        }
        save()
    }

    fun clearAccounts() {
        cache.savedAccounts.clear()
        save()
    }

    fun reload() {
        cache = loadInternal()
    }

    fun save() {
        try {
            Files.createDirectories(filePath.parent)
            Files.writeString(filePath, gson.toJson(cache), StandardCharsets.UTF_8)
            prefs.putBoolean("darkMode", cache.darkMode)
            prefs.put("downloadPath", cache.downloadPath)
        } catch (_: Exception) {
            prefs.putBoolean("darkMode", cache.darkMode)
            prefs.put("downloadPath", cache.downloadPath)
        }
    }

    private fun loadInternal(): SettingsData {
        return try {
            if (Files.exists(filePath)) {
                gson.fromJson(Files.readString(filePath, StandardCharsets.UTF_8), SettingsData::class.java)
                    ?: SettingsData()
            } else {
                SettingsData(
                    downloadPath = prefs.get("downloadPath", defaultDownloadPath()),
                    darkMode = prefs.getBoolean("darkMode", false)
                )
            }
        } catch (_: Exception) {
            SettingsData()
        }
    }

    private fun defaultDownloadPath(): String =
        Path.of(System.getProperty("user.home"), "Downloads", "EmbyDownload").toString()
}
