package com.dud.embydownload

import com.dud.embydownload.emby.EmbyClient
import com.dud.embydownload.emby.EmbyImages
import com.dud.embydownload.emby.model.UserDto

object AppSession {
    var serverUrl: String = ""
        private set
    var accessToken: String = ""
        private set
    var user: UserDto? = null
        private set
    var serverId: String? = null
        private set

    val isLoggedIn: Boolean
        get() = accessToken.isNotBlank() && user?.Id != null

    fun login(serverUrl: String, accessToken: String, user: UserDto, serverId: String?) {
        this.serverUrl = EmbyClient.normalizeBaseUrl(serverUrl)
        this.accessToken = accessToken
        this.user = user
        this.serverId = serverId
    }

    fun logout() {
        serverUrl = ""
        accessToken = ""
        user = null
        serverId = null
        EmbyImages.clearCache()
    }

    fun createClient(): EmbyClient {
        check(isLoggedIn) { "当前未登录" }
        return EmbyClient(serverUrl, accessToken, user!!.Id)
    }
}
