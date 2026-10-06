package app.bililisten

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.bililisten.shared.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class OrganizerScreenState(val account: String = "guest", val authenticated: Boolean = false, val busy: Boolean = false,
    val folders: List<FavoriteFolder> = emptyList(), val folder: Long? = null, val rows: List<FavoriteItem> = emptyList(),
    val query: String = "", val scope: Int = 0, val sort: Int = 0, val tid: Int = 0, val page: Int = 0,
    val hasMore: Boolean = false, val searched: Boolean = false, val selected: Set<String> = emptySet(),
    val data: OrganizerData = OrganizerData(), val error: String? = null, val membership: String? = null)

class OrganizerViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as ListenApplication
    private val mutable = MutableStateFlow(OrganizerScreenState())
    val state: StateFlow<OrganizerScreenState> = mutable.asStateFlow()
    init { viewModelScope.launch {
        app.accounts.session.collect { session ->
            if (mutable.value.account != session.stamp.account) mutable.value = OrganizerScreenState(account = session.stamp.account)
            mutable.value = mutable.value.copy(authenticated = session.status == SessionStatus.AUTHENTICATED)
        }
    } }
    private fun run(action: suspend (SessionStamp) -> Unit) {
        if (mutable.value.busy) return
        val stamp = app.accounts.session.value.stamp
        mutable.value = mutable.value.copy(busy = true, error = null)
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { app.accounts.requireCurrent(stamp); action(stamp) } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (app.accounts.session.value.stamp.account == stamp.account) mutable.update { it.copy(error = (e as? PlatformFailure)?.category ?: "操作未完成，请检查网络或重新验证账号") } }
            finally {
                if (app.accounts.session.value.stamp.account == stamp.account) {
                    val persisted = try { app.organizerStore.read(stamp.account) } catch (_: Exception) { null }
                    mutable.update { it.copy(busy = false, data = persisted ?: it.data) }
                }
            }
        }
    }
    private fun update(stamp: SessionStamp, action: (OrganizerScreenState) -> OrganizerScreenState) { app.accounts.requireCurrent(stamp); mutable.update(action) }
    fun refresh() = run { stamp ->
        val folders = app.api.folders(stamp.account.toLong()); val data = app.organizer.load()
        update(stamp) { it.copy(folders = folders, data = data, folder = it.folder?.takeIf { id -> folders.any { f -> f.id == id } } ?: folders.firstOrNull()?.id) }
    }
    fun filters(query: String = state.value.query, scope: Int = state.value.scope, sort: Int = state.value.sort, tid: Int = state.value.tid, folder: Long? = state.value.folder) {
        if (state.value.busy) return
        mutable.update { it.copy(query = query.take(100), scope = scope, sort = sort, tid = tid, folder = folder, rows = emptyList(), selected = emptySet(), page = 0, hasMore = false, searched = false) }
    }
    fun search(more: Boolean = false) = run { stamp ->
        val before = state.value; val page = if (more) before.page + 1 else 1
        if (more && !before.hasMore) return@run
        val rows: List<FavoriteItem>; val hasMore: Boolean
        if (before.scope == 2) {
            if (before.query.isBlank()) throw PlatformFailure("在线查找请输入关键词")
            val result = app.api.search(before.query.trim(), page, listOf("totalrank", "pubdate", "click")[before.sort.coerceAtMost(2)], before.tid)
            rows = result.items; hasMore = result.hasMore
        } else {
            val folder = before.folder ?: throw PlatformFailure("请先选择收藏夹")
            if (before.scope == 1 && before.query.isBlank()) throw PlatformFailure("跨收藏夹查找请输入关键词")
            val result = app.api.favorites(folder, page, before.query.trim(), before.scope == 1, listOf("mtime", "pubtime", "view", "mtime")[before.sort], before.tid)
            rows = result.items; hasMore = result.hasMore
        }
        val combined = ((if (more) before.rows else emptyList()) + rows).distinctBy { it.bvid }
        update(stamp) { it.copy(rows = if (before.sort == 3 && before.scope == 0) LocalFavoriteOrder.apply(combined, it.data.orders[before.folder].orEmpty()) else combined,
            page = page, hasMore = hasMore, searched = true, selected = if(more) it.selected else emptySet()) }
    }
    fun select(bvid: String) { if (!state.value.busy) mutable.update { it.copy(selected = if (bvid in it.selected) it.selected - bvid else (it.selected + bvid).take(50).toSet()) } }
    fun folder(draft: FolderDraft) = run { stamp ->
        val data = app.organizer.folder(draft); val folders = app.api.folders(stamp.account.toLong())
        update(stamp) { it.copy(data = data, folders = folders, folder = it.folder?.takeIf { id -> folders.any { f -> f.id == id } } ?: folders.firstOrNull()?.id, rows = emptyList(), selected = emptySet(), searched = false) }
    }
    fun batch(target: Long, action: OrganizeAction) = run { stamp ->
        val before = state.value
        if (before.scope != 0) throw PlatformFailure("请先进入一个收藏夹后整理")
        val data = app.organizer.batch(requireNotNull(before.folder), target, action, before.rows.filter { it.bvid in before.selected })
        update(stamp) { it.copy(data = data, selected = emptySet()) }
    }
    fun reconcile() = run { stamp ->
        // Existing per-item journal may have survived a lost response; both paths only read.
        if (app.favorites.pending(stamp.account) != null) app.favorites.reconcile(stamp.account.toLong())
        val data = app.organizer.reconcile(); update(stamp) { it.copy(data = data) }
    }
    fun alias(bvid: String, text: String) = run { stamp -> val data = app.organizer.alias(bvid, text); update(stamp) { it.copy(data = data) } }
    fun moveLocal(bvid: String, delta: Int) = run { stamp ->
        val before = state.value; require(before.scope == 0 && before.sort == 3)
        val list = before.rows.toMutableList(); val from = list.indexOfFirst { it.bvid == bvid }; val to = (from + delta).coerceIn(0, list.lastIndex)
        list.add(to, list.removeAt(from))
        val ids = list.map { it.bvid }; val order = ids + before.data.orders[before.folder].orEmpty().filter { it !in ids }
        val data = app.organizer.order(requireNotNull(before.folder), order); update(stamp) { it.copy(data = data, rows = list) }
    }
    fun memberships(bvid: String) = run { stamp ->
        val aid = app.api.video(bvid).aid; val folders = app.api.folders(stamp.account.toLong(), aid)
        update(stamp) { it.copy(membership = folders.filter { f -> f.contains == true }.joinToString("、") { f -> f.title }.ifEmpty { "未收藏到本人的收藏夹" }) }
    }
    fun dismissMembership() { mutable.update { it.copy(membership = null) } }
}
