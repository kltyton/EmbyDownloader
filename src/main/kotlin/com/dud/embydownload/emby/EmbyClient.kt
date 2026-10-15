package com.dud.embydownload.emby

import com.dud.embydownload.emby.model.AuthenticationResult
import com.dud.embydownload.emby.model.ClientIdentity
import com.dud.embydownload.emby.model.BaseItemDto
import com.dud.embydownload.emby.model.DownloadPhase
import com.dud.embydownload.emby.model.DownloadProgress
import com.dud.embydownload.emby.model.QueryResult
import com.dud.embydownload.emby.model.PlaybackInfoResponse
import com.dud.embydownload.emby.model.SearchMediaType
import com.dud.embydownload.emby.subtitle.SubtitleContentException
import com.dud.embydownload.emby.subtitle.SubtitleFiles
import com.dud.embydownload.proxy.DownloadHttpClients
import com.dud.embydownload.settings.AppSettings
import com.dud.embydownload.settings.MediaSaveMode
import com.dud.embydownload.transfer.DownloadCancelledException
import com.dud.embydownload.transfer.DownloadControl
import com.dud.embydownload.transfer.DownloadStaging
import com.google.gson.Gson
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Duration

class EmbyClient(
    baseUrl: String,
    private val accessToken: String? = null,
    private val userId: String? = null,
    private val identity: ClientIdentity = ClientIdentity()
) {
    private val baseUrl = normalizeBaseUrl(baseUrl)
    private val gson = Gson()
    private val httpClient: HttpClient
        get() = DownloadHttpClients.searchClient()


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
        val viaProxy = loginNode?.displayLabel()?.let { "（代理：$it）" } ?: ""
        if (isCloudflareBlocked(response.headers(), response.body())) {
            throw EmbyApiException(
                "登录请求被 Cloudflare 拦截（HTTP ${response.statusCode()}）$viaProxy：" +
                    "请联系服务器管理员确认此客户端的 API 访问规则"
            )
        }
        if (response.statusCode() !in 200..299) {
            val reason = when (response.statusCode()) {
                401 -> "身份验证未通过，请检查用户名或密码"
                403 -> "服务器拒绝此客户端或账号访问，请检查服务端访问策略"
                404 -> "找不到登录接口，请检查服务器地址及 API 路径"
                429 -> "请求过于频繁，请稍后再试"
                else -> "服务器未能完成登录请求"
            }
            throw EmbyApiException("登录失败（HTTP ${response.statusCode()}）$viaProxy：$reason")
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

    fun getItem(itemId: String): BaseItemDto {
        requireNotNull(accessToken) { "未登录" }
        requireNotNull(userId) { "未登录" }
        val query = "UserId=${encode(userId)}&Fields=${encode(DETAIL_FIELDS)}"
        val original = "$baseUrl/Users/$userId/Items/$itemId?$query"
        val uri = DownloadHttpClients.resolveApiUri(original, AppSettings.proxyConfig.searchNode())
        return parseItem(authorizedGet(uri.toString()), "加载媒体详情失败")
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
        saveMode: MediaSaveMode = AppSettings.mediaSaveMode,
        onProgress: ((downloadedBytes: Long, totalBytes: Long?) -> Unit)? = null
    ): Path {
        requireNotNull(accessToken) { "未登录" }
        val itemId = item.Id ?: throw EmbyApiException("资源缺少 ID，无法下载")
        control?.check()
        if (saveMode == MediaSaveMode.ORIGINAL_FILE && item.CanDownload == false) {
            throw EmbyApiException("服务器未允许下载此项目，请确认账号下载权限", 403)
        }

        Files.createDirectories(targetFile.parent)
        val existingBytes = runCatching {
            if (Files.isRegularFile(targetFile)) Files.size(targetFile) else 0L
        }.getOrDefault(0L).coerceAtLeast(0L)

        val source = if (saveMode == MediaSaveMode.PLAYBACK_CACHE) {
            resolvePlaybackSource(item)
        } else {
            MediaRequest(URI.create("$baseUrl/Items/$itemId/Download?api_key=${encode(accessToken)}"), emptyMap())
        }
        val downloadUri = DownloadHttpClients.resolveDownloadUri(source.uri.toString())
        val downloadClient = DownloadHttpClients.downloadClient()
        val response = sendMediaRequest(downloadClient, downloadUri, source.headers, existingBytes)
        val status = response.statusCode()
        if (status !in 200..299) {
            val errorBody = response.body().use {
                String(it.readNBytes(16 * 1024), StandardCharsets.UTF_8)
            }
            val fromFileServer = !response.uri().host.equals(downloadUri.host, ignoreCase = true)
            val reason = when {
                isCloudflareBlocked(response.headers(), errorBody) ->
                    "下载请求被 Cloudflare 拦截，请检查网络出口或联系站点管理员"
                status == 401 -> "下载鉴权失败或登录已过期，请重新登录"
                status == 403 && fromFileServer ->
                    "文件服务器拒绝访问此下载链接，请检查资源链接的访问规则"
                status == 403 -> "服务器拒绝下载，请确认账号下载权限及此资源的访问规则"
                status == 404 -> "资源不存在或下载接口不可用"
                else -> "服务器未能提供下载文件"
            }
            val source = if (fromFileServer) "（文件服务器）" else ""
            throw EmbyApiException("下载失败（HTTP $status）$source：$reason", status)
        }

        val contentRange = parseContentRange(response.headers().firstValue("Content-Range").orElse(null))
        if (status == 206 && (contentRange == null || contentRange.start != existingBytes)) {
            response.body().close()
            throw EmbyApiException("服务器返回的续传范围不匹配，已保留本地文件")
        }
        val append = status == 206 && existingBytes > 0L
        if (!append && existingBytes > 0L) {
            // 服务器不支持断点续传，重新下完整文件
            runCatching { Files.deleteIfExists(targetFile) }
        }

        val totalFromItem = item.MediaSources?.firstOrNull()?.Size?.takeIf { it > 0 }
        val contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L)
            .takeIf { it > 0 }
        val totalBytes = contentRange?.total
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
        if (totalBytes != null && downloaded != totalBytes) {
            throw EmbyApiException("媒体传输未完成，已保存 $downloaded / $totalBytes 字节，可继续续传")
        }
        // 其他异常保留本地半成品，供断点续传 / 自动重连
        return targetFile
    }

    fun downloadSubtitle(
        item: BaseItemDto,
        sourceId: String,
        index: Int,
        format: String,
        targetFile: Path,
        control: DownloadControl,
        onProgress: (Long, Long?) -> Unit
    ): Path {
        val itemId = item.Id ?: throw EmbyApiException("资源缺少 ID，无法导出字幕")
        val mediaSourceId = sourceId.takeIf { it.isNotBlank() }
            ?: throw EmbyApiException("缺少媒体源，无法导出字幕")
        val resolvedFormat = normalizeSubtitleFormat(format)
        val source = item.MediaSources.orEmpty().firstOrNull { it.Id == mediaSourceId }
            ?: throw EmbyApiException("字幕对应的媒体源不存在，请刷新媒体列表")
        val stream = source.MediaStreams.orEmpty().firstOrNull { it.Type == "Subtitle" && it.Index == index }
            ?: throw EmbyApiException("字幕轨道不存在，请刷新媒体列表")
        val cache = subtitleCacheFile(item, mediaSourceId, index, resolvedFormat)
        val root = "$baseUrl/Videos/${encode(itemId)}/${encode(mediaSourceId)}/Subtitles/$index"
        val candidates = linkedSetOf<String>()
        val deliveryUrl = stream.DeliveryUrl?.takeIf { it.isNotBlank() }
            ?: resolveSubtitleDeliveryUrl(itemId, mediaSourceId, index, resolvedFormat)
        deliveryUrl?.let { candidates += resolveSubtitleUrl(it).toString() }
        candidates += "$root/Stream.$resolvedFormat"
        candidates += "$root/0/Stream.$resolvedFormat"
        var invalidContent = false
        for (url in candidates) {
            control.check()
            Files.deleteIfExists(targetFile)
            try {
                downloadBinary(url, targetFile, control, "字幕下载失败", onProgress)
                if (!SubtitleFiles.isValid(targetFile, resolvedFormat)) {
                    invalidContent = true
                    Files.deleteIfExists(targetFile)
                    continue
                }
                SubtitleFiles.store(targetFile, cache, control)
                return targetFile
            } catch (ex: SubtitleContentException) {
                invalidContent = true
                Files.deleteIfExists(targetFile)
            } catch (ex: EmbyApiException) {
                if (ex.statusCode != 404) throw ex
            }
        }
        if (SubtitleFiles.restore(cache, targetFile, resolvedFormat, control, onProgress)) return targetFile
        if (invalidContent) throw SubtitleContentException("服务器返回的内容不是有效的 ${resolvedFormat.uppercase()} 字幕，且没有对应字幕缓存")
        throw EmbyApiException("服务器未返回此字幕（HTTP 404），且没有对应字幕缓存；可指定已有本地媒体文件导出", 404)
    }

    fun cacheExtractedSubtitle(item: BaseItemDto, sourceId: String, index: Int, format: String,
                               file: Path, control: DownloadControl) {
        if (format !in setOf("ass", "ssa", "srt", "vtt")) return
        if (!SubtitleFiles.isValid(file, format)) throw SubtitleContentException("提取结果不是有效的字幕")
        SubtitleFiles.store(file, subtitleCacheFile(item, sourceId, index, format), control)
    }

    private fun subtitleCacheFile(item: BaseItemDto, sourceId: String, index: Int, format: String): Path {
        val source = item.MediaSources.orEmpty().firstOrNull { it.Id == sourceId }
            ?: throw EmbyApiException("字幕媒体源不存在")
        val stream = source.MediaStreams.orEmpty().firstOrNull { it.Type == "Subtitle" && it.Index == index }
            ?: throw EmbyApiException("字幕轨道不存在")
        val directory = System.getProperty("embydownload.subtitleCacheDir")?.let { Path.of(it) }
            ?: Path.of(AppSettings.stagingPath, "subtitles")
        return SubtitleFiles.cacheFile(directory, listOf(baseUrl, userId.orEmpty(), item.Id.orEmpty(),
            sourceId, source.Size.toString(), source.Path.orEmpty(), index.toString(),
            stream.Codec.orEmpty(), format), format)
    }

    fun readPlaybackStream(item: BaseItemDto, sourceId: String, control: DownloadControl,
                           reader: (InputStream, Long?) -> Unit) {
        control.check()
        val selected = item.MediaSources.orEmpty().firstOrNull { it.Id == sourceId }
            ?: throw EmbyApiException("字幕媒体源不存在")
        val source = resolvePlaybackSource(item.copy(MediaSources = listOf(selected)))
        val uri = DownloadHttpClients.resolveDownloadUri(source.uri.toString())
        val headers = source.headers.toMutableMap()
        if (sameOrigin(source.uri, URI.create(baseUrl))) {
            accessToken?.let { headers["X-Emby-Token"] = it; headers["X-Emby-Authorization"] = authorizationHeader(it) }
        }
        val response = sendMediaRequest(DownloadHttpClients.downloadClient(), uri, headers, 0)
        response.body().use { input ->
            if (response.statusCode() !in setOf(200, 206)) {
                throw EmbyApiException("无法读取字幕所需播放流（HTTP ${response.statusCode()}）", response.statusCode())
            }
            val range = parseContentRange(response.headers().firstValue("Content-Range").orElse(null))
            if (response.statusCode() == 206 && (range == null || range.start != 0L)) {
                throw EmbyApiException("播放流未从文件起点返回，无法提取字幕")
            }
            val length = if (response.statusCode() == 206) {
                range!!.total ?: throw EmbyApiException("播放流未提供完整长度，无法确认字幕提取完成")
            } else response.headers().firstValueAsLong("Content-Length").orElse(-1L).takeIf { it >= 0 }
            reader(input, length)
        }
    }

    private fun resolveSubtitleDeliveryUrl(itemId: String, sourceId: String, index: Int, format: String): String? {
        val actor = userId ?: throw EmbyApiException("未登录")
        val original = "$baseUrl/Items/${encode(itemId)}/PlaybackInfo?UserId=${encode(actor)}" +
            "&MediaSourceId=${encode(sourceId)}&SubtitleStreamIndex=$index&IsPlayback=false" +
            "&AutoOpenLiveStream=false&EnableDirectPlay=true&EnableDirectStream=true&EnableTranscoding=false"
        val uri = DownloadHttpClients.resolveApiUri(original, AppSettings.proxyConfig.searchNode())
        val body = gson.toJson(mapOf("DeviceProfile" to mapOf(
            "Name" to "Emby Download Subtitles",
            "DirectPlayProfiles" to listOf(mapOf("Type" to "Video")),
            "TranscodingProfiles" to emptyList<Any>(),
            "SubtitleProfiles" to listOf(mapOf("Format" to format, "Method" to "External"))
        )))
        val request = HttpRequest.newBuilder(authorizedGet(uri.toString())) { _, _ -> true }
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build()
        val response = DownloadHttpClients.searchClient().send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() == 404) return null
        if (response.statusCode() !in 200..299) {
            throw EmbyApiException("无法取得字幕播放信息（HTTP ${response.statusCode()}）", response.statusCode())
        }
        val info = gson.fromJson(response.body(), PlaybackInfoResponse::class.java)
        return info.MediaSources.orEmpty().firstOrNull { it.Id == sourceId }?.MediaStreams.orEmpty()
            .firstOrNull { it.Index == index && it.Type == "Subtitle" }?.DeliveryUrl?.takeIf { it.isNotBlank() }
    }

    private fun resolveSubtitleUrl(url: String): URI {
        val supplied = URI.create(url)
        val base = URI.create("$baseUrl/")
        val resolved = when {
            supplied.isAbsolute -> supplied
            supplied.rawPath.startsWith("/emby/", true) || supplied.rawPath.startsWith("/mediabrowser/", true) -> base.resolve(supplied)
            else -> base.resolve(url.removePrefix("/"))
        }
        if (resolved.scheme !in setOf("http", "https") || resolved.host.isNullOrBlank() ||
            resolved.rawUserInfo != null || (base.scheme == "https" && resolved.scheme != "https")) {
            throw EmbyApiException("服务器返回了无效的字幕链接")
        }
        return resolved
    }

    fun downloadArtwork(
        item: BaseItemDto,
        targetFile: Path,
        control: DownloadControl,
        onProgress: (Long, Long?) -> Unit
    ): Path {
        val target = resolveArtworkTarget(item)
            ?: throw EmbyApiException("此项目没有可导出的封面")
        val url = buildString {
            append("$baseUrl/Items/${encode(target.first)}/Images/Primary?maxHeight=1080&quality=90")
            target.second?.takeIf { it.isNotBlank() }?.let { append("&tag=${encode(it)}") }
        }
        return downloadBinary(url, targetFile, control, "封面下载失败", onProgress)
    }

    private fun downloadBinary(
        originalUrl: String,
        targetFile: Path,
        control: DownloadControl,
        errorPrefix: String,
        onProgress: ((Long, Long?) -> Unit)?
    ): Path {
        control.check()
        val token = accessToken ?: throw EmbyApiException("未登录")
        val node = AppSettings.proxyConfig.downloadNode()
        val client = DownloadHttpClients.downloadClient()
        val uri = DownloadHttpClients.resolveApiUri(originalUrl, node)

        Files.createDirectories(targetFile.parent)
        val existingBytes = runCatching {
            if (Files.isRegularFile(targetFile)) Files.size(targetFile) else 0L
        }.getOrDefault(0L).coerceAtLeast(0L)

        val headers = if (sameOrigin(URI.create(originalUrl), URI.create(baseUrl))) mapOf(
            "X-Emby-Token" to token,
            "X-Emby-Authorization" to authorizationHeader(token)
        ) else emptyMap()
        val response = sendMediaRequest(client, uri, headers, existingBytes)
        val status = response.statusCode()
        if (status !in 200..299) {
            response.body().close()
            throw EmbyApiException("$errorPrefix（HTTP $status）", status)
        }
        if (errorPrefix == "字幕下载失败") {
            val type = response.headers().firstValue("Content-Type").orElse("").lowercase()
            if (type.startsWith("video/") || type.startsWith("audio/") || type.contains("json") || type.contains("html")) {
                response.body().close()
                throw SubtitleContentException("服务器返回的内容不是字幕")
            }
        }

        val contentRange = parseContentRange(response.headers().firstValue("Content-Range").orElse(null))
        if (status == 206 && (contentRange == null || contentRange.start != existingBytes)) {
            response.body().close()
            throw EmbyApiException("$errorPrefix：服务器返回的续传范围不匹配，已保留本地文件")
        }
        val append = status == 206 && existingBytes > 0L
        if (!append && existingBytes > 0L) {
            runCatching { Files.deleteIfExists(targetFile) }
        }

        val contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L)
            .takeIf { it > 0 }
        val totalBytes = contentRange?.total
            ?: if (append && contentLength != null) existingBytes + contentLength else contentLength

        var downloaded = if (append) existingBytes else 0L
        onProgress?.invoke(downloaded, totalBytes)

        val openOptions = if (append) {
            arrayOf(StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)
        } else {
            arrayOf(StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
        }

        try {
            response.body().use { input ->
                Files.newOutputStream(targetFile, *openOptions).buffered(256 * 1024).use { output ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        control.check()
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
        if (totalBytes != null && downloaded != totalBytes) {
            throw EmbyApiException("$errorPrefix：内容传输未完成，已保存 $downloaded / $totalBytes 字节，可继续续传")
        }
        return targetFile
    }

    private fun normalizeSubtitleFormat(format: String): String =
        when (format.trim().lowercase()) {
            "ass" -> "ass"
            "ssa" -> "ssa"
            "vtt", "webvtt" -> "vtt"
            "srt", "subrip" -> "srt"
            else -> "srt"
        }

    private fun resolveArtworkTarget(item: BaseItemDto): Pair<String, String?>? {
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
        return null
    }

    private data class MediaRange(val start: Long, val end: Long, val total: Long?)

    private fun parseContentRange(header: String?): MediaRange? {
        val match = Regex("bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)", RegexOption.IGNORE_CASE)
            .matchEntire(header?.trim() ?: return null) ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val end = match.groupValues[2].toLongOrNull() ?: return null
        val total = if (match.groupValues[3] == "*") null else match.groupValues[3].toLongOrNull() ?: return null
        if (end < start || (total != null && end >= total)) return null
        return MediaRange(start, end, total)
    }

    private data class MediaRequest(val uri: URI, val headers: Map<String, String>)

    private fun resolvePlaybackSource(item: BaseItemDto): MediaRequest {
        val actor = userId ?: throw EmbyApiException("未登录")
        val id = item.Id ?: throw EmbyApiException("资源缺少 ID")
        val preferred = item.MediaSources?.firstOrNull()?.Id
        val original = buildString {
            append("$baseUrl/Items/$id/PlaybackInfo?UserId=${encode(actor)}")
            append("&IsPlayback=true&StartTimeTicks=0&AutoOpenLiveStream=false")
            append("&EnableDirectPlay=true&EnableDirectStream=true&EnableTranscoding=false&DirectPlayProtocols=Http")
            preferred?.let { append("&MediaSourceId=${encode(it)}") }
        }
        val uri = DownloadHttpClients.resolveApiUri(original, AppSettings.proxyConfig.searchNode())
        val body = gson.toJson(mapOf("DeviceProfile" to mapOf(
            "Name" to "Emby Download",
            "SupportedMediaTypes" to "Audio,Video",
            "DirectPlayProfiles" to listOf(mapOf("Type" to "Video"), mapOf("Type" to "Audio")),
            "TranscodingProfiles" to emptyList<Any>()
        )))
        val request = HttpRequest.newBuilder(authorizedGet(uri.toString())) { _, _ -> true }
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = DownloadHttpClients.searchClient().send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw EmbyApiException("无法取得播放源（HTTP ${response.statusCode()}）", response.statusCode())
        }
        val info = gson.fromJson(response.body(), PlaybackInfoResponse::class.java)
        if (info.ErrorCode != null) throw EmbyApiException("服务器未允许取得可缓存的播放源")
        val sources = info.MediaSources.orEmpty()
        val source = if (preferred == null) sources.firstOrNull() else sources.firstOrNull { it.Id == preferred }
        source ?: throw EmbyApiException("服务器未返回匹配的播放源，请刷新媒体列表")
        val directPath = source.Path?.takeIf {
            !source.SupportsDirectStream && source.SupportsDirectPlay && source.Protocol.equals("Http", ignoreCase = true) && it.isNotBlank()
        }
        if (source.IsInfiniteStream || source.RequiresOpening || (directPath == null && !source.SupportsDirectStream)) {
            throw EmbyApiException("此资源未提供直接播放媒体流，无法使用直接播放缓存")
        }
        val sourceId = source.Id ?: throw EmbyApiException("播放源缺少媒体版本 ID")
        val session = info.PlaySessionId ?: throw EmbyApiException("服务器未返回播放会话")
        val url = directPath ?: source.DirectStreamUrl?.takeIf { it.isNotBlank() }
            ?: "$baseUrl/${if (item.Type == "Audio") "Audio" else "Videos"}/$id/stream?static=true&MediaSourceId=${encode(sourceId)}&PlaySessionId=${encode(session)}&api_key=${encode(accessToken!!)}"
        var streamUri = URI.create("$baseUrl/").resolve(url)
        if (streamUri.scheme !in setOf("http", "https") || streamUri.host.isNullOrBlank()) throw EmbyApiException("播放源不是 HTTP 媒体地址")
        if (directPath == null) {
            if (!Regex("(?:^|&)PlaySessionId=", RegexOption.IGNORE_CASE).containsMatchIn(streamUri.rawQuery.orEmpty())) {
                streamUri = appendQueryParameter(streamUri, "PlaySessionId", session)
            }
            if (source.AddApiKeyToDirectStreamUrl && !Regex("(?:^|&)api_key=", RegexOption.IGNORE_CASE).containsMatchIn(streamUri.rawQuery.orEmpty())) {
                streamUri = appendQueryParameter(streamUri, "api_key", accessToken!!)
            }
        }
        return MediaRequest(streamUri, source.RequiredHttpHeaders.orEmpty())
    }

    private fun appendQueryParameter(uri: URI, name: String, value: String): URI {
        val address = uri.toString().substringBefore('#')
        return URI.create("$address${if (uri.rawQuery == null) "?" else "&"}${encode(name)}=${encode(value)}")
    }

    private fun sameOrigin(first: URI, second: URI): Boolean {
        fun port(uri: URI) = if (uri.port >= 0) uri.port else if (uri.scheme.equals("https", true)) 443 else 80
        return first.scheme.equals(second.scheme, true) && first.host.equals(second.host, true) && port(first) == port(second)
    }

    private fun sendMediaRequest(client: HttpClient, original: URI, headers: Map<String, String>, offset: Long): HttpResponse<InputStream> {
        var uri = original
        repeat(6) { hop ->
            val builder = HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(60)).GET()
            if (sameOrigin(uri, original)) headers.forEach { (name, value) -> builder.header(name, value) }
            if (offset > 0) builder.setHeader("Range", "bytes=$offset-")
            val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
            if (response.statusCode() !in setOf(301, 302, 303, 307, 308)) return response
            response.body().close()
            if (hop == 5) throw EmbyApiException("媒体链接重定向次数过多")
            val location = response.headers().firstValue("Location").orElseThrow { EmbyApiException("媒体重定向缺少目标地址") }
            val next = uri.resolve(location)
            if (next.scheme !in setOf("http", "https") || (uri.scheme == "https" && next.scheme != "https")) {
                throw EmbyApiException("媒体链接返回了不安全的重定向地址")
            }
            uri = next
        }
        throw EmbyApiException("媒体链接重定向次数过多")
    }

    private fun isCloudflareBlocked(headers: HttpHeaders, body: String): Boolean =
        headers.firstValue("cf-mitigated").orElse("").equals("challenge", ignoreCase = true) ||
            (headers.firstValue("Content-Type").orElse("").startsWith("text/html", ignoreCase = true) &&
                (body.contains("cf-error-details", ignoreCase = true) ||
                    body.contains("/cdn-cgi/challenge-platform/", ignoreCase = true)))

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

    private fun parseItem(request: HttpRequest, errorPrefix: String): BaseItemDto {
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw EmbyApiException("$errorPrefix（HTTP ${response.statusCode()}）", response.statusCode())
        }
        return gson.fromJson(response.body(), BaseItemDto::class.java)
            ?: throw EmbyApiException("$errorPrefix：服务器未返回有效数据")
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
            """Client="${identity.clientName}"""",
            """Device="${encode(identity.deviceName).replace("+", "%20")}"""",
            """DeviceId="${identity.deviceId}"""",
            """Version="${identity.clientVersion}""""
        )
        if (!token.isNullOrBlank()) {
            parts += """Token="$token""""
        }
        return "Emby ${parts.joinToString(", ")}"
    }

    companion object {
        private const val DETAIL_FIELDS =
            "Overview,Path,MediaSources,MediaStreams,CanDownload,Container,ProductionYear,Artists,Album,RunTimeTicks," +
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

class EmbyApiException(message: String, val statusCode: Int? = null) : RuntimeException(message)
