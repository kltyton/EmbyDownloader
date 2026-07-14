package com.dud.embydownload.emby

import com.dud.embydownload.AppSession
import com.dud.embydownload.emby.model.BaseItemDto
import javafx.scene.image.Image
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

object EmbyImages {
    private val cache = ConcurrentHashMap<String, Image>()

    fun primaryCoverImage(item: BaseItemDto, maxHeight: Int = 120): Image? {
        val url = primaryCoverUrl(item, maxHeight) ?: return null
        return cache.computeIfAbsent(url) {
            Image(url, 0.0, maxHeight.toDouble(), true, true, true)
        }
    }

    fun primaryCoverUrl(item: BaseItemDto, maxHeight: Int = 120): String? {
        if (!AppSession.isLoggedIn) return null
        val (itemId, tag) = resolveImageTarget(item) ?: return null
        val base = AppSession.serverUrl.trimEnd('/')
        val token = URLEncoder.encode(AppSession.accessToken, StandardCharsets.UTF_8)
        val builder = StringBuilder()
            .append(base)
            .append("/Items/")
            .append(itemId)
            .append("/Images/Primary?maxHeight=")
            .append(maxHeight)
            .append("&quality=80&api_key=")
            .append(token)
        if (!tag.isNullOrBlank()) {
            builder.append("&tag=").append(URLEncoder.encode(tag, StandardCharsets.UTF_8))
        }
        return builder.toString()
    }

    fun clearCache() {
        cache.clear()
    }

    private fun resolveImageTarget(item: BaseItemDto): Pair<String, String?>? {
        val primaryTag = item.ImageTags?.get("Primary")
        if (!item.Id.isNullOrBlank() && !primaryTag.isNullOrBlank()) {
            return item.Id to primaryTag
        }
        if (!item.SeriesPrimaryImageTag.isNullOrBlank() && !item.SeriesId.isNullOrBlank()) {
            return item.SeriesId to item.SeriesPrimaryImageTag
        }
        if (!item.AlbumPrimaryImageTag.isNullOrBlank() && !item.AlbumId.isNullOrBlank()) {
            return item.AlbumId to item.AlbumPrimaryImageTag
        }
        // 无 tag 时仍尝试请求，Emby 可能返回默认图
        return item.Id?.takeIf { it.isNotBlank() }?.let { it to null }
    }
}
