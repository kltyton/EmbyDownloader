package com.dud.embydownload.emby.model

import java.util.UUID

data class ClientIdentity(
    val clientName: String = "EmbyDownload",
    val clientVersion: String = "1.1.3",
    val deviceName: String = "PC",
    val deviceId: String = UUID.nameUUIDFromBytes("EmbyDownload-PC".toByteArray()).toString()
)
