package app.bililisten.shared

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

@Serializable enum class FolderAction { CREATE, EDIT, DELETE }
@Serializable data class FolderDraft(val action: FolderAction, val id: Long = 0, val title: String = "", val private: Boolean = true,
    val confirmedTitle: String? = null, val confirmedCount: Int? = null)
@Serializable data class FolderPending(val draft: FolderDraft, val beforeIds: List<Long>, val receipt: Long? = null)
@Serializable enum class OrganizeAction { COPY, MOVE, REMOVE }
@Serializable enum class RowOutcome { READY, RUNNING, CONFIRMED, FAILED, PARTIAL, UNKNOWN, NOT_STARTED }
@Serializable data class OrganizeRow(val bvid: String, val title: String, val aid: Long = 0, val outcome: RowOutcome = RowOutcome.READY, val detail: String = "等待处理")
@Serializable data class OrganizeBatch(val source: Long, val target: Long, val action: OrganizeAction, val rows: List<OrganizeRow>)
@Serializable data class OrganizerData(val pending: FolderPending? = null, val batch: OrganizeBatch? = null,
    val aliases: Map<String, String> = emptyMap(), val orders: Map<Long, List<String>> = emptyMap(), val result: String = "",
    val library:LibraryPreferences = LibraryPreferences())

interface OrganizerStore {
    suspend fun read(account: String): OrganizerData
    suspend fun write(account: String, value: OrganizerData)
}
interface OrganizerRemote {
    suspend fun preflight(account: Long) {}
    suspend fun folders(account: Long): List<FavoriteFolder>
    suspend fun folder(account: Long, id: Long): FavoriteFolder
    /** A single write; null means no usable receipt. Network uncertainty must be reconciled. */
    suspend fun writeFolder(account: Long, draft: FolderDraft): Long?
    suspend fun video(bvid: String): Video
    suspend fun membership(account: Long, aid: Long): List<FavoriteFolder>
    suspend fun change(account: Long, aid: Long, folder: Long, add: Boolean): MutationOutcome
}

/** Durable intent before every write; recovery only reads and never replays a POST. */
class Organizer(private val remote: OrganizerRemote, private val accounts: AccountRepository, private val store: OrganizerStore) {
    private val lock = Mutex()
    /** Serialize a local migration with remote organizing; never replays pending operations. */
    suspend fun <T> coordinateLocalData(block:suspend ()->T):T=lock.withLock{block()}
    private fun session(): SessionStamp = accounts.session.value.let {
        if (it.status != SessionStatus.AUTHENTICATED) throw PlatformFailure("请先验证登录账户")
        it.stamp.also(accounts::requireCurrent)
    }
    private fun check(stamp: SessionStamp) = accounts.requireCurrent(stamp)
    private fun blocked(data: OrganizerData) = data.pending != null || data.batch?.rows?.any { it.outcome in setOf(RowOutcome.RUNNING, RowOutcome.UNKNOWN) } == true
    suspend fun load(): OrganizerData = store.read(session().account)

    suspend fun folder(draft: FolderDraft): OrganizerData = lock.withLock {
        val stamp = session(); val account = stamp.account.toLong(); val old = store.read(stamp.account)
        if (blocked(old)) throw PlatformFailure("请先核对上次未确认的整理结果")
        remote.preflight(account); check(stamp)
        if (draft.action != FolderAction.DELETE && (draft.title.isBlank() || draft.title.length > 20)) throw PlatformFailure("收藏夹名称需要 1 至 20 个字")
        val before = remote.folders(account); check(stamp)
        if (draft.action != FolderAction.CREATE) {
            val target = before.firstOrNull { it.id == draft.id } ?: throw PlatformFailure("只能整理本人的收藏夹")
            if (draft.action == FolderAction.DELETE && (target.attr == null || target.attr and 2 == 0)) throw PlatformFailure("默认收藏夹不能删除")
            if(draft.action==FolderAction.DELETE && (draft.confirmedTitle!=null && target.title!=draft.confirmedTitle || draft.confirmedCount!=null && target.count!=draft.confirmedCount)) throw PlatformFailure("收藏夹信息已变化，请刷新后重新确认删除")
        }
        var saved = old.copy(pending = FolderPending(draft, before.map { it.id }), result = "正在提交并核对")
        store.write(stamp.account, saved); check(stamp)
        try {
            val receipt = remote.writeFolder(account, draft)
            saved = saved.copy(pending = saved.pending!!.copy(receipt = receipt))
            store.write(stamp.account, saved)
        } catch (e: CancellationException) { throw e }
        catch (e: PlatformFailure) {
            // Explicit platform rejection is final, transport/server ambiguity is not.
            if (e.code != null && e.code !in 500..599) {
                saved = saved.copy(pending = null, result = "平台拒绝：${e.category}（${e.code}）")
                store.write(stamp.account, saved); check(stamp); return@withLock saved
            }
        } catch (_: Exception) { /* Leave durable intent for read-back. */ }
        check(stamp)
        reconcileFolder(stamp)
    }

