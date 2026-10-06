package app.bililisten.shared

/** Business port: pages contain confirmed identities, never Android or transport objects. */
interface SourceRepository {
    suspend fun collectionLink(input:String):SourceLink = SourceLinks.parse(SourceLinks.inputUrl(input))?.takeIf{it.collectionKind==CollectionKind.SEASON}
        ?: throw PlatformFailure("请输入 B 站合集的完整分享链接")
    suspend fun isFollowed(account: Long, source: SourceRef): Boolean { throw UnsupportedOperationException() }
    suspend fun follow(account: Long, source: SourceRef, follow: Boolean): MutationOutcome { throw UnsupportedOperationException() }
    suspend fun reconcileFollow(account: Long): Boolean? { throw UnsupportedOperationException() }
    suspend fun publicFolders(owner: Long, page: Int = 1): SourceListPage
    suspend fun followed(account: Long, page: Int = 1): SourceListPage
    suspend fun content(source: SourceRef, account: Long?, page: Int = 1): SourceContentPage
    suspend fun resolve(link: SourceLink, account: Long?): SourceContentPage
}

class BiliSourceRepository(private val api: BiliApi, private val accounts: AccountRepository? = null, private val store: CollectionStore? = null, private val clock: Clock? = null, private val uploads:UpLibraryRepository?=null) : SourceRepository {
    override suspend fun collectionLink(input:String)=api.resolveCollectionInput(input)
    private val writes = kotlinx.coroutines.sync.Mutex()
    override suspend fun isFollowed(account: Long, source: SourceRef) = api.seasonFollowed(account, source)
    override suspend fun follow(account: Long, source: SourceRef, follow: Boolean): MutationOutcome {
        writes.lock()
        try {
            val sessions = requireNotNull(accounts); val journal = requireNotNull(store)
            val stamp = sessions.session.value.stamp
            sessions.requireCurrent(stamp)
            if (stamp.account != account.toString()) throw PlatformFailure("只能操作当前账号")
            if (journal.mutation(stamp.account) != null) throw PlatformFailure("上次操作尚待核对")
            if (source.collectionKind != CollectionKind.SEASON) throw PlatformFailure("此来源不支持合集追更")
            journal.saveMutation(MutationRecord("follow-${requireNotNull(clock).nowMs()}-${kotlin.random.Random.nextLong()}", stamp.account, 0, 0, follow, clock.nowMs(), followSource = source))
            sessions.requireCurrent(stamp)
            val result = api.changeSeasonFollow(account, source, follow)
            sessions.requireCurrent(stamp)
            if (result != MutationOutcome.UNKNOWN) journal.removeMutation(stamp.account)
            return result
        } finally { writes.unlock() }
    }
    override suspend fun reconcileFollow(account: Long): Boolean? {
        writes.lock()
        try {
            val sessions = requireNotNull(accounts); val journal = requireNotNull(store)
            val stamp = sessions.session.value.stamp; sessions.requireCurrent(stamp)
            if (stamp.account != account.toString()) throw PlatformFailure("只能操作当前账号")
            val record = journal.mutation(stamp.account) ?: return null
            val source = record.followSource ?: throw PlatformFailure("待核对的是视频收藏")
            val followed = api.seasonFollowed(account, source)
            sessions.requireCurrent(stamp); journal.removeMutation(stamp.account)
            return followed
        } finally { writes.unlock() }
    }
    override suspend fun publicFolders(owner: Long, page: Int) = api.publicFolders(owner, page)
    override suspend fun followed(account: Long, page: Int) = api.followedSources(account, page)
    override suspend fun content(source: SourceRef, account: Long?, page: Int): SourceContentPage {
        source.checked()
        if (source.kind == SourceKind.OWN_FAVORITES && source.owner != account) throw PlatformFailure("来源账号不匹配")
        if(source.kind==SourceKind.UP_UPLOADS)return requireNotNull(uploads).uploads(source.owner,page)
        return when (source.collectionKind) {
            CollectionKind.SEASON -> api.seasonSource(source.id, page, source.owner)
            CollectionKind.SERIES -> api.seriesSource(source.id, source.owner, page)
            null -> api.favoriteSource(source.id, page, account, source.owner)
        }
    }
    override suspend fun resolve(link: SourceLink, account: Long?): SourceContentPage {
        val id = link.id ?: throw PlatformFailure("请选择具体来源")
        return when (link.collectionKind) {
            CollectionKind.SEASON -> api.seasonSource(id, 1, link.owner)
            CollectionKind.SERIES -> api.seriesSource(id, link.owner ?: throw PlatformFailure("缺少来源所有者"))
            null -> api.favoriteSource(id, account = account, expectedOwner = link.owner)
        }
    }
}
