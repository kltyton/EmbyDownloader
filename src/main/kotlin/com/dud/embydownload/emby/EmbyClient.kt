package com.dud.embydownload.emby

import com.dud.embydownload.emby.model.AuthenticationResult
import com.dud.embydownload.emby.model.BaseItemDto
import com.dud.embydownload.emby.model.DownloadPhase
import com.dud.embydownload.emby.model.DownloadProgress
import com.dud.embydownload.emby.model.QueryResult
import com.dud.embydownload.emby.model.SearchMediaType
import com.dud.embydownload.proxy.DownloadHttpClients
import com.dud.embydownload.settings.AppSettings
import com.dud.embydownload.transfer.DownloadCancelledException
import com.dud.embydownload.transfer.DownloadControl
import com.dud.embydownload.transfer.DownloadStaging
import com.google.gson.Gson
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.util.UUID

class EmbyClient(
    baseUrl: String,
    private val accessToken: String? = null,
    private val userId: String? = null
) {
    private val baseUrl = normalizeBaseUrl(baseUrl)
    private val gson = Gson()
    private val httpClient: HttpClient = DownloadHttpClients.defaultClient()

    private val deviceId: String = UUID.nameUUIDFromBytes("EmbyDownload-PC".toByteArray()).toString()

    fun authenticate(username: String, password: String): AuthenticationResult {
        val body = gson.toJson(
            mapOf(
                "Username" to username,
                "Pw" to password
            )
        )

        val originalUrl = "$baseUrl/Users/AuthenticateByName"
        val loginNode = AppSettings.proxyConfig.loginNode()
        val client = DownloadHttpClients.loginClient()
        val uri = DownloadHttpClients.resolveApiUri(originalUrl, loginNode)
        val timeoutSeconds = DownloadHttpClients.loginTimeoutSeconds()

        val request = HttpRequest.newBuilder()
            .uri(uri)
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .header("Content-Type", "application/json")
            .header("X-Emby-Authorization", authorizationHeader())
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            val viaProxy = loginNode?.displayLabel()?.let { "（代理：$it）" } ?: ""
            throw EmbyApiException("登录失败（HTTP ${response.statusCode()}）$viaProxy：请检查地址、用户名或密码")
        }

        val result = gson.fromJson(response.body(), AuthenticationResult::class.java)
        if (result.AccessToken.isNullOrBlank() || result.User?.Id.isNullOrBlank()) {
            throw EmbyApiException("登录失败：服务器未返回有效令牌")
        }
        return result
    }

    fun search(term: String, mediaType: SearchMediaType, limit: Int = 50): List<BaseItemDto> {
        requireNotNull(accessToken) { "未登录" }
        requireNotNull(userId) { "未登录" }

        val query = buildString {
            append("SearchTerm=${encode(term)}")
            append("&IncludeItemTypes=${encode(mediaType.includeItemTypes)}")
            append("&Recursive=true")
            append("&Fields=${encode(DETAIL_FIELDS)}")
            append("&Limit=$limit")
            append("&UserId=${encode(userId)}")
        }

        val originalUrl = "$baseUrl/Users/$userId/Items?$query"
        val searchNode = AppSettings.proxyConfig.searchNode()
        val client = DownloadHttpClients.searchClient()
        val uri = DownloadHttpClients.resolveApiUri(originalUrl, searchNode)
        val request = authorizedGet(uri.toString())
        return parseItems(request, "搜索失败", client)
    }

    /**
     * 根据条目类型加载可下载子项：剧集 → 分集，专辑 → 音轨；电影/单集等叶子节点返回自身。
     */
    fun getDownloadableItems(parent: BaseItemDto): List<BaseItemDto> {
        requireNotNull(accessToken) { "未登录" }
        requireNotNull(userId) { "未登录" }
        val parentId = parent.Id ?: throw EmbyApiException("资源缺少 ID")

        return when (parent.Type) {
            "Series" -> getEpisodes(seriesId = parentId)
            "Season" -> getChildren(
                parentId = parentId,
                includeItemTypes = "Episode",
                recursive = false
            )
            "MusicAlbum" -> getChildren(
                parentId = parentId,
                includeItemTypes = "Audio",
                recursive = false
            )
            "MusicArtist" -> getChildren(
                parentId = parentId,
                includeItemTypes = "Audio",
                recursive = true
            )
            "Folder", "BoxSet" -> getChildren(
                parentId = parentId,
                includeItemTypes = "Movie,Episode,Audio",
                recursive = true
            )
            else -> listOf(parent)
        }
    }

    fun getEpisodes(seriesId: String, seasonId: String? = null): List<BaseItemDto> {
        requireNotNull(accessToken) { "未登录" }
        requireNotNull(userId) { "未登录" }

        val query = buildString {
            append("UserId=${encode(userId)}")
            append("&Fields=${encode(DETAIL_FIELDS)}")
            if (!seasonId.isNullOrBlank()) {
                append("&SeasonId=${encode(seasonId)}")
            }
        }

        val request = authorizedGet("$baseUrl/Shows/$seriesId/Episodes?$query")
        return parseItems(request, "加载剧集失败")
    }

    fun getSeasons(seriesId: String): List<BaseItemDto> {
        requireNotNull(accessToken) { "未登录" }
        requireNotNull(userId) { "未登录" }

        val query = buildString {
            append("UserId=${encode(userId)}")
            append("&Fields=${encode(DETAIL_FIELDS)}")
        }

        val request = authorizedGet("$baseUrl/Shows/$seriesId/Seasons?$query")
        return parseItems(request, "加载季列表失败")
            .sortedWith(compareBy(nullsLast()) { it.IndexNumber })
    }

    fun getChildren(
        parentId: String,
        includeItemTypes: String? = null,
        recursive: Boolean = false
    ): List<BaseItemDto> {
        requireNotNull(accessToken) { "未登录" }
        requireNotNull(userId) { "未登录" }

        val query = buildString {
            append("ParentId=${encode(parentId)}")
            append("&Recursive=$recursive")
            append("&Fields=${encode(DETAIL_FIELDS)}")
            includeItemTypes?.let { append("&IncludeItemTypes=${encode(it)}") }
            append("&SortBy=${encode("ParentIndexNumber,IndexNumber,SortName")}")
            append("&SortOrder=Ascending")
        }

        val request = authorizedGet("$baseUrl/Users/$userId/Items?$query")
        return parseItems(request, "加载子资源失败")
    }

    fun downloadItem(
        item: BaseItemDto,
        targetFile: Path,
        control: DownloadControl? = null,
        onProgress: ((downloadedBytes: Long, totalBytes: Long?) -> Unit)? = null
    ): Path {
        requireNotNull(accessToken) { "未登录" }
        val itemId = item.Id ?: throw EmbyApiException("资源缺少 ID，无法下载")
        control?.check()

        Files.createDirectories(targetFile.parent)
        val existingBytes = runCatching {
            if (Files.isRegularFile(targetFile)) Files.size(targetFile) else 0L
        }.getOrDefault(0L).coerceAtLeast(0L)

        val originalUrl = "$baseUrl/Items/$itemId/Download"
        val downloadUri = DownloadHttpClients.resolveDownloadUri(originalUrl)
        val downloadClient = DownloadHttpClients.downloadClient()

        val requestBuilder = HttpRequest.newBuilder()
            .uri(downloadUri)
            .timeout(Duration.ofMinutes(60))
            .GET()

        requestBuilder.header("X-Emby-Token", accessToken)
        requestBuilder.header("X-Emby-Authorization", authorizationHeader(accessToken))
        if (existingBytes > 0L) {
            requestBuilder.header("Range", "bytes=$existingBytes-")
        }

        val response = downloadClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofInputStream())
        val status = response.statusCode()
        if (status !in 200..299) {
            throw EmbyApiException("下载失败（HTTP $status）")
        }

        val append = status == 206 && existingBytes > 0L
        if (!append && existingBytes > 0L) {
            // 服务器不支持断点续传，重新下完整文件
            runCatching { Files.deleteIfExists(targetFile) }
        }

        val totalFromItem = item.MediaSources?.firstOrNull()?.Size?.takeIf { it > 0 }
        val contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L)
            .takeIf { it > 0 }
        val contentRange = response.headers().firstValue("Content-Range").orElse(null)
        val totalBytes = parseContentRangeTotal(contentRange)
            ?: if (append && contentLength != null) existingBytes + contentLength else contentLength
            ?: totalFromItem

        var downloaded = if (append) existingBytes else 0L
        onProgress?.invoke(downloaded, totalBytes)

        val bufferSize = if (DownloadStaging.isLikelyNetworkPath(targetFile)) 1024 * 1024 else 256 * 1024
        val openOptions = if (append) {
            arrayOf(StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)
        } else {
            arrayOf(StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
        }

        try {
            response.body().use { input ->
                Files.newOutputStream(targetFile, *openOptions).buffered(bufferSize).use { output ->
                    val buffer = ByteArray(bufferSize)
                    while (true) {
                        control?.check()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        onProgress?.invoke(downloaded, totalBytes)
                    }
                }
            }
        } catch (ex: DownloadCancelledException) {
            runCatching { Files.deleteIfExists(targetFile) }
            throw ex
        }
        // 其他异常保留本地半成品，供断点续传 / 自动重连
        return targetFile
    }

    private fun parseContentRangeTotal(header: String?): Long? {
        if (header.isNullOrBlank()) return null
        // 例：bytes 1000-9999/12345
        val slash = header.lastIndexOf('/')
        if (slash < 0 || slash == header.lastIndex) return null
        val totalPart = header.substring(slash + 1).trim()
        if (totalPart == "*") return null
        return totalPart.toLongOrNull()?.takeIf { it > 0 }
    }

    fun suggestFileName(item: BaseItemDto): String {
        val baseName = when (item.Type) {
            "Episode" -> {
                val seriesPrefix = if (AppSettings.episodeFileNameIncludeSeries) {
                    item.SeriesName?.takeIf { it.isNotBlank() }?.let { sanitizeFileName(it) + " - " }.orEmpty()
                } else {
                    ""
                }
                val season = item.ParentIndexNumber?.let { "S${it.toString().padStart(2, '0')}" }.orEmpty()
                val episode = item.IndexNumber?.let { "E${it.toString().padStart(2, '0')}" }.orEmpty()
                val title = sanitizeFileName(item.Name ?: "episode")
                "$seriesPrefix$season$episode - $title".trim(' ', '-')
            }
            else -> sanitizeFileName(item.Name ?: "emby-item")
        }
        val container = item.Container
            ?: item.MediaSources?.firstOrNull()?.Container
            ?: item.Path?.substringAfterLast('.', "")?.takeIf { it.isNotBlank() && it.length <= 5 }
        return if (container.isNullOrBlank()) baseName else "$baseName.$container"
    }

    /**
     * 剧集：在下载根目录下创建以剧名命名的文件夹；电影等其它类型直接落在根目录。
     */
    fun suggestTargetDirectory(baseDirectory: Path, item: BaseItemDto): Path {
        if (item.Type != "Episode") return baseDirectory
        val seriesName = item.SeriesName?.takeIf { it.isNotBlank() }
            ?: item.Name?.takeIf { it.isNotBlank() }
            ?: "Series"
        return baseDirectory.resolve(sanitizeFileName(seriesName))
    }

    fun downloadItems(
        items: List<BaseItemDto>,
        targetDirectory: Path,
        control: DownloadControl? = null,
        onProgress: ((DownloadProgress) -> Unit)? = null
    ): List<Path> {
        val saved = mutableListOf<Path>()
        val useStaging = DownloadStaging.shouldStage(targetDirectory)
        if (useStaging) {
            Files.createDirectories(DownloadStaging.stagingDirectory())
        }
        items.forEachIndexed { index, item ->
            control?.check()
            val itemDirectory = suggestTargetDirectory(targetDirectory, item)
            Files.createDirectories(itemDirectory)
            val finalTarget = itemDirectory.resolve(suggestFileName(item))
            val writeTarget = if (useStaging) DownloadStaging.stagingFileFor(finalTarget) else finalTarget
            val totalBytes = item.MediaSources?.firstOrNull()?.Size
            var lastBytes = 0L
            var lastTimeNs = System.nanoTime()
            var speed = 0L

            fun emitProgress(
                downloaded: Long,
                total: Long?,
                phase: DownloadPhase,
                completed: Boolean = false
            ) {
                val now = System.nanoTime()
                val elapsedNs = now - lastTimeNs
                if (elapsedNs >= 200_000_000L) {
                    val delta = downloaded - lastBytes
                    speed = (delta * 1_000_000_000L) / elapsedNs
                    lastBytes = downloaded
                    lastTimeNs = now
                }
                onProgress?.invoke(
                    DownloadProgress(
                        itemIndex = index + 1,
                        itemCount = items.size,
                        itemName = item.displayTitle(),
                        downloadedBytes = downloaded,
                        totalBytes = total,
                        speedBytesPerSec = speed,
                        completed = completed,
                        phase = phase
                    )
                )
            }

            emitProgress(0L, totalBytes, DownloadPhase.DOWNLOADING)

            try {
                downloadItem(item, writeTarget, control) { downloaded, total ->
                    emitProgress(downloaded, total, DownloadPhase.DOWNLOADING)
                }

                val savedPath = if (useStaging) {
                    lastBytes = 0L
                    lastTimeNs = System.nanoTime()
                    speed = 0L
                    emitProgress(0L, totalBytes, DownloadPhase.SAVING_TO_DESTINATION)
                    DownloadStaging.moveToFinal(writeTarget, finalTarget, control) { moved, total ->
                        emitProgress(moved, total, DownloadPhase.SAVING_TO_DESTINATION)
                    }
                } else {
                    finalTarget
                }
                emitProgress(totalBytes ?: 0L, totalBytes, DownloadPhase.DOWNLOADING, completed = true)
                saved.add(savedPath)
            } catch (ex: DownloadCancelledException) {
                runCatching { Files.deleteIfExists(writeTarget) }
                throw ex
            } catch (ex: Exception) {
                runCatching { Files.deleteIfExists(writeTarget) }
                throw ex
            }
        }
        return saved
    }

    private fun authorizedGet(url: String, client: HttpClient = httpClient): HttpRequest {
        val token = accessToken ?: throw EmbyApiException("未登录")
        return HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(60))
            .header("X-Emby-Token", token)
            .header("X-Emby-Authorization", authorizationHeader(token))
            .GET()
            .build()
    }

    private fun parseItems(
        request: HttpRequest,
        errorPrefix: String,
        client: HttpClient = httpClient
    ): List<BaseItemDto> {
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw EmbyApiException("$errorPrefix（HTTP ${response.statusCode()}）")
        }
        return gson.fromJson(response.body(), QueryResult::class.java).Items.orEmpty()
    }

    private fun authorizationHeader(token: String? = null): String {
        val parts = mutableListOf(
            """Client="EmbyDownload"""",
            """Device="PC"""",
            """DeviceId="$deviceId"""",
            """Version="1.0.0""""
        )
        if (!token.isNullOrBlank()) {
            parts += """Token="$token""""
        }
        return "Emby ${parts.joinToString(", ")}"
    }

    companion object {
        private const val DETAIL_FIELDS =
            "Overview,Path,MediaSources,CanDownload,Container,ProductionYear,Artists,Album,RunTimeTicks," +
                "SeriesName,SeasonName,IndexNumber,ParentIndexNumber,ImageTags,PrimaryImageAspectRatio," +
                "SeriesId,SeriesPrimaryImageTag,AlbumId,AlbumPrimaryImageTag,ParentId"

        fun normalizeBaseUrl(url: String): String {
            var normalized = url.trim().removeSuffix("/")
            if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
                normalized = "http://$normalized"
            }
            return normalized
        }

        private fun encode(value: String): String =
            URLEncoder.encode(value, StandardCharsets.UTF_8)

        fun sanitizeFileName(name: String): String =
            name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "emby-item" }
    }
}

class EmbyApiException(message: String) : RuntimeException(message)
