package app.bililisten

import app.bililisten.shared.*

/** ViewModel construction takes ports; tests can replace all storage/network/playback dependencies. */
data class AppDependencies(
    val accounts: AccountRepository, val favorites: FavoriteRepository, val content: ContentRepository,
    val entitlements: EntitlementRepository, val live: LiveRepository, val recommendations: RecommendationRepository,
    val sources: SourceRepository, val collections: CollectionStore, val history: LocalHistoryRepository,
    val snapshots: PlaybackStateStore, val settings: SettingsRepository, val playback: PlaybackPort,
    val jump: JumpPort, val clock: Clock, val diagnostics: DiagnosticLog,
    val onAccountConfirmed: (String) -> Unit = {}, val retryAccount: suspend () -> Unit = {},
    val home: HomeStore? = null,
    val subtitles: SubtitleRepository? = null,
    val lyrics: LyricsRepository? = null,
    val downloads: app.bililisten.storage.AudioFiles? = null,
    val searchAssist: SearchAssistRepository? = null,
    val upLibrary:UpLibraryRepository?=null,
    val library:LibraryPreferenceRepository?=null,
    val folderCreation:FolderCreationRepository?=null,
    val historySync:RemoteHistorySync?=null,
    val engagement:VideoEngagementRepository?=null,
    val messages:MessageRepository?=null,
)
