package com.dud.embydownload.settings

enum class MediaSaveMode(private val label: String) {
    ORIGINAL_FILE("原文件下载"),
    PLAYBACK_CACHE("直接播放缓存");

    override fun toString(): String = label
}