    private suspend fun reconcileFolder(stamp: SessionStamp): OrganizerData {
        val saved = store.read(stamp.account); val pending = saved.pending ?: return saved
        return try {
            val list = remote.folders(stamp.account.toLong()); check(stamp)
            val draft = pending.draft
            val candidates = if (draft.action == FolderAction.CREATE) list.filter { it.id !in pending.beforeIds && it.title == draft.title && it.attr != null && (it.attr and 1 == 1) == draft.private }
                else list.filter { it.id == draft.id }
            val confirmed = when (draft.action) {
                FolderAction.CREATE -> candidates.size == 1 && (pending.receipt == null || candidates.single().id == pending.receipt)
                FolderAction.EDIT -> candidates.singleOrNull()?.let { it.title == draft.title && it.attr != null && (it.attr and 1 == 1) == draft.private } == true
                FolderAction.DELETE -> candidates.isEmpty()
            }
            saved.copy(pending = if (confirmed) null else pending, result = if (confirmed) "已回读确认${when(draft.action){FolderAction.CREATE->"创建";FolderAction.EDIT->"修改";FolderAction.DELETE->"删除"}}成功" else "结果尚未确认，请稍后再次核对；不会重复提交").also { store.write(stamp.account, it) }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { saved.copy(result = "网络或会话变化，结果待核对；不会重复提交").also { store.write(stamp.account, it) } }
    }

    suspend fun batch(source: Long, target: Long, action: OrganizeAction, items: List<FavoriteItem>): OrganizerData = lock.withLock {
        require(items.isNotEmpty() && items.size <= 50 && items.map { it.bvid }.distinct().size == items.size)
        val stamp = session(); val old = store.read(stamp.account)
        if (blocked(old)) throw PlatformFailure("请先核对上次未确认的整理结果")
        remote.preflight(stamp.account.toLong()); check(stamp)
        val owned = remote.folders(stamp.account.toLong()).map { it.id }; check(stamp)
        require(source in owned && (action == OrganizeAction.REMOVE || target in owned && source != target))
        var data = old.copy(batch = OrganizeBatch(source, target, action, items.map { OrganizeRow(it.bvid, it.title) }), result = "正在逐条整理")
        store.write(stamp.account, data)
        var stop = false
        for (index in items.indices) {
            check(stamp)
            var row = data.batch!!.rows[index]
            if (stop) row = row.copy(outcome = RowOutcome.NOT_STARTED, detail = "前项未确认，尚未执行")
            else {
                try {
                    val video = remote.video(row.bvid); check(stamp)
                    row = row.copy(aid = video.aid, outcome = RowOutcome.RUNNING, detail = "结果待核对")
                    data = saveRow(stamp.account, data, index, row)
                    val memberships = remote.membership(stamp.account.toLong(), row.aid); check(stamp)
                    val sourcePresent = memberships.firstOrNull { it.id == source }?.contains ?: throw PlatformFailure("无法核对来源收藏关系")
                    if (!sourcePresent) throw PlatformFailure("视频已不在来源收藏夹")
                    if (action != OrganizeAction.REMOVE) {
                        check(stamp)
                        val result = remote.change(stamp.account.toLong(), row.aid, target, true); check(stamp)
                        if (result == MutationOutcome.UNKNOWN) throw PlatformFailure("目标收藏结果待核对")
                    }
                    if (action != OrganizeAction.COPY) {
                        check(stamp)
                        val result = remote.change(stamp.account.toLong(), row.aid, source, false); check(stamp)
                        if (result == MutationOutcome.UNKNOWN) throw PlatformFailure("来源移除结果待核对")
                    }
                    row = verifyRow(stamp, data.batch!!, row)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    row = if (row.aid == 0L) row.copy(outcome = RowOutcome.FAILED, detail = (e as? PlatformFailure)?.category ?: "视频读取失败，未写入")
                    else try { verifyRow(stamp, data.batch!!, row) } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { row.copy(outcome = RowOutcome.UNKNOWN, detail = "结果待核对，未重试写入") }
                    if (row.outcome == RowOutcome.UNKNOWN) stop = true
                }
            }
            data = saveRow(stamp.account, data, index, row)
        }
        data.copy(result = "本批处理结束；逐条结果如下，不支持整体撤销").also { store.write(stamp.account, it) }
    }

    private suspend fun verifyRow(stamp: SessionStamp, batch: OrganizeBatch, row: OrganizeRow): OrganizeRow {
        check(stamp)
        val memberships = remote.membership(stamp.account.toLong(), row.aid); check(stamp)
        val source = memberships.firstOrNull { it.id == batch.source }?.contains ?: throw PlatformFailure("来源关系未知")
        val target = if (batch.action == OrganizeAction.REMOVE) false else memberships.firstOrNull { it.id == batch.target }?.contains ?: throw PlatformFailure("目标关系未知")
        val ok = when (batch.action) { OrganizeAction.COPY -> target; OrganizeAction.MOVE -> target && !source; OrganizeAction.REMOVE -> !source }
        return row.copy(outcome = if (ok) RowOutcome.CONFIRMED else if (batch.action == OrganizeAction.MOVE && target && source) RowOutcome.PARTIAL else RowOutcome.FAILED,
            detail = if (ok) "已回读确认成功" else if (batch.action == OrganizeAction.MOVE && target && source) "目标已收藏，来源仍保留；可重新选择后整理" else "未达到预期；已核对当前关系，未自动重试")
    }
    private suspend fun saveRow(account: String, data: OrganizerData, index: Int, row: OrganizeRow): OrganizerData =
        data.copy(batch = data.batch!!.copy(rows = data.batch.rows.mapIndexed { i, r -> if (i == index) row else r })).also { store.write(account, it) }

    suspend fun reconcile(): OrganizerData = lock.withLock {
        val stamp = session(); var data = reconcileFolder(stamp)
        for ((index, row) in data.batch?.rows.orEmpty().withIndex()) {
            if (row.outcome !in setOf(RowOutcome.RUNNING, RowOutcome.UNKNOWN, RowOutcome.READY)) continue
            val next = if (row.aid == 0L) row.copy(outcome = RowOutcome.NOT_STARTED, detail = "上次中断，尚未写入") else
                try { verifyRow(stamp, data.batch!!, row) } catch (e: CancellationException) { throw e }
                catch (_: Exception) { row.copy(outcome = RowOutcome.UNKNOWN, detail = "暂时无法核对") }
            data = saveRow(stamp.account, data, index, next)
        }
        check(stamp); data
    }
    suspend fun alias(bvid: String, text: String): OrganizerData = lock.withLock {
        require(Bvid.parse(bvid) == bvid && text.length <= 80)
        val stamp = session(); val old = store.read(stamp.account)
        old.copy(aliases = if (text.isBlank()) old.aliases - bvid else old.aliases + (bvid to text.trim())).also { check(stamp); store.write(stamp.account, it) }
    }
    suspend fun order(folder: Long, bvids: List<String>): OrganizerData = lock.withLock {
        require(bvids.size <= 10000 && bvids.distinct().size == bvids.size && bvids.all { Bvid.parse(it) == it })
        val stamp = session(); val old = store.read(stamp.account)
        old.copy(orders = old.orders + (folder to bvids)).also { check(stamp); store.write(stamp.account, it) }
    }
}

object LocalFavoriteOrder {
    fun apply(items: List<FavoriteItem>, order: List<String>): List<FavoriteItem> {
        val rank = order.withIndex().associate { it.value to it.index }
        return items.sortedBy { rank[it.bvid] ?: Int.MAX_VALUE }
    }
}
