package app.bililisten

import android.app.Application
import androidx.room.Room
import app.bililisten.platform.CredentialVault
import app.bililisten.platform.ListenDatabase
import app.bililisten.shared.BiliApi
import app.bililisten.shared.SourceRepository
import app.bililisten.shared.BiliSourceRepository
import app.bililisten.shared.*
import app.bililisten.platform.RoomStores
import app.bililisten.platform.SettingsStore
import app.bililisten.playback.ControllerPlaybackPort
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.channels.Channel
import kotlin.coroutines.resume
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout

@androidx.annotation.OptIn(markerClass=[androidx.media3.common.util.UnstableApi::class])
class ListenApplication : Application() {
    lateinit var vault: CredentialVault; private set
    lateinit var api: BiliApi; private set
    lateinit var sources: SourceRepository; private set
    lateinit var upLibrary:UpLibraryRepository; private set
    lateinit var database: ListenDatabase; private set
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val clock = object : Clock { override fun nowMs() = System.currentTimeMillis() }
    val diagnostics = DiagnosticLog(clock)
    lateinit var governor: RequestGovernor; private set
    lateinit var accounts: AccountRepository; private set
    lateinit var favorites: FavoriteRepository; private set
    lateinit var content: ContentRepository; private set
    lateinit var entitlements: EntitlementRepository; private set
    lateinit var subtitles: SubtitleRepository; private set
    lateinit var lyrics: LyricsRepository; private set
    lateinit var live: LiveRepository; private set
    lateinit var recommendations: RecommendationRepository; private set
    lateinit var searchAssist: SearchAssistRepository; private set
    lateinit var stores: RoomStores; private set
    lateinit var history: LocalHistoryRepository; private set
    lateinit var historySync: RemoteHistorySync; private set
    @Volatile private var historySyncAccount:String?=null
    private val historyReports=Channel<Pair<String,RemoteHistoryItem>>(Channel.UNLIMITED)
    fun historySyncEnabled(account:String)=historySyncAccount==account
    fun syncHistory(row:LocalHistoryEntry) { if(historySyncEnabled(row.account))historyReports.trySend(row.account to RemoteHistoryItem(bvid=row.video.bvid,cid=row.video.cid,page=row.video.part,title=row.title,positionMs=row.positionMs,viewedAt=row.playedAt)) }
    fun syncHistory(row:LiveHistoryEntry) { if(historySyncEnabled(row.account))historyReports.trySend(row.account to RemoteHistoryItem(roomId=row.roomId,title=row.title,viewedAt=row.playedAt)) }
    lateinit var settings: SettingsStore; private set
    lateinit var organizerStore: app.bililisten.platform.RoomOrganizerStore; private set
    lateinit var organizer: Organizer; private set
    val home by lazy { app.bililisten.platform.HomeFileStore(java.io.File(cacheDir,"home-ranking-v2"),covers,clock) }
    val covers by lazy { app.bililisten.platform.CoverStore(this) }
    val widgets by lazy { app.bililisten.widget.WidgetCoordinator(this) }
    val downloads by lazy { app.bililisten.storage.AudioFiles(this) }
    @get:androidx.media3.common.util.UnstableApi
    val automaticAudio by lazy { app.bililisten.storage.AutomaticAudioCache(this) }
    @Volatile var accountKey: String = "guest"
    @Volatile var persistenceError: Boolean = false
    override fun onCreate() {
        super.onCreate()
        vault = CredentialVault(this).apply { restore() }
        // Recover from an interrupted login: WebView must not be an alternate persistent session store.
        app.bililisten.platform.WebSession.clear()
        val client = HttpClient(OkHttp) {
            followRedirects = false
            install(HttpTimeout) { requestTimeoutMillis = 15000; connectTimeoutMillis = 10000; socketTimeoutMillis = 15000 }
            engine { config { cache(null); followRedirects(false); followSslRedirects(false) } }
        }
        governor = RequestGovernor(scope)
        api = BiliApi(client, governor, { vault.generation },clock,
            {bytes->java.security.MessageDigest.getInstance("MD5").digest(bytes).joinToString(""){"%02x".format(it)}},vault::read)
        searchAssist = BiliSearchAssistRepository(api)
        database = Room.databaseBuilder(this, ListenDatabase::class.java, "listening.db").addMigrations(ListenDatabase.MIGRATION_1_2, ListenDatabase.MIGRATION_2_3, ListenDatabase.MIGRATION_3_4,ListenDatabase.MIGRATION_4_5,ListenDatabase.MIGRATION_5_6).build()
        stores = RoomStores(database.listenDao(), java.io.File(noBackupFilesDir, "pending-favorite.json"))
        settings = SettingsStore(java.io.File(filesDir, "datastore/settings.json"), scope, diagnostics)
        accounts = SessionAccountRepository(api, vault) {
            withContext(Dispatchers.Main) {
                withTimeoutOrNull(3000) {
                    suspendCancellableCoroutine<Unit> { continuation ->
                        app.bililisten.platform.WebSession.clear {
                            if (continuation.isActive) continuation.resume(Unit)
                        }
                    }
                }
            }
        }
        favorites = BiliFavoriteRepository(api, accounts, stores, clock) { java.util.UUID.randomUUID().toString() }
        val remoteHistory=app.bililisten.platform.RoomRemoteHistoryStore(database.listenDao())
        history=SyncedHistoryRepository(stores,remoteHistory)
        historySync=RemoteHistorySync(object:RemoteHistoryPort {
            override suspend fun paused()=api.historyPaused()
            override suspend fun page(cursor:HistoryCursor?)=api.historyPage(cursor)
            override suspend fun report(item:RemoteHistoryItem,account:String)=api.reportHistory(item,account)
            override suspend fun delete(key:String?,account:String)=api.deleteHistory(key,account)
        },remoteHistory,accounts,clock)
        scope.launch {
            for((account,item) in historyReports)try{historySync.record(item,account)}catch(e:CancellationException){throw e}catch(_:Exception){diagnostics.record(DiagnosticEvent.STORAGE_FAILED)}
        }
        scope.launch {
            @OptIn(ExperimentalCoroutinesApi::class)
            accounts.session.flatMapLatest{session->remoteHistory.observe(session.stamp.account).map{data->if(data.enabled)session.stamp.account else null}}.collect{historySyncAccount=it}
        }
        accountKey = accounts.session.value.stamp.account
        organizerStore = app.bililisten.platform.RoomOrganizerStore(database.listenDao(),app.bililisten.platform.OrganizerFileStore(java.io.File(noBackupFilesDir, "organizer")))
        organizer = Organizer(object : OrganizerRemote {
            override suspend fun preflight(account: Long) {
                if (favorites.pending(account.toString()) != null) throw PlatformFailure("请先核对上次收藏写入")
            }
            override suspend fun folders(account: Long) = api.folders(account)
            override suspend fun folder(account: Long, id: Long) = api.ownFolder(account, id)
            override suspend fun writeFolder(account: Long, draft: FolderDraft): Long? {
                return api.writeFolder(account, draft)
            }
            override suspend fun video(bvid: String) = api.video(bvid)
            override suspend fun membership(account: Long, aid: Long) = api.folders(account, aid)
            override suspend fun change(account: Long, aid: Long, folder: Long, add: Boolean) = favorites.change(account, aid, folder, add)
        }, accounts, organizerStore)
        content = BiliContentRepository(api, app.bililisten.playback.DeviceAudioSupport::supports)
        upLibrary = BiliUpLibraryRepository(api,clock){bytes->java.security.MessageDigest.getInstance("MD5").digest(bytes).joinToString(""){"%02x".format(it)}}
        sources = BiliSourceRepository(api, accounts, stores, clock,upLibrary)
        entitlements = BiliEntitlementRepository(api, clock, accounts)
        subtitles = BiliSubtitleRepository(api)
        lyrics = LrclibRepository(HttpClient(OkHttp) {
            followRedirects=false
            install(HttpTimeout) { requestTimeoutMillis=20000;connectTimeoutMillis=10000;socketTimeoutMillis=15000 }
            engine { config { cache(null);followRedirects(false);followSslRedirects(false) } }
        })
        live = BiliLiveRepository(api)
        recommendations = BiliRecommendationRepository(api,{
            RecentRecommendationWindow(clock.nowMs())
        },{bytes->java.security.MessageDigest.getInstance("MD5").digest(bytes).joinToString(""){"%02x".format(it)}})
        scope.launch(Dispatchers.Main.immediate) {
            var generation = vault.generation
            var owner = accounts.session.value.stamp.account
            accounts.session.collect { session ->
                if (generation != vault.generation || owner != session.stamp.account) widgets.invalidate()
                generation = vault.generation; owner = session.stamp.account
                accountKey = owner
                widgets.accountChanged(owner)
            }
        }
        scope.launch(Dispatchers.Main.immediate) { settings.settings.collect { widgets.refreshCurrent(force = true) } }
        diagnostics.record(DiagnosticEvent.START)
    }
    @androidx.media3.common.util.UnstableApi
    fun dependencies() = AppDependencies(accounts, favorites, content, entitlements, live, recommendations, sources, stores, history, stores, settings,
        ControllerPlaybackPort(this), BiliJumpPort, clock, diagnostics, { accountKey = it; scope.launch { governor.cancelOtherGenerations(vault.generation) } }, { governor.acknowledge(vault.generation) }, home, subtitles, lyrics, downloads, searchAssist,upLibrary,
        app.bililisten.platform.RoomLibraryPreferences(organizerStore,organizer,accounts),
        app.bililisten.platform.OrganizerFolderCreation(organizer,organizerStore,accounts),historySync,
        VideoEngagementRepository(BiliVideoEngagementPort(api),accounts,stores,clock,{java.util.UUID.randomUUID().toString()}),
        BiliMessageRepository(api))
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        widgets.refreshCurrent(force = true)
    }
}
