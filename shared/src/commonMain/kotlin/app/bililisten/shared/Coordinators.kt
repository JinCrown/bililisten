package app.bililisten.shared

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException

class SessionAccountRepository(private val api: BiliApi, private val vault: CredentialStore, private val clearWebSession: suspend () -> Unit = {}) : AccountRepository {
    private val gate = Mutex()
    // Only a locally encrypted, previously validated credential may identify offline history.
    // This does not authenticate playback or remote writes until verify succeeds.
    private val localAccount = vault.read()?.split(';')?.map { it.trim() }?.firstOrNull { it.substringBefore('=') == "DedeUserID" }
        ?.substringAfter('=')?.toLongOrNull()?.takeIf { it > 0 }?.toString() ?: "guest"
    private val mutable = MutableStateFlow(AccountSession(SessionStamp(localAccount, vault.generation), SessionStatus.UNVERIFIED))
    override val session = mutable.asStateFlow()
    private fun publish(account: Account?, status: SessionStatus) {
        val key = account?.id?.toString() ?: if (status == SessionStatus.UNVERIFIED) mutable.value.stamp.account else "guest"
        mutable.value = AccountSession(SessionStamp(key, mutable.value.stamp.generation + 1), status, account)
    }
    override suspend fun verify(): Account? = gate.withLock {
        publish(mutable.value.account, SessionStatus.UNVERIFIED)
        if (vault.read() == null) { publish(null, SessionStatus.GUEST); return@withLock null }
        try { api.account().also { publish(it, SessionStatus.AUTHENTICATED) } }
        catch (e: PlatformFailure) {
            if (e.code == -101) { vault.clear(); publish(null, SessionStatus.GUEST); clearWebSession() }
            throw e
        }
    }
    override suspend fun accept(cookie: String): Account = gate.withLock {
        val account = api.account(cookie)
        vault.save(cookie); publish(account, SessionStatus.AUTHENTICATED); clearWebSession(); account
    }
    override suspend fun logout() = gate.withLock { vault.clear(); publish(null, SessionStatus.GUEST); clearWebSession() }
    override fun requireCurrent(stamp: SessionStamp) {
        if (session.value.stamp != stamp || session.value.status == SessionStatus.UNVERIFIED) throw PlatformFailure("账号会话已变化，旧结果已丢弃")
    }
}

class ResumeCoordinator(private val store: PlaybackStateStore, private val accounts: AccountRepository, private val sources: SourceRepository, private val clock: Clock) {
    suspend fun prepare(): ResumeContext? {
        val current = accounts.session.value
        if (current.status == SessionStatus.UNVERIFIED) throw PlatformFailure("当前账号尚未联网验证；本机历史和进度保留，请联网后重试账号检查")
        accounts.requireCurrent(current.stamp)
        val saved = store.load(current.stamp.account)?.checked() ?: return null
        require(saved.queue.account == current.stamp.account && saved.savedAt <= clock.nowMs() + 60000)
        for (source in saved.queue.entries.mapNotNull { it.resolvedSource(saved.queue.account) }.distinct()) {
            sources.content(source, current.account?.id)
            accounts.requireCurrent(current.stamp)
        }
        accounts.requireCurrent(current.stamp)
        return ResumeContext(current.stamp, saved.copy(pauseReason = PauseReason.RESTORED), autoplay = false)
    }
}

/** Partial pagination never replaces the last complete update baseline. */
class CollectionCoordinator(private val store: CollectionStore, private val accounts: AccountRepository, private val clock: Clock) {
    suspend fun save(page: SourceContentPage, stamp: SessionStamp) {
        accounts.requireCurrent(stamp)
        val previous = store.collection(stamp.account, page.source.ref)
        val revision = maxOf(clock.nowMs(), (previous?.revision ?: 0) + 1)
        accounts.requireCurrent(stamp)
        store.saveCollection(CollectionSnapshot(stamp.account, page.source.ref, page.items.map { ContentItem(it.bvid, it.title) }, !page.hasMore, clock.nowMs(), revision,
            page.source.title, page.source.ownerName, page.source.count, previous?.bookmarked ?: false,page.source.cover.ifBlank{previous?.cover.orEmpty()}))
        val prior = store.updateCheckpoint(stamp.account, page.source.ref)
        val update = SourceUpdates.compare(prior, stamp.account, page.source.ref, page.items.map { it.bvid }, !page.hasMore)
        accounts.requireCurrent(stamp)
        if (!page.hasMore) update.checkpoint?.let { store.saveUpdate(it) }
    }
}

