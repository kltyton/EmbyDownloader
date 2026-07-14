package com.dud.embydownload.emby.model

data class AuthenticationResult(
    val User: UserDto? = null,
    val AccessToken: String? = null,
    val ServerId: String? = null
)

data class UserDto(
    val Id: String? = null,
    val Name: String? = null,
    val ServerId: String? = null
)

data class QueryResult(
    val Items: List<BaseItemDto>? = null,
    val TotalRecordCount: Int = 0
)

data class BaseItemDto(
    val Id: String? = null,
    val Name: String? = null,
    val Type: String? = null,
    val Overview: String? = null,
    val ProductionYear: Int? = null,
    val Artists: List<String>? = null,
    val Album: String? = null,
    val SeriesName: String? = null,
    val SeasonName: String? = null,
    val IndexNumber: Int? = null,
    val ParentIndexNumber: Int? = null,
    val RunTimeTicks: Long? = null,
    val Container: String? = null,
    val Path: String? = null,
    val MediaSources: List<MediaSourceInfo>? = null,
    val CanDownload: Boolean? = null,
    val ImageTags: Map<String, String>? = null,
    val SeriesId: String? = null,
    val SeriesPrimaryImageTag: String? = null,
    val AlbumId: String? = null,
    val AlbumPrimaryImageTag: String? = null,
    val ParentId: String? = null
) {
    fun displayTitle(): String {
        if (Type == "Episode") {
            val season = ParentIndexNumber?.let { "S${it.toString().padStart(2, '0')}" }.orEmpty()
            val episode = IndexNumber?.let { "E${it.toString().padStart(2, '0')}" }.orEmpty()
            val code = "$season$episode"
            val name = Name ?: "未命名"
            return if (code.isNotBlank()) "$code · $name" else name
        }
        return Name ?: "未命名"
    }

    fun displaySubtitle(): String {
        val parts = mutableListOf<String>()
        Type?.let { parts += typeLabel(it) }
        SeriesName?.takeIf { it.isNotBlank() }?.let { parts += it }
        SeasonName?.takeIf { it.isNotBlank() }?.let { parts += it }
        ProductionYear?.let { parts += it.toString() }
        Album?.takeIf { it.isNotBlank() }?.let { parts += it }
        Artists?.takeIf { it.isNotEmpty() }?.let { parts += it.joinToString(", ") }
        Container?.takeIf { it.isNotBlank() }?.let { parts += it.uppercase() }
        return parts.joinToString(" · ")
    }

    fun isContainerItem(): Boolean = when (Type) {
        "Series", "Season", "MusicAlbum", "MusicArtist", "Folder", "BoxSet" -> true
        else -> false
    }

    private fun typeLabel(type: String): String = when (type) {
        "Movie" -> "电影"
        "Episode" -> "单集"
        "Series" -> "剧集"
        "Season" -> "季"
        "Audio" -> "音乐"
        "MusicAlbum" -> "专辑"
        "MusicArtist" -> "艺术家"
        else -> type
    }
}

data class MediaSourceInfo(
    val Id: String? = null,
    val Path: String? = null,
    val Container: String? = null,
    val Size: Long? = null,
    val Name: String? = null
)

enum class SearchMediaType(val label: String, val includeItemTypes: String) {
    MOVIE("影片", "Movie,Episode,Series"),
    MUSIC("音乐", "Audio,MusicAlbum")
}

/**
 * 季下拉选项；[season] 为 null 表示“全部季”。
 */
data class SeasonOption(
    val season: BaseItemDto?,
    val label: String
) {
    companion object {
        fun all(): SeasonOption = SeasonOption(null, "全部季")

        fun from(season: BaseItemDto): SeasonOption {
            val index = season.IndexNumber
            val name = season.Name?.takeIf { it.isNotBlank() }
            val label = when {
                index != null && name != null && !name.contains("第") && name != "Season $index" ->
                    "第${index}季 · $name"
                index != null -> "第${index}季"
                name != null -> name
                else -> "未知季"
            }
            return SeasonOption(season, label)
        }
    }

    override fun toString(): String = label
}

enum class DownloadPhase {
    DOWNLOADING,
    SAVING_TO_DESTINATION
}

data class DownloadProgress(
    val itemIndex: Int,
    val itemCount: Int,
    val itemName: String,
    val downloadedBytes: Long,
    val totalBytes: Long?,
    val speedBytesPerSec: Long,
    val completed: Boolean = false,
    val phase: DownloadPhase = DownloadPhase.DOWNLOADING
) {
    val itemProgress: Double
        get() {
            val total = totalBytes ?: return -1.0
            if (total <= 0L) return -1.0
            return (downloadedBytes.toDouble() / total.toDouble()).coerceIn(0.0, 1.0)
        }

    val overallProgress: Double
        get() {
            if (itemCount <= 0) return 0.0
            val completedItems = (itemIndex - 1).coerceAtLeast(0)
            val currentFraction = itemProgress.takeIf { it >= 0 } ?: 0.0
            return ((completedItems + currentFraction) / itemCount).coerceIn(0.0, 1.0)
        }
}
