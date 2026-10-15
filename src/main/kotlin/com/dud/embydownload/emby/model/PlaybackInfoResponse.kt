package com.dud.embydownload.emby.model

data class PlaybackInfoResponse(
    val MediaSources: List<MediaSourceInfo>? = null,
    val PlaySessionId: String? = null,
    val ErrorCode: String? = null
)
