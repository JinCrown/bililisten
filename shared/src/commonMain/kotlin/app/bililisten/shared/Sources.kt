package app.bililisten.shared

import io.ktor.http.Url
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable enum class SourceKind { OWN_FAVORITES, PUBLIC_FAVORITES, UP_COLLECTION, UP_UPLOADS }
@Serializable enum class CollectionKind { SEASON, SERIES }
@Serializable data class SourceRef(
    val kind: SourceKind, val id: Long, val owner: Long,
    val collectionKind: CollectionKind? = null,
) {
    fun checked(): SourceRef {
        require(id > 0 && owner > 0)
        require((kind == SourceKind.UP_COLLECTION) == (collectionKind != null))
        require(kind != SourceKind.UP_UPLOADS || id == owner)
        return this
    }
}

object SourceCodec {
    fun encode(source: SourceRef): String = Json.encodeToString(source.checked())
    fun decode(text: String): SourceRef = Json.decodeFromString<SourceRef>(text).checked()
}

/** Link hints are never trusted for ownership: confirm against the detail response. */
data class SourceLink(val owner: Long?, val id: Long?, val collectionKind: CollectionKind? = null)
object SourceLinks {
    fun inputUrl(input:String):String {
        if(input.length>2048)throw PlatformFailure("分享文字过长")
        return Regex("https://(?:b23\\.tv|space\\.bilibili\\.com|(?:www\\.|m\\.)?bilibili\\.com)/[^\\s<>\"「」]+")
            .find(input)?.value?.trimEnd('。','，',')','）') ?: throw PlatformFailure("请输入 B 站合集的完整分享链接")
    }
    fun parse(input: String): SourceLink? = runCatching {
        val u = Url(input.trim())
        require(u.protocol.name == "https" && u.port == 443 && u.user.isNullOrEmpty() && u.password.isNullOrEmpty())
        val p = u.encodedPath.trim('/').split('/')
        val result = when (u.host) {
            "space.bilibili.com" -> {
                val owner = p.firstOrNull()?.toLongOrNull() ?: error("owner")
                when {
                    p.size == 1 -> SourceLink(owner, null)
                    p.getOrNull(1) == "favlist" -> SourceLink(owner, u.parameters["fid"]?.toLongOrNull() ?: error("fid"))
                    p.size == 3 && p[1] == "lists" -> SourceLink(owner, p[2].toLongOrNull() ?: error("id"), when(u.parameters["type"]) { null, "season" -> CollectionKind.SEASON; "series" -> CollectionKind.SERIES; else -> error("type") })
                    p.drop(1) == listOf("channel", "collectiondetail") -> SourceLink(owner, u.parameters["sid"]?.toLongOrNull() ?: error("sid"), CollectionKind.SEASON)
                    p.drop(1) == listOf("channel", "seriesdetail") -> SourceLink(owner, u.parameters["sid"]?.toLongOrNull() ?: error("sid"), CollectionKind.SERIES)
                    else -> error("unsupported")
                }
            }
            "www.bilibili.com", "m.bilibili.com", "bilibili.com" -> {
                if(p.size==3&&p.take(2)==listOf("medialist","play")) {
                    SourceLink(p[2].toLongOrNull() ?: error("owner"),u.parameters["business_id"]?.toLongOrNull() ?: error("id"),when(u.parameters["business"]){"space_collection"->CollectionKind.SEASON;"space_series"->CollectionKind.SERIES;else->error("type")})
                } else {
                val id = when { p.size == 2 && p[0] == "list" -> p[1]; p.size == 3 && p.take(2) == listOf("medialist", "detail") -> p[2]; else -> error("unsupported") }
                require(id.startsWith("ml")); SourceLink(null, id.drop(2).toLongOrNull() ?: error("id"))
                }
            }
            else -> error("host")
        }
        require(result.owner == null || result.owner > 0)
        require(result.id == null || result.id > 0)
        result
    }.getOrNull()
}

@Serializable data class ContentSource(val ref: SourceRef, val title: String, val ownerName: String, val count: Int, val cover: String = "")
data class SourceListPage(val sources: List<ContentSource>, val page: Int, val hasMore: Boolean, val unsupportedTypes: Set<Int> = emptySet(), val unavailableSeasonIds: Set<Long> = emptySet())
data class SourceContentPage(val source: ContentSource, val items: List<FavoriteItem>, val page: Int, val hasMore: Boolean)

@Serializable data class SourceCheckpoint(val account: String, val source: SourceRef, val ids: Set<String>, val heard: Set<String> = emptySet(), val completeBaseline: Boolean = true)
data class SourceUpdate(val checkpoint: SourceCheckpoint?, val added: Set<String>, val removed: Set<String>)
/** Only a complete successful pagination can replace a baseline. The playing queue is independent. */
object SourceUpdates {
    fun compare(previous: SourceCheckpoint?, account: String, source: SourceRef, ids: List<String>, complete: Boolean): SourceUpdate {
        source.checked(); require(account.isNotBlank())
        require(previous == null || previous.account == account && previous.source == source)
        if (!complete) return SourceUpdate(previous, emptySet(), emptySet())
        val current = ids.toSet()
        return SourceUpdate(SourceCheckpoint(account, source, current, previous?.heard.orEmpty()),
            if (previous == null || !previous.completeBaseline) emptySet() else current - previous.ids,
            if (previous == null || !previous.completeBaseline) emptySet() else previous.ids - current)
    }
}