/** Mutation journal is durable before POST. Reconciliation is read-only, including after cancellation. */
class BiliFavoriteRepository(private val api: BiliApi, private val accounts: AccountRepository, private val store: CollectionStore, private val clock: Clock, private val newId: () -> String) : FavoriteRepository {
    private val writes = Mutex()
    override suspend fun folders(account: Long, aid: Long?) = api.folders(account, aid)
    override suspend fun page(folder: Long, page: Int) = api.favorites(folder, page)
    override suspend fun pending(account: String) = store.mutation(account)
    override suspend fun change(account: Long, aid: Long, folder: Long, add: Boolean): MutationOutcome = writes.withLock {
        val stamp = accounts.session.value.stamp
        accounts.requireCurrent(stamp)
        if (stamp.account != account.toString()) throw PlatformFailure("只能操作当前账号")
        if (store.mutation(stamp.account) != null) throw PlatformFailure("上次收藏操作尚待核对")
        store.saveMutation(MutationRecord(newId(), stamp.account, aid, folder, add, clock.nowMs()))
        accounts.requireCurrent(stamp)
        val result = try { api.changeFavorite(account, aid, folder, add) }
        catch (failure: PlatformFailure) {
            if(failure.code != null && failure.code !in 500..599) store.removeMutation(stamp.account)
            throw failure
        }
        accounts.requireCurrent(stamp)
        if (result != MutationOutcome.UNKNOWN) store.removeMutation(stamp.account)
        result
    }
    override suspend fun reconcile(account: Long): FavoriteMembership? = writes.withLock {
        val stamp = accounts.session.value.stamp
        accounts.requireCurrent(stamp); require(stamp.account == account.toString())
        val record = store.mutation(stamp.account) ?: return@withLock null
        if (record.engagement != null) throw PlatformFailure("待核对的是点赞或投币，请在视频详情中核对")
        if (record.followSource != null) throw PlatformFailure("待核对的是合集追更，请在合集操作中核对")
        val target = api.folders(account, record.aid).firstOrNull { it.id == record.folder }
        if (target?.contains == null) throw PlatformFailure("收藏关系仍无法核对")
        accounts.requireCurrent(stamp)
        store.removeMutation(stamp.account)
        FavoriteMembership(stamp.account, record.folder, record.aid, target.contains)
    }
}

class BiliContentRepository(private val api: BiliApi, private val audioSupported: (AudioTrack) -> Boolean = { true }) : ContentRepository {
    override suspend fun resolve(input: String) = api.resolveVideoInput(input)
    override suspend fun followingCount() = api.followingCount()
    override suspend fun video(bvid: String) = api.video(bvid)
    override suspend fun search(keyword: String, page: Int) = api.search(keyword, page)
    override suspend fun audio(bvid: String, cid: Long) = api.audio(bvid, cid, audioSupported)
}
class BiliLiveRepository(private val api: BiliApi) : LiveRepository {
    override suspend fun room(id: Long) = api.liveRoom(id)
    override suspend fun streams(id: Long) = api.liveStreams(id)
    override suspend fun ranking()=api.liveRanking()
    override suspend fun resolve(input:String):LiveRoom {
        LiveInput.room(input)?.let{return room(it)}
        val target=api.resolveSharedInput(input) as? SharedTarget.Live ?: throw PlatformFailure("这不是直播链接，请输入房间号或 B站直播链接")
        return room(target.room)
    }
}
class BiliRecommendationRepository(private val api: BiliApi,
    private val window:()->RecentRecommendationWindow = {throw PlatformFailure("缺少推荐时间窗口")},
    private val md5:(ByteArray)->String = {throw PlatformFailure("搜索签名暂不可用")}) : RecommendationRepository {
    private val music=PopularMusicFeed({round,excluded,ready->api.popularMusicBatch(window(),round,excluded,ready,md5)},
        {window().nowMs},{api.sessionGeneration})
    private val accountHome=AccountHomeFeed({cursor->api.accountHomePage(cursor,window(),md5)},{api.sessionGeneration})
    override suspend fun accountHome(previous:Set<String>,ready:suspend (List<PopularMusic>)->Unit)=accountHome.load(previous,ready)
    override suspend fun accountCreators(previous:Set<Long>)=accountHome.creators(previous)
    private val creatorGate=Mutex()
    private var creatorGeneration:Long?=null
    private var creatorsAt=0L
    private var creatorPool=emptyList<UpProfile>()
    override suspend fun creators()=creators(emptySet())
    override suspend fun creators(previous:Set<Long>)=creatorGate.withLock {
        val stamp=api.sessionGeneration;val current=window()
        if(creatorGeneration!=stamp || creatorPool.isEmpty() || current.nowMs-creatorsAt !in 0..6L*3600000) {
            val rows=api.homeCreatorCandidates(current,md5)
            if(api.sessionGeneration!=stamp)throw CancellationException("Account changed")
            creatorPool=rows;creatorsAt=current.nowMs;creatorGeneration=stamp
        }
        selectRecommendedCreators(creatorPool,previous)
    }
    override suspend fun popularMusic()=popularMusic(emptySet()){}
    override suspend fun popularMusic(previous:Set<String>,ready:suspend (List<PopularMusic>)->Unit)=music.load(previous,ready)
    override suspend fun candidates() = candidates(HomeCategory.ALL)
    override suspend fun candidates(category:HomeCategory)=api.homeRecommendations(category,window(),md5)
}
class BiliEntitlementRepository(private val api: BiliApi, private val clock: Clock, private val accounts: AccountRepository? = null) : EntitlementRepository {
    override suspend fun probe(bvid: String, cid: Long, extended: Boolean) = api.audioProbe(bvid, cid, extended)
    override suspend fun inspect(video: VideoRef): EntitlementSnapshot {
        val stamp = accounts?.session?.value?.stamp
        val result = probe(video.bvid, video.cid, true)
        if (stamp != null) accounts?.requireCurrent(stamp)
        return EntitlementSnapshot(video, result.tracks.map { MediaStreamDescriptor(it.id, it.mime, it.codec, it.bandwidth, StreamKind.AUDIO_ONLY) },
            if (result.tracks.isEmpty()) EntitlementStatus.UNKNOWN else EntitlementStatus.AVAILABLE, clock.nowMs(), stamp)
    }
}
