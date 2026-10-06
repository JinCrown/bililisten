package app.bililisten

import app.bililisten.shared.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class M3ViewModelTest {
    private val bv = "BV1xx411c7mD"
    private class Fixture {
        var now = 100000L
        var verifyDelay = 0L
        var recommendationDelay = 0L
        var verifyCalls = 0
        var recommendationCalls = 0
        var musicCalls = 0
        var musicAction: suspend ()->List<PopularMusic> = { emptyList() }
        var accountHomeAction:suspend (Set<String>,suspend (List<PopularMusic>)->Unit)->List<PopularMusic> = {_,_->emptyList()}
        var accountCreatorAction:suspend (Set<Long>)->List<UpProfile> = {emptyList()}
        var musicBatchAction: (suspend (Set<String>,suspend (List<PopularMusic>)->Unit)->List<PopularMusic>)? = null
        var creatorAction: (suspend (Set<Long>)->List<UpProfile>)? = null
        var retryCalls = 0
        var categoryAction: suspend (HomeCategory)->List<Recommendation> = { category->delay(recommendationDelay);listOf(Recommendation("BV1xx411c7mD",category.label,null)) }
        val events = mutableListOf<String>()
        var sourceCalls = 0
        var videoCalls = 0
        var audioCalls = 0
        var liveReads=0
        var liveRankAction:suspend ()->List<LiveRanking> = {listOf(LiveRanking(77,8,"现场","主播"))}
        var liveRoomAction:suspend (Long)->LiveRoom={LiveRoom(it,77,8,1,"现场","主播")}
        var liveStreamAction:suspend (Long)->List<LiveStream> = {listOf(LiveStream("https://fixture.bilivideo.com/live.flv","flv","avc",80))}
        var liveConsent:Boolean?=null
        var lastSnapshot: ResumeSnapshot? = null
        var writes = 0
        var prunes = 0
        var folderReads = 0
        var member = false
        var failPage = false
        var memberAccount: Account? = Account(7,"fixture",Membership.ORDINARY)
        var searchAction: suspend (String,Int)->SearchPage = { q,p -> SearchPage(listOf(FavoriteItem("BV1xx411c7mD",q)),p,2,2) }
        val video = Video("BV1xx411c7mD",1,"fixture",listOf(VideoPart(1,1,"P1"),VideoPart(2,2,"P2")))
        val source = SourceRef(SourceKind.OWN_FAVORITES,9,7)
        var sourceAction: (suspend (SourceRef,Int)->SourceContentPage)? = null
        var followedAction:suspend (Long,Int)->SourceListPage={_,page->SourceListPage(emptyList(),page,false)}
        var videoAction: (suspend (String)->Video)? = null
        var upSearchAction:suspend (String,Int)->UpSearchPage={q,p->UpSearchPage(listOf(UpProfile(7,q,"//i0.hdslb.com/avatar.jpg",videos=1887)),p,1,false)}
        var upUploadsAction:suspend (Long,Int,UploadOrder,String)->SourceContentPage={mid,p,_,_->
            val rows=List(1887){FavoriteItem("BV"+(it+1).toString().padStart(10,'0'),"song $it")}
            SourceContentPage(ContentSource(SourceRef(SourceKind.UP_UPLOADS,mid,mid),"fixture 的投稿","fixture",1887),rows.drop((p-1)*30).take(30),p,p*30<rows.size)
        }
        val ups=object:UpLibraryRepository {
            override suspend fun search(keyword:String,page:Int)=upSearchAction(keyword,page)
            override suspend fun profile(mid:Long)=UpProfile(mid,"fixture","//i0.hdslb.com/avatar.jpg",videos=1887)
            override suspend fun uploads(mid:Long,page:Int,order:UploadOrder,keyword:String)=upUploadsAction(mid,page,order,keyword)
        }
        val layouts=mutableMapOf<String,LibraryPreferences>();var failLayoutWrite=false
        val library=object:LibraryPreferenceRepository {
            override suspend fun read(stamp:SessionStamp)=layouts[stamp.account] ?: LibraryPreferences()
            override suspend fun update(stamp:SessionStamp,change:(LibraryPreferences)->LibraryPreferences):LibraryPreferences {
                accounts.requireCurrent(stamp);if(failLayoutWrite)throw PlatformFailure("本机保存失败")
                return change(read(stamp)).checked(stamp.account).also{layouts[stamp.account]=it}
            }
        }
        var favoriteFoldersAction:(suspend (Long,Long?)->List<FavoriteFolder>)?=null
        var favoriteOutcome=MutationOutcome.CONFIRMED
        val favoriteWrites=mutableListOf<Triple<Long,Long,Long>>()
        val createdFolders=mutableListOf<FavoriteFolder>()
        var createCalls=0;var reconcileCalls=0;var createdPrivate=false
        var createResult=FolderCreationOutcome(false,"已回读确认创建成功")
        var resolveDelay=0L;var followedReads=0
        val folderCreation=object:FolderCreationRepository {
            override suspend fun inspect(stamp:SessionStamp)=createResult.copy(message=if(createResult.pending)createResult.message else "")
            override suspend fun create(stamp:SessionStamp,title:String,private:Boolean):FolderCreationOutcome {
                accounts.requireCurrent(stamp);createCalls++;createdPrivate=private
                if(!createResult.pending)createdFolders+=FavoriteFolder(100L+createCalls,title,0,attr=if(private)1 else 0)
                return createResult
            }
            override suspend fun reconcile(stamp:SessionStamp):FolderCreationOutcome {accounts.requireCurrent(stamp);reconcileCalls++;createResult=FolderCreationOutcome(false,"已回读确认创建成功");return createResult}
        }
        val mirrors = mutableMapOf<Pair<String,SourceRef>,CollectionSnapshot>()
        val checkpoints = mutableMapOf<Pair<String,SourceRef>,SourceCheckpoint>()
        val pending = mutableMapOf<String,MutationRecord>()
        val histories = mutableMapOf<String,MutableStateFlow<List<LocalHistoryEntry>>>()
        val accounts = object : AccountRepository {
            override val session = MutableStateFlow(AccountSession(SessionStamp("7",1),SessionStatus.AUTHENTICATED,memberAccount))
            override suspend fun verify():Account? {
                verifyCalls++;delay(verifyDelay)
                val account=memberAccount
                session.value=AccountSession(SessionStamp(account?.id?.toString() ?: "guest",session.value.stamp.generation+1),if(account==null)SessionStatus.GUEST else SessionStatus.AUTHENTICATED,account)
                return account
            }
            override suspend fun accept(cookie:String)=error("unused")
            override suspend fun logout(){memberAccount=null;verify()}
            override fun requireCurrent(stamp:SessionStamp){if(stamp!=session.value.stamp)throw PlatformFailure("stale")}
        }
        val playerState=MutableStateFlow(PlaybackView(connected=true))
        val player = object : PlaybackPort {
            override val state=playerState
            override suspend fun replace(snapshot:ResumeSnapshot,play:Boolean){lastSnapshot=snapshot.checked();events+="replace";state.value=state.value.copy(queue=snapshot.entries,currentId=snapshot.currentId,mode=snapshot.mode,requested=play)}
            override suspend fun flush(){events+="flush";flushAction()}
            override suspend fun clear(){events+="clear";state.value=PlaybackView(connected=true)}
            override suspend fun mode(mode:PlayMode){}
            override suspend fun forget(video:VideoRef?){events+="forget"}
            override suspend fun live(room:LiveRoom,consent:Boolean){events+="live";liveConsent=consent}
            override suspend fun edit(edit:QueueEdit,expectedVersion:Long){}
            override fun seekTo(positionMs:Long){}
            override fun pause(reason:PauseReason){events+="pause"}
            override fun toggle(){};override fun next(){};override fun previous(){};override fun seekBy(delta:Long){};override fun close(){}
        }
        val store = object : CollectionStore {
            override suspend fun bookmarks(account:String)=mirrors.values.filter{it.account==account&&it.bookmarked}
            override suspend fun bookmark(account:String,source:ContentSource,saved:Boolean){val prior=mirrors[account to source.ref];mirrors[account to source.ref]=(prior?:CollectionSnapshot(account,source.ref,emptyList(),false,0,0)).copy(bookmarked=saved,title=source.title,ownerName=source.ownerName,total=source.count,cover=source.cover)}
            override suspend fun collection(account:String,source:SourceRef)=mirrors[account to source]
            override suspend fun saveCollection(snapshot:CollectionSnapshot){mirrors[snapshot.account to snapshot.source]=snapshot}
            override suspend fun mutation(account:String)=pending[account]
            override suspend fun saveMutation(record:MutationRecord){pending[record.account]=record}
            override suspend fun removeMutation(account:String){pending.remove(account)}
            override suspend fun updateCheckpoint(account:String,source:SourceRef)=checkpoints[account to source]
            override suspend fun saveUpdate(checkpoint:SourceCheckpoint){checkpoints[checkpoint.account to checkpoint.source]=checkpoint}
        }
        val folders = object : FavoriteRepository {
            override suspend fun folders(account:Long,aid:Long?):List<FavoriteFolder>{folderReads++;favoriteFoldersAction?.let{return it(account,aid)};return listOf(FavoriteFolder(9,"音乐",if(member)1 else 0,if(aid==null)null else member,0))+createdFolders}
            override suspend fun page(folder:Long,page:Int)=error("Use ownership-checked source port")
            override suspend fun change(account:Long,aid:Long,folder:Long,add:Boolean):MutationOutcome{accounts.requireCurrent(accounts.session.value.stamp);writes++;favoriteWrites+=Triple(account,aid,folder)
                if(favoriteOutcome==MutationOutcome.UNKNOWN)pending[account.toString()]=MutationRecord("favorite",account.toString(),aid,folder,add,now) else member=add
                return favoriteOutcome}
            override suspend fun pending(account:String)=pending[account]
            override suspend fun reconcile(account:Long):FavoriteMembership?{pending.remove("$account");return FavoriteMembership("$account",9,1,member)}
        }
        val sources=object:SourceRepository {
            override suspend fun content(source:SourceRef,account:Long?,page:Int):SourceContentPage {
                sourceCalls++;if(failPage && page==2)throw PlatformFailure("网络请求失败")
                sourceAction?.let{return it(source,page)}
                return SourceContentPage(ContentSource(source,"音乐","owner",2),if(page==1)listOf(FavoriteItem(video.bvid,"first"))else listOf(FavoriteItem("BV1U1421r7SM","second")),page,page==1)
            }
            override suspend fun resolve(link:SourceLink,account:Long?):SourceContentPage {
                withContext(NonCancellable){delay(resolveDelay)}
                val ref=if(link.collectionKind!=null)SourceRef(SourceKind.UP_COLLECTION,link.id!!,link.owner!!,link.collectionKind) else SourceRef(SourceKind.PUBLIC_FAVORITES,link.id!!,8)
                return content(ref,account,1)
            }
            override suspend fun isFollowed(account:Long,source:SourceRef):Boolean {followedReads++;return false}
            override suspend fun publicFolders(owner:Long,page:Int)=SourceListPage(emptyList(),page,false)
            override suspend fun followed(account:Long,page:Int)=followedAction(account,page)
        }
        val history=object:LocalHistoryRepository {
            override suspend fun latestVideo(account:String,bvid:String)=historyRead(account,bvid)
            override fun observe(account:String)=histories.getOrPut(account){MutableStateFlow(emptyList())}
            override suspend fun delete(account:String,video:VideoRef?){events+="delete";histories[account]?.let{it.value=it.value.filter{row->video!=null&&row.video!=video}}}
            override suspend fun prune(account:String,policy:HistoryRetentionPolicy,now:Long){prunes++}
        }
        val content=object:ContentRepository {
            override suspend fun video(bvid:String):Video {videoCalls++;return videoAction?.invoke(bvid) ?: video.copy(bvid=bvid)}
            override suspend fun search(keyword:String,page:Int)=searchAction(keyword,page)
            override suspend fun audio(bvid:String,cid:Long):String {audioCalls++;return ""}
        }
        var savedPlayback:PlaybackSnapshot?=null
        var flushAction:suspend ()->Unit={}
        var historyRead:suspend (String,String)->LocalHistoryEntry?={account,bvid->histories[account]?.value?.filter{it.video.bvid==bvid}?.maxByOrNull{it.playedAt}}
        val snapshots=object:PlaybackStateStore{override suspend fun load(account:String):PlaybackSnapshot?=savedPlayback?.takeIf{it.queue.account==account};override suspend fun checkpoint(snapshot:PlaybackSnapshot,heard:LocalHistoryEntry?) {}}
        val settings=object:SettingsRepository{override val settings=MutableStateFlow(UserSettings());override suspend fun update(value:UserSettings){settings.value=value}}
        val clock=object:Clock{override fun nowMs()=now}
        var balance=10.5
        var balanceAction:suspend (String)->Double?={balance}
        var engagementRelations=VideoRelations(false,0,false)
        var engagementLikeCalls=0
        val engagementFavoriteFolders=mutableListOf<Long>()
        val engagement=VideoEngagementRepository(object:VideoEngagementPort {
            override suspend fun folders(account:Long,aid:Long)=folders.folders(account,aid)
            override suspend fun favorite(account:Long,aid:Long,folder:Long):MutationOutcome{engagementFavoriteFolders+=folder;member=true;engagementRelations=engagementRelations.copy(favorite=true);return MutationOutcome.CONFIRMED}
            override suspend fun coinBalance(account:String)=balanceAction(account)
            override suspend fun relations(bvid:String)=engagementRelations
            override suspend fun tags(bvid:String)=emptyList<String>()
            override suspend fun like(account:String,bvid:String,liked:Boolean):MutationOutcome{engagementLikeCalls++;engagementRelations=engagementRelations.copy(liked=liked);return MutationOutcome.CONFIRMED}
            override suspend fun coin(account:String,bvid:String,amount:Int):MutationOutcome{balance-=amount;engagementRelations=engagementRelations.copy(coins=engagementRelations.coins+amount);return MutationOutcome.CONFIRMED}
            override suspend fun triple(account:String,bvid:String):TripleReceipt{balance-=1;return TripleReceipt(true,true,true,1)}
        },accounts,store,clock){"balance-fixture"}
        fun vm(home:HomeStore?=null,cloud:RemoteHistoryStore?=null,sync:RemoteHistorySync?=null)=MainViewModel(AppDependencies(accounts,folders,content,
            object:EntitlementRepository{override suspend fun inspect(video:VideoRef)=EntitlementSnapshot(video,emptyList(),EntitlementStatus.UNKNOWN,now,accounts.session.value.stamp);override suspend fun probe(bvid:String,cid:Long,extended:Boolean)=AudioProbe(emptyList(),false,null)},
            object:LiveRepository{override suspend fun room(id:Long)=liveRoomAction(id);override suspend fun streams(id:Long)=liveStreamAction(id);override suspend fun ranking():List<LiveRanking>{liveReads++;return liveRankAction()}},
            object:RecommendationRepository{
                override suspend fun accountHome(previous:Set<String>,ready:suspend (List<PopularMusic>)->Unit)=accountHomeAction(previous,ready)
                override suspend fun accountCreators(previous:Set<Long>)=accountCreatorAction(previous)
                override suspend fun creators(previous:Set<Long>):List<UpProfile> = creatorAction?.invoke(previous) ?: creators();override suspend fun popularMusic():List<PopularMusic>{musicCalls++;return musicAction()};override suspend fun popularMusic(previous:Set<String>,ready:suspend (List<PopularMusic>)->Unit):List<PopularMusic> = musicBatchAction?.invoke(previous,ready) ?: popularMusic();override suspend fun candidates():List<Recommendation>{recommendationCalls++;delay(recommendationDelay);return listOf(Recommendation(video.bvid,"public",null))};override suspend fun candidates(category:HomeCategory)=if(category==HomeCategory.ALL)candidates() else categoryAction(category)},sources,store,if(cloud==null)history else SyncedHistoryRepository(history,cloud),snapshots,settings,player,BiliJumpPort,clock,DiagnosticLog(clock),retryAccount={retryCalls++},home=home,upLibrary=ups,library=library,folderCreation=folderCreation,historySync=sync,engagement=engagement))
    }
    private fun runCase(block:suspend TestScope.(Fixture,MainViewModel)->Unit)=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f=Fixture();val vm=f.vm();val owner=androidx.lifecycle.ViewModelStore();owner.put("fixture",vm)
        try{runCurrent();block(f,vm)}finally{owner.clear();Dispatchers.resetMain()}
    }
    private suspend fun TestScope.prepareTriple(f:Fixture,vm:MainViewModel) {
        f.videoAction={f.video.copy(copyright=1,owner=8)}
        val entry=QueueEntry("triple-song",bv,1,1,"song")
        f.playerState.value=f.playerState.value.copy(queue=listOf(entry),currentId=entry.id);runCurrent()
        vm.openVideoDetails(bv);runCurrent()
    }
    @Test fun homepagePreloadsLiveWithoutTappingAndTapCoalescesTheRead()=runCase{f,vm->
        f.liveRankAction={delay(1000);listOf(LiveRanking(77,8,"现场","主播"))}
        vm.preloadRecommendationCategories();runCurrent();assertEquals(1,f.liveReads)
        vm.selectRecommendationCategory(HomeCategory.LIVE);advanceUntilIdle()
        assertEquals(1,f.liveReads);assertEquals(77L,vm.state.value.liveRankings.single().roomId)
        assertEquals(0,f.audioCalls);assertEquals(0,f.writes)
    }
    @Test fun notificationFavoriteChecksConfiguredFolderAndNeverWritesStaleTargets()=runCase{f,_->
        val action=NotificationFavorite(f.accounts,f.settings,f.content,f.folders)
        assertTrue(action.toggle(bv){true}.chooseFolder);assertTrue(f.favoriteWrites.isEmpty())
        f.settings.update(f.settings.current().copy(defaultFavoriteFolders=mapOf("7" to DefaultFavoriteFolder(9,"fixture"))))
        assertTrue(runCatching{action.toggle(bv){false}}.exceptionOrNull() is PlatformFailure);assertTrue(f.favoriteWrites.isEmpty())
        assertTrue(action.toggle(bv){true}.message.startsWith("已收藏"));assertEquals(1,f.favoriteWrites.size);assertTrue(f.member)
        assertTrue(action.toggle(bv){true}.message.contains("取消收藏"));assertEquals(2,f.favoriteWrites.size);assertFalse(f.member)
        f.member=false;f.pending["7"]=MutationRecord("unknown","7",1,9,true,0)
        assertTrue(runCatching{action.toggle(bv){true}}.exceptionOrNull() is PlatformFailure);assertEquals(2,f.favoriteWrites.size)
    }
    @Test fun notificationFavoriteDropsLateAccountAndDefaultFolderChanges()=runCase{f,_->
        f.settings.update(UserSettings(defaultFavoriteFolders=mapOf("7" to DefaultFavoriteFolder(9,"fixture"))))
        val action=NotificationFavorite(f.accounts,f.settings,f.content,f.folders)
        f.favoriteFoldersAction={_,_->f.accounts.verify();listOf(FavoriteFolder(9,"fixture",0,false))}
        assertTrue(runCatching{action.toggle(bv){true}}.exceptionOrNull() is PlatformFailure);assertTrue(f.favoriteWrites.isEmpty())
        f.favoriteFoldersAction=null
        f.videoAction={f.settings.update(UserSettings(defaultFavoriteFolders=mapOf("7" to DefaultFavoriteFolder(404,"changed"))));f.video}
        assertTrue(runCatching{action.toggle(bv){true}}.exceptionOrNull() is PlatformFailure);assertTrue(f.favoriteWrites.isEmpty())
    }
    @Test fun liveRankingIsSeparateReadOnlyAndKeepsServerOrderAndOldRowsDuringRefresh()=runCase{f,vm->
        val rows=listOf(LiveRanking(88,8,"second","A"),LiveRanking(77,9,"first","B"))
        f.liveRankAction={rows};vm.selectRecommendationCategory(HomeCategory.LIVE);advanceUntilIdle()
        assertEquals(rows,vm.state.value.liveRankings);assertTrue(vm.state.value.recommendations.isEmpty())
        vm.selectRecommendationCategory(HomeCategory.LIVE);advanceUntilIdle();assertEquals(1,f.liveReads)
        f.liveRankAction={delay(1000);throw PlatformFailure("平台要求验证",-352)}
        vm.loadLiveRanking(true);runCurrent();assertEquals(rows,vm.state.value.liveRankings);assertTrue(vm.state.value.liveRankingBusy)
        advanceUntilIdle();assertEquals(rows,vm.state.value.liveRankings);assertEquals("平台要求验证",vm.state.value.liveRankingError)
        assertEquals(0,f.videoCalls);assertEquals(0,f.audioCalls);assertEquals(0,f.writes);assertFalse(f.events.contains("live"))
    }
    @Test fun switchingAccountsDropsLateLiveRankingAndRoomMetadata()=runCase{f,vm->
        vm.inspectLive("6");advanceUntilIdle();assertEquals(77L,vm.state.value.liveRoom?.roomId)
        f.liveRankAction={withContext(NonCancellable){delay(1000)};listOf(LiveRanking(77,8,"旧身份","A"))}
        vm.loadLiveRanking();runCurrent();f.memberAccount=Account(9,"new");vm.checkAccount();advanceUntilIdle()
        assertNull(vm.state.value.liveRoom);assertTrue(vm.state.value.liveRankings.isEmpty());assertFalse(vm.state.value.liveRankingBusy)
        f.liveRankAction={listOf(LiveRanking(99,9,"new","B"))};vm.loadLiveRanking();advanceUntilIdle();assertEquals(99L,vm.state.value.liveRankings.single().roomId)
    }
    @Test fun sameAccountVerificationRetainsVisibleLiveRoomAndRankingButInvalidatesRequests()=runCase{f,vm->
        vm.inspectLive("6");vm.loadLiveRanking();advanceUntilIdle();val rows=vm.state.value.liveRankings
        f.accounts.verify();advanceUntilIdle()
        assertEquals(77L,vm.state.value.liveRoom?.roomId);assertEquals(rows,vm.state.value.liveRankings)
        assertFalse(vm.state.value.liveRankingBusy)
        vm.loadLiveRanking();advanceUntilIdle();assertEquals(2,f.liveReads)
        f.accounts.verify();advanceUntilIdle();f.memberAccount=Account(9,"new");f.accounts.verify();advanceUntilIdle()
        assertNull(vm.state.value.liveRoom);assertTrue(vm.state.value.liveRankings.isEmpty())
    }
    @Test fun roomInspectAndLocalBookmarkNeverStartPlaybackOrFollowRemotely()=runCase{f,vm->
        vm.inspectLive("https://live.bilibili.com/blanc/6");advanceUntilIdle()
        assertEquals(77L,vm.state.value.liveRoom?.roomId);assertEquals(StreamKind.MIXED,vm.state.value.liveStreams.single().kind)
        vm.toggleLiveBookmark();advanceUntilIdle();assertEquals(77L,f.settings.current().localLiveRooms["7"]?.single()?.roomId)
        assertEquals(0,f.writes);assertFalse(f.events.contains("live"));assertFalse(vm.state.value.remoteHistory.enabled)
        vm.toggleLiveBookmark();advanceUntilIdle();assertTrue(f.settings.current().localLiveRooms["7"].orEmpty().isEmpty())
    }
    @Test fun liveConsentIsExplicitAndFailedCheckpointNeverReplacesThePlayer()=runCase{f,vm->
        vm.inspectLive("6");advanceUntilIdle();f.events.clear();f.flushAction={throw PlatformFailure("本机保存失败")}
        vm.playLiveWithConsent(true);advanceUntilIdle();assertEquals(listOf("flush"),f.events);assertNull(f.liveConsent)
        f.flushAction={};f.events.clear();vm.playLiveWithConsent(true);advanceUntilIdle();assertEquals(listOf("flush","live"),f.events);assertEquals(true,f.liveConsent)
    }
    @Test fun stoppedOrUnknownRoomNeverFetchesStreamsOrStartsPlayback()=runCase{f,vm->
        f.liveStreamAction={error("must not fetch")};f.liveRoomAction={LiveRoom(it,77,8,-1,"未知","主播")}
        vm.inspectLive("6");advanceUntilIdle();assertEquals(LiveRoomStatus.UNKNOWN,vm.state.value.liveRoom?.state)
        vm.playLiveWithConsent(true);advanceUntilIdle();assertFalse(f.events.contains("live"));assertTrue(vm.state.value.liveStreams.isEmpty())
    }
    @Test fun liveOfficialJumpUsesCanonicalRoomWithoutVodTimeAndReturnNeverResumes()=runCase{f,vm->
        f.playerState.value=PlaybackView(connected=true,live=true,currentId="live:77",liveExperience=LiveExperience(roomId=77))
        runCurrent();f.events.clear();var url=""
        vm.openOfficial{url=it};advanceUntilIdle();assertEquals("https://live.bilibili.com/77",url);assertEquals(listOf("pause","flush"),f.events)
        f.events.clear();vm.foregrounded();advanceUntilIdle();assertFalse(f.events.contains("live"));assertFalse(f.events.contains("replace"));assertTrue(vm.state.value.message.orEmpty().contains("暂停"))
    }
    @Test fun returningFromLiveRestoresTheExactVodQueuePaused()=runCase{f,vm->
        val entries=listOf(QueueEntry("keep",bv,2,2,"P2"),QueueEntry("later",bv,1,1,"P1"))
        val queue=ResumeSnapshot(account="7",entries=entries,order=entries.map{it.id},currentId="keep",positionMs=78901)
        f.savedPlayback=PlaybackSnapshot(queue,f.now);f.playerState.value=f.playerState.value.copy(live=true,currentId="live:77",queue=emptyList());runCurrent()
        vm.restoreVod();advanceUntilIdle();assertEquals(queue,f.lastSnapshot);assertFalse(f.playerState.value.requested)
    }
    @Test fun tripleWithoutDefaultOnlyPromptsAndDoesNotAct()=runCase{f,vm->
        prepareTriple(f,vm);vm.engageVideo(bv,"7",EngagementAction.TRIPLE);runCurrent()
        assertTrue(vm.state.value.defaultFolderPrompt);assertEquals(0,f.engagementLikeCalls);assertTrue(f.engagementFavoriteFolders.isEmpty());assertEquals(10.5,f.balance,0.0)
    }
    @Test fun tripleUsesAccountSettingAndRepeatedHoldDoesNotSpendAgain()=runCase{f,vm->
        prepareTriple(f,vm);f.settings.update(UserSettings(defaultFavoriteFolders=mapOf("7" to DefaultFavoriteFolder(9,"音乐"))));runCurrent()
        vm.engageVideo(bv,"7",EngagementAction.TRIPLE);runCurrent()
        assertEquals(listOf(9L),f.engagementFavoriteFolders);assertEquals(1,f.engagementLikeCalls);assertEquals(8.5,f.balance,0.0)
        vm.engageVideo(bv,"7",EngagementAction.TRIPLE);runCurrent()
        assertEquals(listOf(9L),f.engagementFavoriteFolders);assertEquals(1,f.engagementLikeCalls);assertEquals(8.5,f.balance,0.0)
    }
    @Test fun removedTripleDefaultIsClearedAndPromptsWithoutAnyWrite()=runCase{f,vm->
        prepareTriple(f,vm);f.settings.update(UserSettings(defaultFavoriteFolders=mapOf("7" to DefaultFavoriteFolder(404,"已删除"))));runCurrent()
        vm.engageVideo(bv,"7",EngagementAction.TRIPLE);runCurrent()
        assertTrue(vm.state.value.defaultFolderPrompt);assertNull(f.settings.current().defaultFavoriteFolders["7"])
        assertEquals(0,f.engagementLikeCalls);assertTrue(f.engagementFavoriteFolders.isEmpty());assertEquals(10.5,f.balance,0.0)
    }
    @Test fun detailBalanceRefreshesAfterCoinWithoutReverifyingSession()=runCase{f,vm->
        f.videoAction={f.video.copy(copyright=1,owner=8)}
        val entry=QueueEntry("balance-song",bv,1,1,"song")
        f.playerState.value=f.playerState.value.copy(queue=listOf(entry),currentId=entry.id);runCurrent()
        val verifies=f.verifyCalls;vm.openVideoDetails(bv);runCurrent()
        assertEquals(10.5,vm.state.value.videoDetails.coinBalance!!,0.0)
        vm.engageVideo(bv,"7",EngagementAction.COIN,1);runCurrent()
        assertEquals(9.5,vm.state.value.videoDetails.coinBalance!!,0.0)
        assertEquals(verifies,f.verifyCalls);assertFalse(vm.state.value.busy)
    }
    @Test fun lateBalanceAfterAccountChangeNeverReappears()=runCase{f,vm->
        f.balanceAction={withContext(NonCancellable){delay(1000)};999.0}
        vm.openVideoDetails(bv);runCurrent();assertTrue(vm.state.value.videoDetails.coinBalanceLoading)
        f.accounts.logout();runCurrent();advanceTimeBy(1001);runCurrent()
        assertNull(vm.state.value.videoDetails.coinBalance);assertNull(vm.state.value.videoDetails.bvid)
    }
    @Test fun unavailableBalanceDoesNotDisableVideoRelations()=runCase{f,vm->
        f.balanceAction={throw PlatformFailure("network unavailable")}
        vm.openVideoDetails(bv);runCurrent()
        assertNull(vm.state.value.videoDetails.coinBalance);assertFalse(vm.state.value.videoDetails.coinBalanceLoading)
        assertNotNull(vm.state.value.videoDetails.relations);assertNull(vm.state.value.videoDetails.error)
    }
    @Test fun switchingCategoriesRejectsLateResultsAndKeepsSourcesSeparate()=runCase{f,vm->
        f.categoryAction={c->if(c==HomeCategory.MUSIC)withContext(NonCancellable){delay(1000)} else delay(10);listOf(Recommendation(f.video.bvid,c.label,null))}
        vm.selectRecommendationCategory(HomeCategory.MUSIC);runCurrent();assertTrue(vm.state.value.recommendations.isEmpty())
        vm.selectRecommendationCategory(HomeCategory.GAME);runCurrent();advanceTimeBy(11);runCurrent()
        assertEquals("游戏",vm.state.value.recommendations.single().title);assertFalse(vm.state.value.recommendationBusy)
        advanceTimeBy(1000);runCurrent();assertEquals(HomeCategory.GAME,vm.state.value.recommendationCategory);assertEquals("游戏",vm.state.value.recommendations.single().title)
    }
    @Test fun categorySelectionDuringAccountCheckLoadsAfterVerification()=startupCase({f,_->f.verifyDelay=5000}){_,vm->
        vm.selectRecommendationCategory(HomeCategory.MUSIC);runCurrent()
        assertEquals(HomeCategory.MUSIC,vm.state.value.recommendationCategory)
        advanceTimeBy(5001);runCurrent()
        assertFalse(vm.state.value.recommendationBusy);assertEquals("音乐",vm.state.value.recommendations.single().title)
    }
    @Test fun rankingOrderIsNeverReorderedByMusicBoost()=runCase{f,vm->
        val rows=listOf(Recommendation("BV1xx411c7mD","first",listOf("生活")),Recommendation("BV1xx411c7mE","second",listOf("音乐")))
        f.categoryAction={rows};vm.selectRecommendationCategory(HomeCategory.MUSIC);runCurrent()
        assertEquals(rows,vm.state.value.recommendations);assertFalse(vm.state.value.recommendationBusy)
    }
    @Test fun onlyExplicitRecommendationRetryAcknowledgesARestriction()=runCase{f,vm->
        val prior=f.retryCalls;var reads=0
        f.categoryAction={reads++;throw PlatformFailure("平台限制或需要验证",-412)}
        vm.selectRecommendationCategory(HomeCategory.MUSIC);runCurrent()
        assertEquals("平台限制或需要验证",vm.state.value.recommendationError);assertEquals(prior,f.retryCalls);assertEquals(1,reads)
        f.categoryAction={listOf(Recommendation(f.video.bvid,"恢复后的音乐",null))}
        vm.retryRecommendations();runCurrent()
        assertEquals(prior+1,f.retryCalls);assertNull(vm.state.value.recommendationError);assertEquals("恢复后的音乐",vm.state.value.recommendations.single().title)
    }
    private class PreviewStore:HomeStore {
        var saved:HomePreview?=HomePreview("7",100000,listOf(Recommendation("BV1xx411c7mD","cached",null)))
        var warmDelay=0L
        var readDelay=0L
        override suspend fun read(account:String):HomePreview? { delay(readDelay);return saved?.takeIf{it.account==account} }
        override suspend fun recommendations(account:String,rows:List<Recommendation>){saved=HomePreview(account,100000,rows)}
        override suspend fun metadata(account:String,video:Video){}
        override suspend fun clearMetadata(account:String){}
        override suspend fun warm(urls:List<String>){delay(warmDelay)}
    }
    private fun startupCase(configure:(Fixture,PreviewStore)->Unit,block:suspend TestScope.(Fixture,MainViewModel)->Unit)=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f=Fixture();val home=PreviewStore();configure(f,home)
        val vm=f.vm(home);val owner=androidx.lifecycle.ViewModelStore();owner.put("startup",vm)
        try{runCurrent();block(f,vm)}finally{owner.clear();Dispatchers.resetMain()}
    }
    @Test fun cachedHomeAppearsBeforeAccountNetworkCompletesWithoutAutoplay()=startupCase({f,_->f.verifyDelay=10000}){f,vm->
        assertTrue(vm.state.value.startupReady);assertEquals("cached",vm.state.value.recommendations.single().title)
        assertTrue(vm.state.value.recommendationsCached);assertFalse(vm.state.value.accountChecked)
        assertFalse(f.playerState.value.requested);assertEquals(0,f.recommendationCalls)
        advanceTimeBy(10000);runCurrent()
        assertEquals("public",vm.state.value.recommendations.single().title);assertFalse(vm.state.value.recommendationsCached)
        assertFalse(f.playerState.value.requested)
    }
    @Test fun cachedMusicAppearsBeforeAccountNetworkWithoutPlayback()=startupCase({f,h->
        f.verifyDelay=10000
        h.saved=h.saved!!.copy(popularMusic=listOf(PopularMusic("BV1xx411c7mD","cached music","","UP",6000000)))
    }){f,vm->
        assertEquals("cached music",vm.state.value.popularMusic.single().title)
        assertFalse(vm.state.value.accountChecked);assertEquals(0,f.musicCalls);assertEquals(0,f.audioCalls)
    }
    @Test fun musicRefreshKeepsTheOldBatchUntilItsReplacementIsComplete()=runCase{f,vm->
        val old=PopularMusic(bv,"first","","UP",9000000)
        f.musicAction={listOf(old)};vm.loadPopularMusic();advanceUntilIdle()
        val fresh=old.copy(bvid="BV0000000002",title="random",plays=5000000)
        f.musicBatchAction={previous,ready->
            assertEquals(setOf(old.key),previous);delay(100);ready(listOf(fresh));delay(10000);listOf(fresh)
        }
        vm.loadPopularMusic(true);advanceTimeBy(100);runCurrent()
        assertEquals(listOf(old),vm.state.value.popularMusic);assertTrue(vm.state.value.popularMusicBusy)
        assertEquals(0,f.audioCalls);advanceUntilIdle();assertFalse(vm.state.value.popularMusicBusy)
        assertEquals(listOf(fresh),vm.state.value.popularMusic)
    }
    @Test fun initialMusicStillPublishesBeforeTheBatchCompletes()=runCase{f,vm->
        val fresh=PopularMusic(bv,"first","","UP",6000000)
        f.musicBatchAction={previous,ready->assertTrue(previous.isEmpty());delay(100);ready(listOf(fresh));delay(10000);listOf(fresh)}
        vm.loadPopularMusic();advanceTimeBy(100);runCurrent()
        assertEquals(listOf(fresh),vm.state.value.popularMusic);assertTrue(vm.state.value.popularMusicBusy)
        advanceUntilIdle();assertFalse(vm.state.value.popularMusicBusy);assertEquals(0,f.audioCalls)
    }
    private fun popularRows(start:Int,count:Int)=List(count){index->PopularMusic("BV"+(start+index).toString().padStart(10,'0'),"song ${start+index}","","UP",2000000)}
    @Test fun musicSwitchChangesBothFeedsAndKeepsLowViewServerOrderAndPaging()=runCase{f,vm->
        f.musicAction={popularRows(1,12)};f.creatorAction={listOf(UpProfile(8,"音乐作者"))}
        vm.loadPopularMusic();vm.loadHomeUps();advanceUntilIdle();assertTrue(vm.state.value.settings.musicRecommendations)
        var reads=0;var creatorReads=0
        val accountRows=popularRows(200,12).reversed().map{it.copy(plays=23)}
        f.accountHomeAction={excluded,_->reads++;if(excluded.isEmpty())accountRows else popularRows(300,12).map{it.copy(plays=1)}}
        f.accountCreatorAction={creatorReads++;listOf(UpProfile(99,"生活作者"))}
        vm.updateSettings(f.settings.current().copy(musicRecommendations=false));advanceUntilIdle()
        assertEquals(accountRows,vm.state.value.popularMusic);assertEquals("生活作者",vm.state.value.homeUps.single().name)
        assertEquals(1,reads);assertEquals(1,creatorReads);assertFalse(f.settings.current().musicRecommendations)
        vm.loadMorePopularMusic();advanceUntilIdle();assertEquals(24,vm.state.value.popularMusic.size);assertEquals(accountRows,vm.state.value.popularMusic.take(12))
        vm.updateSettings(f.settings.current().copy(musicRecommendations=true));advanceUntilIdle()
        assertEquals(popularRows(1,12),vm.state.value.popularMusic);assertEquals("音乐作者",vm.state.value.homeUps.single().name)
        assertEquals(0,f.audioCalls);assertEquals(0,f.writes);assertEquals(2,reads)
    }
    @Test fun switchingModesDropsLateVideoAndCreatorResultsAndNeverFallsBackOnFailure()=runCase{f,vm->
        f.musicAction={withContext(NonCancellable){delay(1000)};popularRows(1,12)}
        f.creatorAction={withContext(NonCancellable){delay(1000)};listOf(UpProfile(8,"旧音乐作者"))}
        vm.loadPopularMusic();vm.loadHomeUps();runCurrent()
        f.accountHomeAction={_,_->popularRows(100,12).map{it.copy(plays=10)}}
        f.accountCreatorAction={listOf(UpProfile(99,"新作者"))}
        vm.updateSettings(f.settings.current().copy(musicRecommendations=false));advanceUntilIdle()
        assertTrue(vm.state.value.popularMusic.all{it.plays==10L});assertEquals(99L,vm.state.value.homeUps.single().mid)
        f.accountHomeAction={_,_->throw PlatformFailure("平台限制",-352)};f.accountCreatorAction={throw PlatformFailure("平台限制",-352)}
        vm.loadPopularMusic(true);vm.loadHomeUps(true);advanceUntilIdle()
        assertEquals("平台限制",vm.state.value.popularMusicError);assertEquals("平台限制",vm.state.value.homeUpsError)
        assertEquals(1,f.musicCalls);assertEquals(99L,vm.state.value.homeUps.single().mid);assertEquals(0,f.audioCalls)
    }
    @Test fun storedOffPreferenceNeverRestoresMusicPreviewIntoAccountFeed()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));val f=Fixture()
        f.settings.update(UserSettings(musicRecommendations=false));f.accountHomeAction={_,_->popularRows(100,12).map{it.copy(plays=5)}}
        val preview=PreviewStore();preview.saved=HomePreview("7",f.now,popularMusic=popularRows(1,12))
        val vm=f.vm(preview);val owner=androidx.lifecycle.ViewModelStore();owner.put("fixture",vm)
        try{advanceUntilIdle();vm.loadPopularMusic();advanceUntilIdle();assertTrue(vm.state.value.popularMusic.all{it.plays==5L});assertFalse(vm.state.value.settings.musicRecommendations)}finally{owner.clear();Dispatchers.resetMain()}
    }
    @Test fun popularMusicOnlyLoadsOneBatchUntilBrowsingThenAppendsBeyondThirty()=runCase{f,vm->
        var reads=0
        f.musicBatchAction={excluded,_->
            val batch=popularRows(++reads*100,20)
            assertTrue(batch.none{it.key in excluded});batch
        }
        vm.loadPopularMusic();advanceUntilIdle();assertEquals(12,vm.state.value.popularMusic.size);assertEquals(1,reads)
        val first=vm.state.value.popularMusic
        advanceTimeBy(60000);runCurrent();assertEquals(1,reads)
        repeat(4){vm.loadMorePopularMusic();advanceUntilIdle()}
        assertEquals(60,vm.state.value.popularMusic.size);assertEquals(5,reads)
        assertEquals(first,vm.state.value.popularMusic.take(12));assertEquals(60,vm.state.value.popularMusic.distinctBy{it.key}.size)
        assertEquals(0,f.audioCalls);assertEquals(0,f.writes)
    }
    @Test fun popularMusicMoreCoalescesPartialBatchesAndKeepsRowsOnFailureForExplicitRetry()=runCase{f,vm->
        f.musicAction={popularRows(1,12)};vm.loadPopularMusic();advanceUntilIdle()
        val first=vm.state.value.popularMusic;var reads=0
        f.musicBatchAction={excluded,ready->
            reads++;assertTrue(excluded.containsAll(first.map{it.key}))
            val next=popularRows(100,12);delay(100);ready(first+next.take(3));delay(1000);throw PlatformFailure("network")
        }
        vm.loadMorePopularMusic();vm.loadMorePopularMusic();advanceTimeBy(101);runCurrent()
        assertEquals(15,vm.state.value.popularMusic.size);assertEquals(first,vm.state.value.popularMusic.take(12))
        advanceUntilIdle();assertEquals(1,reads);assertEquals("network",vm.state.value.popularMusicMoreError)
        vm.loadMorePopularMusic();advanceUntilIdle();assertEquals(1,reads)
        f.musicBatchAction={excluded,_->reads++;assertTrue(popularRows(100,3).all{it.key in excluded});popularRows(200,12)}
        vm.loadMorePopularMusic(true);advanceUntilIdle();assertEquals(27,vm.state.value.popularMusic.size)
        assertEquals(2,reads);assertNull(vm.state.value.popularMusicMoreError)
    }
    @Test fun refreshedOrChangedAccountDropsLatePopularPagesAndEmptyReadStopsWithoutLooping()=runCase{f,vm->
        f.musicAction={popularRows(1,12)};vm.loadPopularMusic();advanceUntilIdle()
        f.musicBatchAction={_,_->withContext(NonCancellable){delay(1000)};popularRows(100,12)}
        vm.loadMorePopularMusic();runCurrent()
        f.musicBatchAction={_,_->popularRows(200,12)};vm.loadPopularMusic(true);advanceUntilIdle()
        assertEquals(popularRows(200,12),vm.state.value.popularMusic);assertFalse(vm.state.value.popularMusicMoreBusy)
        f.musicBatchAction={_,_->withContext(NonCancellable){delay(1000)};popularRows(300,12)}
        vm.loadMorePopularMusic();runCurrent();f.memberAccount=Account(9,"new");vm.checkAccount();advanceUntilIdle()
        assertTrue(vm.state.value.popularMusic.isEmpty());assertFalse(vm.state.value.popularMusicMoreBusy)
        f.musicBatchAction={_,_->popularRows(400,12)};vm.loadPopularMusic();advanceUntilIdle()
        var reads=0;f.musicBatchAction={_,_->reads++;emptyList()}
        vm.loadMorePopularMusic();advanceUntilIdle();assertFalse(vm.state.value.popularMusicHasMore)
        vm.loadMorePopularMusic();advanceUntilIdle();assertEquals(1,reads);assertEquals(12,vm.state.value.popularMusic.size)
    }
    @Test fun firstInstallReleasesAtDeadlineEvenWhenNetworkNeverFinishes()=startupCase({f,h->f.verifyDelay=60000;h.saved=null}){_,vm->
        assertTrue(vm.state.value.recommendationBusy);assertFalse(vm.state.value.startupReady);advanceTimeBy(1799);runCurrent();assertFalse(vm.state.value.startupReady)
        advanceTimeBy(1);runCurrent();assertTrue(vm.state.value.startupReady)
    }
    @Test fun freshHomeCanReleaseImmediatelyAndForegroundDoesNotVerifyTwice()=startupCase({_,h->h.saved=null}){f,vm->
        assertTrue(vm.state.value.startupReady);assertEquals("public",vm.state.value.recommendations.single().title)
        vm.foregrounded();runCurrent();assertEquals(1,f.verifyCalls);assertEquals(1,f.recommendationCalls)
    }
    @Test fun slowCoverDoesNotHoldUsableCachedHomeBehindStartup()=startupCase({f,h->f.verifyDelay=10000;h.warmDelay=60000}){f,vm->
        assertTrue(vm.state.value.startupReady)
        assertEquals("cached",vm.state.value.recommendations.single().title)
        assertFalse(vm.state.value.accountChecked);assertFalse(f.playerState.value.requested)
    }
    @Test fun SlowRecommendationKeepsCachedHomeUntilFreshResultArrives()=startupCase({f,_->f.recommendationDelay=10000}){_,vm->
        assertTrue(vm.state.value.startupReady);assertEquals("cached",vm.state.value.recommendations.single().title)
        advanceTimeBy(10000);runCurrent();assertEquals("public",vm.state.value.recommendations.single().title)
        assertFalse(vm.state.value.recommendationsCached)
    }
    @Test fun lateCacheCannotRepopulateAnotherIdentityAfterLogout()=startupCase({_,h->h.readDelay=5000}){_,vm->
        vm.logout();runCurrent();advanceTimeBy(5000);runCurrent()
        assertNull(vm.state.value.account);assertFalse(vm.state.value.recommendationsCached)
        assertTrue(vm.state.value.recommendations.none{it.title=="cached"})
    }
    @Test fun continuousPartsAndOriginsPropagateWithoutAddingAnExtraUserStep()=runCase{f,vm->
        vm.updateSettings(UserSettings(continuousParts=true));runCurrent()
        for(origin in listOf(HistoryOrigin.SEARCH,HistoryOrigin.RECOMMENDATION,HistoryOrigin.LINK)) {
            vm.playVideo(bv,origin=origin);runCurrent()
            assertEquals(listOf(1,2),f.playerState.value.queue.map{it.part})
            assertTrue(f.playerState.value.queue.all{it.origin==origin});assertTrue(f.playerState.value.requested)
        }
        vm.sharedVideo(f.video,f.video.parts[1],0);runCurrent()
        assertEquals(2,f.playerState.value.queue.single().part);assertEquals(HistoryOrigin.SHARE,f.playerState.value.queue.single().origin)
        assertEquals(0,f.writes)
    }
    @Test fun logoutRetentionIsExplicitAndDeletesBeforeChangingAccountOnlyWhenEnabled()=runCase{f,vm->
        vm.updateSettings(UserSettings(historyDeleteOnExit=true));runCurrent();f.events.clear()
        vm.logout();runCurrent()
        assertEquals(listOf("pause","flush","forget","delete","clear"),f.events)
        assertNull(vm.state.value.account);assertEquals(0,f.writes)
    }
    @Test fun disablingRecordingInvalidatesPendingHeardStateWithoutTouchingFavorites()=runCase{f,vm->
        vm.updateSettings(UserSettings(historyEnabled=false));runCurrent()
        assertEquals(listOf("pause","flush","forget"),f.events);assertEquals(0,f.writes)
    }
    @Test fun historyRestoreRetainsOriginPartAndDoesNotRequireLibraryMutation()=runCase{f,vm->
        for(origin in HistoryOrigin.entries) {
            vm.playHistory(LocalHistoryEntry("7",VideoRef(bv,2,2),"course",null,45678,100,origin));runCurrent()
            assertEquals(origin,f.playerState.value.queue.single().origin);assertEquals(2,f.playerState.value.queue.single().part)
        }
        vm.playHistory(LocalHistoryEntry("8",VideoRef(bv,2,2),"other",null,1,100));runCurrent()
        assertNotNull(vm.state.value.error);assertEquals(0,f.writes)
    }
    @Test fun recommendationPreferenceUpdatesNeverCleanHistoryAndUnverifiedRestoreExplainsNetworkRequirement()=runCase{f,vm->
        f.prunes=0;vm.updateSettings(UserSettings(musicBoost=false));runCurrent();assertEquals(0,f.prunes)
        vm.updateSettings(UserSettings());runCurrent();assertEquals(0,f.prunes)
        val row=LocalHistoryEntry("7",VideoRef(bv,2,2),"course",null,500,100)
        f.histories["7"]!!.value=listOf(row);runCurrent()
        (f.accounts.session as MutableStateFlow<AccountSession>).value=f.accounts.session.value.copy(status=SessionStatus.UNVERIFIED)
        vm.playHistory(row);runCurrent()
        assertTrue(vm.state.value.error!!.contains("联网验证"));assertEquals(listOf(row),vm.state.value.history)
        assertFalse("replace" in f.events)
    }
    @Test fun newSearchCancelsOldResultAndDoesNotReplacePlayback()=runCase{f,vm->
        val old=CompletableDeferred<SearchPage>();f.searchAction={q,p->if(q=="old")old.await() else SearchPage(listOf(FavoriteItem(bv,q)),p,1,1)}
        vm.searchVideos("old");runCurrent();vm.searchVideos("new");runCurrent()
        old.complete(SearchPage(listOf(FavoriteItem(bv,"stale")),1,1,1));runCurrent()
        assertEquals("new",vm.state.value.search!!.items.single().title);assertFalse(vm.state.value.searchBusy);assertFalse("replace" in f.events)
    }
    @Test fun contentTapDirectlyPlaysP1WithoutFavoriteWriteOrQueuePicker()=runCase{f,vm->
        vm.updateSettings(UserSettings(continuousParts=false));runCurrent();f.events.clear()
        vm.playVideo(bv,9,f.source);runCurrent()
        assertEquals(1,f.playerState.value.queue.single().part)
        assertEquals(f.source,f.playerState.value.queue.single().source)
        assertTrue(f.playerState.value.requested);assertEquals(0,f.writes)
        assertFalse(vm.state.value.pendingFavorite);assertEquals(listOf("flush","replace"),f.events)
    }
    @Test fun defaultVideoTapResumesLatestPartAmong150AcrossEntryOriginsAndNewViewModel()=runCase{f,vm->
        f.videoAction={id->f.video.copy(bvid=id,parts=List(150){VideoPart(it+101L,it+1,"song ${it+1}",200)})}
        f.histories["7"]!!.value=listOf(
            LocalHistoryEntry("7",VideoRef(bv,149,49),"older",null,18000,100),
            LocalHistoryEntry("7",VideoRef(bv,142,42),"latest",null,95123,200))
        for(origin in listOf(HistoryOrigin.SEARCH,HistoryOrigin.RECOMMENDATION,HistoryOrigin.LINK)) {
            vm.playVideo(bv,origin=origin);runCurrent()
            val q=f.lastSnapshot!!;assertEquals(150,q.entries.size)
            assertEquals(42,q.entries.single{it.id==q.currentId}.part);assertEquals(95123L,q.positionMs)
            assertEquals((1..150).toList(),q.entries.map{it.part});assertTrue(q.entries.all{it.origin==origin})
        }
        val fresh=f.vm();runCurrent();fresh.playVideo(bv,9,f.source);runCurrent()
        assertEquals(42,f.lastSnapshot!!.entries.single{it.id==f.lastSnapshot!!.currentId}.part)
        assertEquals(f.source,f.lastSnapshot!!.entries.first().source);assertEquals(0,f.audioCalls);assertEquals(0,f.writes)
    }
    @Test fun flushBeforeReadingUsesLatestPausedSnapshotPositionWithoutRequiringAUiHistoryRefresh()=runCase{f,vm->
        val row=LocalHistoryEntry("7",VideoRef(bv,2,2),"part 2",null,12000,100)
        f.flushAction={
            f.histories["7"]!!.value=listOf(row)
            val e=row.entry("saved")
            f.savedPlayback=PlaybackSnapshot(ResumeSnapshot(account="7",entries=listOf(e),order=listOf(e.id),currentId=e.id,positionMs=23456),200)
        }
        vm.playVideo(bv);runCurrent()
        assertEquals(listOf("flush","replace"),f.events);assertEquals(23456L,f.lastSnapshot!!.positionMs)
        assertEquals(2,f.lastSnapshot!!.entries.single{it.id==f.lastSnapshot!!.currentId}.part)
    }
    @Test fun privacyDisabledAndAnotherAccountNeverResumeStoredVideoProgress()=runCase{f,vm->
        f.histories["7"]!!.value=listOf(LocalHistoryEntry("7",VideoRef(bv,2,2),"old",null,12345,200))
        vm.updateSettings(UserSettings(historyEnabled=false));runCurrent();f.events.clear()
        vm.playVideo(bv);runCurrent();assertEquals(0L,f.lastSnapshot!!.positionMs);assertEquals(1,f.lastSnapshot!!.entries.single{it.id==f.lastSnapshot!!.currentId}.part)
        vm.updateSettings(UserSettings());runCurrent()
        f.historyRead={_,_->LocalHistoryEntry("8",VideoRef(bv,2,2),"foreign",null,12345,300)}
        vm.playVideo(bv);runCurrent();assertEquals(0L,f.lastSnapshot!!.positionMs);assertEquals(1,f.lastSnapshot!!.entries.single{it.id==f.lastSnapshot!!.currentId}.part)
    }
    @Test fun explicitPartSelectionStartsChosenSongAtZeroAndDisablingContinuousKeepsResume()=runCase{f,vm->
        f.histories["7"]!!.value=listOf(LocalHistoryEntry("7",VideoRef(bv,2,2),"old",null,12345,200))
        vm.updateSettings(UserSettings(continuousParts=false));runCurrent();vm.playVideo(bv);runCurrent()
        assertEquals(2,f.lastSnapshot!!.entries.single().part);assertEquals(12345L,f.lastSnapshot!!.positionMs)
        vm.updateSettings(UserSettings());runCurrent();vm.inspect(bv);runCurrent();vm.playPart(f.video.parts.first());runCurrent()
        assertEquals(1,f.lastSnapshot!!.entries.single{it.id==f.lastSnapshot!!.currentId}.part);assertEquals(0L,f.lastSnapshot!!.positionMs)
        assertEquals(listOf(1,2),f.lastSnapshot!!.entries.map{it.part})
    }
    @Test fun deletedPartFallsBackAndFailedHistoryReadDoesNotReplaceExistingQueue()=runCase{f,vm->
        f.histories["7"]!!.value=listOf(LocalHistoryEntry("7",VideoRef(bv,99,2),"removed",null,12345,200))
        vm.playVideo(bv);runCurrent();assertEquals(0L,f.lastSnapshot!!.positionMs);assertTrue(vm.state.value.message.contains("已不可用"))
        val retained=f.lastSnapshot;f.historyRead={_,_->throw PlatformFailure("本机进度读取失败")}
        vm.playVideo(bv);runCurrent();assertSame(retained,f.lastSnapshot);assertNotNull(vm.state.value.error)
    }
    @Test fun accountChangeRejectsDelayedHistoryBeforeQueueReplacement()=runCase{f,vm->
        val read=CompletableDeferred<LocalHistoryEntry?>();f.historyRead={_,_->withContext(NonCancellable){read.await()}}
        vm.playVideo(bv);runCurrent()
        (f.accounts.session as MutableStateFlow<AccountSession>).value=AccountSession(SessionStamp("8",2),SessionStatus.AUTHENTICATED,Account(8,"other"))
        read.complete(LocalHistoryEntry("7",VideoRef(bv,2,2),"old",null,12345,200));runCurrent()
        assertNull(f.lastSnapshot);assertFalse("replace" in f.events);assertEquals(0,f.audioCalls)
    }
    @Test fun guestContentTapPlaysAndReceivingShareAloneDoesNothing()=runCase{f,vm->
        vm.logout();runCurrent();f.events.clear()
        vm.receiveShare("https://www.bilibili.com/video/$bv");runCurrent()
        assertTrue(f.events.isEmpty());assertEquals(0,f.writes)
        vm.dismissShare();vm.playVideo(bv);runCurrent()
        assertTrue(f.playerState.value.requested);assertTrue(f.playerState.value.queue.all{it.bvid==bv})
        assertEquals(0,f.writes)
    }
    @Test fun sharedLiveOnlyInspectsUntilAnExplicitPlayActionThenFlushesVod()=runCase{f,vm->
        vm.playVideo(bv);runCurrent();f.events.clear()
        vm.sharedLive(LiveRoom(6,6,7,1,"fixture","anchor"));runCurrent()
        assertTrue(f.events.isEmpty());assertEquals(77L,vm.state.value.liveRoom?.roomId)
        vm.playLiveWithConsent(true);runCurrent()
        assertEquals(listOf("flush","live"),f.events);assertEquals(0,f.writes)
    }
    @Test fun missingOfficialHandlerDoesNotPauseOrLaunch()=runCase{f,vm->
        f.playerState.value=PlaybackView(connected=true,queue=listOf(QueueEntry("e",bv,1,1,"video")),currentId="e",playing=true,requested=true)
        runCurrent();f.events.clear();var launched=false
        vm.openOfficialChecked({false},{launched=true});runCurrent()
        assertFalse(launched);assertFalse("pause" in f.events);assertFalse("flush" in f.events);assertNotNull(vm.state.value.error)
    }
    @Test fun officialLaunchWaitsForPauseAndFlushWithoutChangingQueue()=runCase{f,vm->
        f.playerState.value=PlaybackView(connected=true,queue=listOf(QueueEntry("e",bv,2,2,"video")),currentId="e",positionMs=109000)
        runCurrent();f.events.clear()
        vm.openOfficialChecked({true},{url->assertEquals(listOf("pause","flush"),f.events);assertTrue(url.contains("p=2&t=109"));f.events+="launch"});runCurrent()
        assertEquals(listOf("pause","flush","launch"),f.events)
    }
    @Test fun refreshingSameFolderKeepsQueryAndPagingFailureShowsError()=runCase{f,vm->
        val folder=FavoriteFolder(9,"音乐",2);vm.openFolder(folder);runCurrent();vm.setCollectionFilter("fixture")
        vm.openFolder(folder);runCurrent();assertEquals("fixture",vm.state.value.collectionFilter)
        f.failPage=true;vm.openFolder(folder,true);runCurrent();assertNotNull(vm.state.value.error);assertTrue(vm.state.value.favorites.isNotEmpty())
    }
    @Test fun explicitSearchSubmissionClearsOldRestrictionButPaginationDoesNotAutomaticallyRetryIt()=runCase{f,vm->
        val before=f.retryCalls
        f.searchAction={_,_->throw PlatformFailure("platform",-352)}
        vm.searchVideos("first");runCurrent();assertEquals(before+1,f.retryCalls);assertNotNull(vm.state.value.error)
        f.searchAction={q,p->SearchPage(listOf(FavoriteItem(bv,q)),p,2,2)}
        vm.searchVideos("second");runCurrent();assertEquals(before+2,f.retryCalls);assertNull(vm.state.value.error)
        assertEquals("second",vm.state.value.search!!.items.single().title)
        vm.searchVideos("second",true);runCurrent();assertEquals(before+2,f.retryCalls);assertEquals(2,vm.state.value.search!!.page)
        assertFalse("replace" in f.events);assertEquals(0,f.audioCalls)
    }
    @Test fun searchSecondPageFailureKeepsFirstPage()=runCase{f,vm->
        vm.searchVideos("first");runCurrent();f.searchAction={_,_->throw PlatformFailure("platform",412)}
        vm.searchVideos("first",true);runCurrent();assertEquals(1,vm.state.value.search!!.page);assertEquals("first",vm.state.value.search!!.items.single().title)
    }
    @Test fun logoutCancelsSearchAndIsolatesHistory()=runCase{f,vm->
        val blocked=CompletableDeferred<SearchPage>();f.searchAction={_,_->blocked.await()}
        vm.searchVideos("private");runCurrent();vm.logout();runCurrent();blocked.complete(SearchPage(emptyList(),1,1,0));runCurrent()
        assertNull(vm.state.value.account);assertNull(vm.state.value.search);assertEquals(LoginPhase.GUEST,vm.state.value.loginPhase)
        assertEquals(setOf("7","guest"),f.histories.keys)
    }
    @Test fun guestCanSearchRecommendAndPlayWhileFavoriteWaitsForExplicitChoice()=runCase{f,vm->
        vm.logout();runCurrent();vm.searchVideos("public");runCurrent();vm.loadRecommendations();runCurrent();vm.inspect(bv);runCurrent()
        vm.playPart(f.video.parts.first());runCurrent();assertTrue("replace" in f.events)
        vm.requestFavorite();runCurrent();assertTrue(vm.state.value.pendingFavorite);assertEquals(0,f.writes)
        f.memberAccount=Account(8,"new");vm.loginFinished();runCurrent()
        assertEquals(bv,vm.state.value.video!!.bvid);assertEquals("public",vm.state.value.searchKeyword);assertTrue(vm.state.value.pendingFavorite);assertEquals(0,f.writes)
    }
    @Test fun favoriteWritesRefreshMembershipAndOnlySelectedFolder()=runCase{f,vm->
        vm.inspect(bv);runCurrent();vm.requestFavorite();runCurrent();assertEquals(false,vm.state.value.folders.single().contains)
        vm.modifyFavorite(vm.state.value.folders.single(),true);runCurrent()
        assertEquals(true,vm.state.value.folders.single().contains);assertEquals(1,f.writes);assertEquals("已确认",vm.state.value.mutationStatus)
    }
    @Test fun pagingFailureRetainsLoadedContentAndDoesNotReplaceBaseline()=runCase{f,vm->
        f.checkpoints["7" to f.source]=SourceCheckpoint("7",f.source,setOf("prior"))
        val folder=FavoriteFolder(9,"音乐",2);vm.openFolder(folder);runCurrent();f.failPage=true
        vm.openFolder(folder,true);runCurrent()
        assertEquals(listOf(bv),vm.state.value.favorites.map{it.bvid});assertEquals(setOf("prior"),f.checkpoints["7" to f.source]!!.ids)
        assertEquals(false,f.mirrors["7" to f.source]!!.complete)
    }
    @Test fun completePaginationUpdatesBaselineWithoutQueueMutation()=runCase{f,vm->
        val folder=FavoriteFolder(9,"音乐",2);vm.openFolder(folder);runCurrent();vm.openFolder(folder,true);runCurrent()
        assertEquals(2,vm.state.value.favorites.size);assertFalse(vm.state.value.hasMore);assertTrue(f.mirrors["7" to f.source]!!.complete);assertFalse("replace" in f.events)
    }
    @Test fun historyDeletionFlushesAndInvalidatesBeforeDelete()=runCase{f,vm->
        vm.deleteLocalHistory();runCurrent();assertEquals(listOf("pause","flush","forget","delete"),f.events)
    }
    @Test fun backgroundPreventsNetworkRefreshAndForegroundUsesFreshness()=runCase{f,vm->
        vm.foregrounded();runCurrent();val count=f.folderReads
        vm.foregrounded();runCurrent();assertEquals(count,f.folderReads)
        vm.backgrounded();runCurrent();f.now+=60000;vm.networkAvailable();runCurrent();assertEquals(count,f.folderReads)
        vm.foregrounded();runCurrent();assertTrue(f.folderReads>count)
    }
    @Test fun sourceQueueUsesAllPagesAndLibraryRefreshCannotInjectNewEntries()=runCase{f,vm->
        vm.openFolder(FavoriteFolder(9,"音乐",2));runCurrent();vm.playCollection(true);runCurrent()
        assertEquals(2,f.player.state.value.queue.size);assertEquals(PlayMode.SHUFFLE,f.player.state.value.mode)
        val queue=f.player.state.value.queue
        vm.openFolder(FavoriteFolder(9,"音乐",2));runCurrent();assertEquals(queue,f.player.state.value.queue)
        assertTrue(f.player.state.value.queue.all{it.source==f.source&&it.part==1})
    }
    private fun collectionRows(count:Int)=List(count){FavoriteItem("BV"+(it+1).toString().padStart(10,'0'),"song $it")}
    @Test fun mixedFollowedPagesAndFailureRetainLocalBookmarksAndCurrentQueue()=runCase{f,vm->
        val folder=ContentSource(SourceRef(SourceKind.PUBLIC_FAVORITES,42,8),"收藏夹","作者",200,"cover")
        val season=ContentSource(SourceRef(SourceKind.UP_COLLECTION,42,8,CollectionKind.SEASON),"合集","作者",40)
        f.store.bookmark("7",folder,true)
        f.followedAction={_,page->SourceListPage(if(page==1)listOf(folder) else listOf(folder,season),page,page==1)}
        val queue=f.playerState.value;vm.loadFollowedSources();runCurrent();vm.loadFollowedSources(true);runCurrent()
        assertEquals(listOf(folder,season),vm.state.value.sources!!.sources)
        assertEquals(1,vm.state.value.bookmarks.size);assertEquals(2,followedLibraryRows(vm.state.value).size)
        f.followedAction={_,_->throw PlatformFailure("网络请求失败")}
        vm.loadFollowedSources();runCurrent()
        assertEquals(listOf(folder,season),vm.state.value.sources!!.sources)
        assertEquals(queue,f.playerState.value);assertEquals(0,f.writes);assertEquals(0,f.audioCalls)
    }
    private fun fullCollection(f:Fixture,count:Int=200):List<FavoriteItem> {
        val rows=collectionRows(count)
        f.sourceAction={source,page->SourceContentPage(ContentSource(source,"音乐","owner",count),rows.drop((page-1)*20).take(20),page,page*20<count)}
        return rows
    }
    @Test fun twoHundredSongsReadTenPagesAndOnlyMetadataBeforeOneQueueCommit()=runCase{f,vm->
        val rows=fullCollection(f)
        vm.openFolder(FavoriteFolder(9,"音乐",200));runCurrent();f.sourceCalls=0;f.videoCalls=0;f.events.clear()
        var ready=false;vm.playCollection(onReady={ready=true});runCurrent()
        val queue=f.lastSnapshot!!
        assertEquals(10,f.sourceCalls);assertEquals(200,f.videoCalls);assertEquals(0,f.audioCalls)
        assertEquals(rows.map{it.bvid},queue.entries.map{it.bvid});assertEquals(queue.entries.map{it.id},queue.order)
        assertEquals(listOf("replace"),f.events);assertTrue(ready);assertFalse(vm.state.value.collectionPreparing)
        assertTrue(queue.entries.all{it.source==f.source&&it.part==1&&!it.offline})
    }
    @Test fun shuffledTwoHundredSongsDeduplicateAndSkipOnlyMissingOrDenied()=runCase{f,vm->
        val rows=fullCollection(f)
        val pages=f.sourceAction!!
        f.sourceAction={source,page->pages(source,page).let{it.copy(items=it.items+it.items.take(1))}}
        f.videoAction={bvid->when(bvid){rows[65].bvid->throw PlatformFailure("失效",-404);rows[150].bvid->throw PlatformFailure("无权访问",-403);else->f.video.copy(bvid=bvid)}}
        vm.openFolder(FavoriteFolder(9,"音乐",200));runCurrent();vm.playCollection(true);runCurrent()
        val q=f.lastSnapshot!!
        assertEquals(198,q.entries.size);assertEquals(PlayMode.SHUFFLE,q.mode)
        assertEquals(q.entries.map{it.id}.toSet(),q.order.toSet());assertEquals(198,q.order.size)
        assertEquals(0,f.audioCalls);assertTrue(vm.state.value.message.contains("跳过 2"))
    }
    @Test fun failedPageAfterFiftyKeepsOriginalQueueAndDoesNotReadAudio()=runCase{f,vm->
        fullCollection(f);val pages=f.sourceAction!!
        f.sourceAction={source,page->if(page==8)throw PlatformFailure("网络请求失败") else pages(source,page)}
        vm.openFolder(FavoriteFolder(9,"音乐",200));runCurrent();f.events.clear();val prior=f.playerState.value
        vm.playCollection();runCurrent()
        assertEquals(prior,f.playerState.value);assertFalse("replace" in f.events);assertEquals(0,f.audioCalls)
        assertEquals("网络请求失败",vm.state.value.error);assertFalse(vm.state.value.collectionPreparing)
    }
    @Test fun cancellingPreparationRejectsUncancellableLateDetailsAndKeepsQueue()=runCase{f,vm->
        fullCollection(f);vm.openFolder(FavoriteFolder(9,"音乐",200));runCurrent()
        val before=f.playerState.value;var ready=false;f.events.clear()
        f.videoAction={bvid->withContext(NonCancellable){delay(1000)};f.video.copy(bvid=bvid)}
        vm.playCollection(onReady={ready=true});runCurrent();assertTrue(vm.state.value.collectionPreparing)
        vm.cancelCollectionPlayback();advanceUntilIdle()
        assertEquals(before,f.playerState.value);assertFalse("replace" in f.events);assertFalse(ready)
        assertFalse(vm.state.value.busy);assertFalse(vm.state.value.collectionPreparing);assertEquals(0,f.audioCalls)
    }
    @Test fun badFirstPageOrRepeatedPageCannotReplaceQueue()=runCase{f,vm->
        vm.openFolder(FavoriteFolder(9,"音乐",2));runCurrent();f.events.clear()
        f.sourceAction={source,page->SourceContentPage(ContentSource(source,"音乐","owner",200),listOf(FavoriteItem(bv,"same")),page+1,true)}
        vm.playCollection();runCurrent();assertEquals("分页身份不匹配",vm.state.value.error)
        f.sourceAction={source,page->SourceContentPage(ContentSource(source,"音乐","owner",200),listOf(FavoriteItem(bv,"same")),page,true)}
        vm.playCollection();runCurrent();assertTrue(vm.state.value.error!!.contains("分页没有推进"))
        assertFalse("replace" in f.events);assertEquals(0,f.audioCalls)
    }
    @Test fun logoutDuringLateMetadataCannotCommitOldAccountQueue()=runCase{f,vm->
        fullCollection(f);vm.openFolder(FavoriteFolder(9,"音乐",200));runCurrent();f.events.clear()
        f.videoAction={bvid->withContext(NonCancellable){delay(1000)};f.video.copy(bvid=bvid)}
        vm.playCollection();runCurrent();vm.logout();runCurrent();advanceUntilIdle()
        assertNull(vm.state.value.account);assertFalse("replace" in f.events);assertFalse(vm.state.value.collectionPreparing)
        assertEquals(0,f.audioCalls)
    }
    @Test fun upAll1887SongsUseOnlyStartingP1AndKeepTheRestLazyInShuffle()=runCase{f,vm->
        f.videoAction={bvid->f.video.copy(bvid=bvid,owner=8)}
        val pages=mutableListOf<Int>();val action=f.upUploadsAction
        f.upUploadsAction={mid,page,order,query->pages+=page;action(mid,page,order,query)}
        vm.openUp(UpProfile(8,"音乐 UP"));runCurrent();pages.clear();f.videoCalls=0;f.events.clear()
        vm.playCollection(true);runCurrent()
        val queue=f.lastSnapshot!!
        assertEquals((1..63).toList(),pages);assertEquals(1887,queue.entries.size);assertEquals(1,f.videoCalls);assertEquals(0,f.audioCalls)
        assertEquals(1,queue.entries.count{it.cid>0});assertEquals(1886,queue.entries.count{it.cid==0L})
        assertTrue(queue.entries.first{it.id==queue.currentId}.cid>0)
        assertEquals(PlayMode.SHUFFLE,queue.mode);assertEquals(1887,queue.order.toSet().size)
        assertTrue(queue.entries.all{it.source==UpProfile(8,"UP").source});assertEquals(listOf("replace"),f.events)
        assertFalse(vm.state.value.collectionPreparing)
    }
    @Test fun oldestPostsLoadAllPagesAndFilteredSearchDoesNotOverwriteFullBaseline()=runCase{f,vm->
        vm.openUp(UpProfile(8,"音乐 UP"));runCurrent();vm.filterUp(order=UploadOrder.OLDEST);runCurrent()
        val full=vm.state.value.sourceContent!!
        assertEquals(1887,full.items.size);assertFalse(full.hasMore);assertEquals("BV0000001887",full.items.first().bvid)
        val source=UpProfile(8,"UP").source;val prior=f.checkpoints["7" to source]!!
        val mirror=f.mirrors["7" to source]!!
        f.upUploadsAction={mid,p,_,q->assertEquals("现场",q);SourceContentPage(ContentSource(SourceRef(SourceKind.UP_UPLOADS,mid,mid),"UP 的投稿","UP",1),listOf(FavoriteItem(bv,"现场")),p,false)}
        vm.filterUp(query="现场");runCurrent()
        assertEquals(listOf(bv),vm.state.value.sourceContent!!.items.map{it.bvid})
        assertEquals(prior,f.checkpoints["7" to source]);assertEquals(mirror,f.mirrors["7" to source])
        assertFalse("replace" in f.events);assertEquals(1887,vm.state.value.upProfile!!.videos)
    }
    @Test fun savedUpIsLocalAccountScopedAndRemovingItKeepsPlayingQueue()=runCase{f,vm->
        val profile=UpProfile(8,"音乐 UP","//i0.hdslb.com/avatar.jpg")
        vm.openUp(profile);runCurrent();val prior=f.playerState.value
        vm.bookmarkUp(true);runCurrent()
        val saved=vm.state.value.bookmarks.single();assertEquals(profile.source,saved.source);assertEquals(profile.avatar,saved.cover)
        assertEquals("音乐 UP",saved.ownerName);assertEquals(1887,saved.total)
        assertTrue(f.store.bookmarks("guest").isEmpty());assertEquals(0,f.writes)
        vm.bookmarkUp(false);runCurrent();assertTrue(vm.state.value.bookmarks.isEmpty());assertEquals(prior,f.playerState.value)
    }
    @Test fun lateUpSearchCannotOverrideNewQueryOrCancelledSearch()=runCase{f,vm->
        f.upSearchAction={q,p->if(q=="旧")withContext(NonCancellable){delay(1000)};UpSearchPage(listOf(UpProfile(if(q=="旧")8 else 9,q)),p,1,false)}
        vm.searchUps("旧");runCurrent();vm.searchUps("新");runCurrent()
        assertEquals("新",vm.state.value.upSearch!!.items.single().name)
        advanceTimeBy(1000);runCurrent();assertEquals("新",vm.state.value.upSearch!!.items.single().name)
        vm.searchUps("旧");runCurrent();vm.cancelUpSearch();advanceUntilIdle()
        assertNull(vm.state.value.upSearch);assertFalse(vm.state.value.upSearchBusy)
    }
    @Test fun upFailureBeyondFirstPageAndCancelledOldestReadNeverMutateQueueOrMirror()=runCase{f,vm->
        vm.openUp(UpProfile(8,"UP"));runCurrent();val before=vm.state.value.sourceContent;val player=f.playerState.value
        val action=f.upUploadsAction;f.upUploadsAction={mid,p,o,q->if(p==20)throw PlatformFailure("网络请求失败") else action(mid,p,o,q)}
        vm.playCollection();runCurrent();assertEquals(player,f.playerState.value);assertEquals("网络请求失败",vm.state.value.error)
        f.upUploadsAction={mid,p,o,q->if(p==2)withContext(NonCancellable){delay(1000)};action(mid,p,o,q)}
        vm.filterUp(order=UploadOrder.OLDEST);runCurrent();assertTrue(vm.state.value.upLoading)
        vm.cancelUpLoading();advanceUntilIdle();assertFalse(vm.state.value.upLoading);assertFalse(vm.state.value.busy)
        assertEquals(player,f.playerState.value);assertFalse("replace" in f.events)
        assertEquals(before!!.items.map{it.bvid},f.mirrors["7" to UpProfile(8,"UP").source]!!.items.map{it.bvid})
    }
    @Test fun sourceManagementReadsAllPagesKeepsHiddenAndOrderAcrossRefreshWithoutPlaybackOrWrites()=runCase { f,vm->
        val a=ContentSource(SourceRef(SourceKind.PUBLIC_FAVORITES,42,8),"folder","owner",200)
        val b=ContentSource(SourceRef(SourceKind.UP_COLLECTION,42,8,CollectionKind.SEASON),"season","owner",40)
        val c=a.copy(ref=a.ref.copy(id=43),title="new")
        f.followedAction={_,p->SourceListPage(if(p==1)listOf(a) else listOf(a,b,c),p,p==1)}
        vm.loadLibraryManagement(LibrarySection.FOLLOWED);advanceUntilIdle();assertEquals(3,vm.state.value.managementSources.size)
        vm.hideLibrarySource(a.ref,true);advanceUntilIdle();vm.moveLibrarySource(b.ref,0);advanceUntilIdle()
        vm.loadFollowedSources();advanceUntilIdle()
        assertFalse(vm.state.value.sources!!.hasMore)
        assertEquals(listOf(b,c),vm.state.value.library.apply(LibrarySection.FOLLOWED,vm.state.value.sources!!.sources){it.ref})
        vm.loadLibraryManagement(LibrarySection.FOLLOWED);advanceUntilIdle();assertTrue(vm.state.value.library.hidden(a.ref))
        vm.hideLibrarySource(a.ref,false);advanceUntilIdle();assertFalse(vm.state.value.library.hidden(a.ref))
        vm.resetLibraryLayout(true);advanceUntilIdle();assertTrue(vm.state.value.library.layout(LibrarySection.FOLLOWED)!!.order.isEmpty())
        assertEquals(0,f.writes);assertEquals(0,f.audioCalls);assertTrue(f.playerState.value.queue.isEmpty())
    }
    @Test fun failedManagementPaginationAndFailedLocalSaveKeepLayoutAndQueue()=runCase { f,vm->
        val row=ContentSource(SourceRef(SourceKind.PUBLIC_FAVORITES,42,8),"folder","owner",2)
        f.followedAction={_,_->SourceListPage(listOf(row),1,false)}
        vm.loadLibraryManagement(LibrarySection.FOLLOWED);advanceUntilIdle()
        f.failLayoutWrite=true;vm.hideLibrarySource(row.ref,true);advanceUntilIdle()
        assertFalse(vm.state.value.library.hidden(row.ref));assertNotNull(vm.state.value.error)
        f.failLayoutWrite=false;vm.hideLibrarySource(row.ref,true);advanceUntilIdle();val original=vm.state.value.library
        f.followedAction={_,p->if(p==2)throw PlatformFailure("network") else SourceListPage(listOf(row),1,true)}
        vm.loadLibraryManagement(LibrarySection.FOLLOWED);advanceUntilIdle()
        assertFalse(vm.state.value.managementReady);assertFalse(vm.state.value.managementLoading);assertEquals(original,vm.state.value.library)
        assertEquals(0,f.writes);assertEquals(0,f.audioCalls)
    }
    @Test fun managementCancelledAndAccountChangedNeverExposePriorLayout()=runCase { f,vm->
        val row=ContentSource(SourceRef(SourceKind.PUBLIC_FAVORITES,42,8),"folder","owner",2)
        f.followedAction={_,_->SourceListPage(listOf(row),1,false)}
        vm.loadLibraryManagement(LibrarySection.FOLLOWED);advanceUntilIdle();vm.hideLibrarySource(row.ref,true);advanceUntilIdle()
        f.followedAction={_,p->delay(1000);SourceListPage(listOf(row),p,false)}
        vm.loadLibraryManagement(LibrarySection.FOLLOWED);runCurrent();vm.cancelLibraryManagement();advanceUntilIdle()
        assertFalse(vm.state.value.busy);assertFalse(vm.state.value.managementReady);assertTrue(vm.state.value.library.hidden(row.ref))
        f.memberAccount=Account(8,"other");vm.checkAccount();advanceUntilIdle()
        assertTrue(vm.state.value.library.layouts.isEmpty());assertTrue(vm.state.value.managementSources.isEmpty());assertTrue(f.layouts["7"]!!.hidden(row.ref))
    }

    @Test fun syncedFolderCreationValidatesNameRefreshesFoldersAndPreservesLocalLayout()=runCase { f,vm->
        vm.loadLibraryManagement(LibrarySection.MINE);advanceUntilIdle();vm.hideLibrarySource(f.source,true);advanceUntilIdle()
        val layout=vm.state.value.library;val player=f.playerState.value;var note=""
        vm.createFolder(" ",false);advanceUntilIdle();assertEquals(0,f.createCalls)
        vm.createFolder("x".repeat(21),false);advanceUntilIdle();assertEquals(0,f.createCalls)
        vm.createFolder("  私密歌单  ",true){note=it};advanceUntilIdle()
        assertEquals(1,f.createCalls);assertTrue(f.createdPrivate);assertEquals("私密歌单",vm.state.value.folders.last().title)
        assertEquals("已回读确认创建成功",note);assertFalse(vm.state.value.folderCreationPending)
        assertEquals(layout,vm.state.value.library);assertEquals(player,f.playerState.value);assertEquals(0,f.audioCalls)
        vm.logout();advanceUntilIdle();vm.createFolder("guest",false);advanceUntilIdle();assertEquals(1,f.createCalls)
    }
    @Test fun uncertainFolderCreationBlocksRepeatedWritesAndReconcilesReadOnly()=runCase { f,vm->
        f.createResult=FolderCreationOutcome(true,"结果尚未确认，不会重复提交")
        vm.inspectFolderCreation();advanceUntilIdle();assertTrue(vm.state.value.folderCreationPending)
        vm.createFolder("音乐",false);advanceUntilIdle();assertEquals(0,f.createCalls)
        vm.reconcileFolderCreation();advanceUntilIdle();assertEquals(1,f.reconcileCalls);assertEquals(0,f.createCalls)
        f.createResult=FolderCreationOutcome(true,"结果尚未确认，不会重复提交")
        var callback=false;vm.createFolder("音乐",false){callback=true};advanceUntilIdle()
        assertTrue(callback);assertTrue(vm.state.value.folderCreationPending);assertEquals(1,f.createCalls)
        vm.createFolder("音乐",false);advanceUntilIdle();assertEquals(1,f.createCalls)
    }
    @Test fun collectionPlusReadsVerifiedSeasonBeforeFollowAndRejectsOtherLinksOrWrongIdentity()=runCase { f,vm->
        var ready=0;val player=f.playerState.value
        for(url in listOf("https://www.bilibili.com/list/ml42","https://space.bilibili.com/8/lists/42?type=series","https://example.com/8/lists/42")) {
            vm.openCollectionLink(url){ready++};advanceUntilIdle()
        }
        assertEquals(0,ready);assertEquals(0,f.followedReads)
        vm.openCollectionLink("https://space.bilibili.com/8/lists/42?type=season"){ready++};advanceUntilIdle()
        assertEquals(1,ready);assertEquals(SourceRef(SourceKind.UP_COLLECTION,42,8,CollectionKind.SEASON),vm.state.value.sourceContent!!.source.ref)
        assertEquals(false,vm.state.value.sourceFollowed);assertEquals(1,f.followedReads)
        f.sourceAction={_,p->SourceContentPage(ContentSource(SourceRef(SourceKind.UP_COLLECTION,42,9,CollectionKind.SEASON),"wrong","owner",0),emptyList(),p,false)}
        vm.openCollectionLink("https://space.bilibili.com/8/lists/42"){ready++};advanceUntilIdle()
        assertEquals(1,ready);assertEquals("合集身份不匹配",vm.state.value.error)
        assertEquals(player,f.playerState.value);assertEquals(0,f.writes);assertEquals(0,f.audioCalls)
    }
    @Test fun dismissedCollectionLinkRejectsLateNetworkResponseAndNavigation()=runCase { f,vm->
        f.resolveDelay=1000;var ready=false
        vm.openCollectionLink("https://space.bilibili.com/8/lists/42"){ready=true};runCurrent()
        assertTrue(vm.state.value.collectionLinkReading);vm.cancelCollectionLink();advanceUntilIdle()
        assertFalse(ready);assertNull(vm.state.value.sourceContent);assertFalse(vm.state.value.busy)
        assertFalse(vm.state.value.collectionLinkReading);assertEquals(0,f.followedReads);assertEquals(0,f.writes)
    }

    @Test fun popularMusicKeepsIndependentStateAndRetainsVerifiedRowsOnFailure()=runCase { f,vm->
        val row=PopularMusic(bv,"song","cover","UP",6000000,42)
        f.musicAction={delay(100);listOf(row,row.copy(plays=4999999))}
        vm.loadPopularMusic();vm.loadPopularMusic();runCurrent();assertTrue(vm.state.value.popularMusicBusy)
        advanceUntilIdle();assertEquals(1,f.musicCalls);assertEquals(listOf(row),vm.state.value.popularMusic)
        vm.loadPopularMusic();advanceUntilIdle();assertEquals(1,f.musicCalls)
        f.musicAction={throw PlatformFailure("restricted",-412)};vm.loadPopularMusic(true);advanceUntilIdle()
        assertEquals(listOf(row),vm.state.value.popularMusic);assertEquals("restricted",vm.state.value.popularMusicError)
        assertEquals(HomeCategory.ALL,vm.state.value.recommendationCategory)
        assertEquals(0,f.audioCalls);assertEquals(0,f.videoCalls);assertEquals(0,f.writes);assertNull(f.lastSnapshot)
    }
    @Test fun popularMusicDropsLateAccountResponseAndManualRefreshReplacesRows()=runCase { f,vm->
        f.musicAction={withContext(NonCancellable){delay(1000)};listOf(PopularMusic(bv,"old","","UP",6000000))}
        vm.loadPopularMusic();runCurrent();f.memberAccount=Account(9,"new");vm.checkAccount();advanceUntilIdle()
        assertTrue(vm.state.value.popularMusic.isEmpty());assertFalse(vm.state.value.popularMusicLoaded)
        f.musicAction={delay(100);listOf(PopularMusic(bv,"new","","UP",7000000))}
        val refresh=launch{vm.refreshHome()};runCurrent();assertFalse(refresh.isCompleted);advanceUntilIdle()
        assertTrue(refresh.isCompleted);assertEquals("new",vm.state.value.popularMusic.single().title)
        assertEquals(0,f.audioCalls);assertEquals(0,f.videoCalls);assertEquals(0,f.writes)
    }
    @Test fun recommendedUpsUseMusicOwnersIndependentlyRetainOnFailureAndNeverResolveAudio()=runCase { f,vm->
        val calls=mutableListOf<HomeCategory>()
        f.categoryAction={c->calls+=c;listOf(Recommendation("a",c.label,null,author="UP",owner=8,avatar="avatar"))}
        vm.loadHomeUps();advanceUntilIdle();assertEquals(listOf(HomeCategory.MUSIC),calls)
        assertEquals(listOf(8L),vm.state.value.homeUps.map{it.mid});assertEquals(HomeCategory.ALL,vm.state.value.recommendationCategory)
        vm.loadHomeUps();advanceUntilIdle();assertEquals(1,calls.size)
        vm.selectRecommendationCategory(HomeCategory.STUDY);advanceUntilIdle()
        assertEquals("学习",vm.state.value.recommendations.first().title);assertEquals(8L,vm.state.value.homeUps.first().mid)
        f.categoryAction={throw PlatformFailure("network")};vm.loadHomeUps(true);advanceUntilIdle()
        assertEquals(8L,vm.state.value.homeUps.first().mid);assertEquals("network",vm.state.value.homeUpsError)
        assertFalse(vm.state.value.homeUpsBusy);assertEquals(0,f.audioCalls);assertEquals(0,f.videoCalls);assertEquals(0,f.writes);assertNull(f.lastSnapshot)
    }
    @Test fun upRefreshPassesPreviousIdentitiesAndKeepsTheOldCardsWhileLoading()=runCase{f,vm->
        val old=UpProfile(8,"old UP");val fresh=UpProfile(9,"new UP")
        f.creatorAction={listOf(old)};vm.loadHomeUps();advanceUntilIdle()
        f.creatorAction={previous->assertEquals(setOf(8L),previous);delay(1000);listOf(fresh)}
        vm.loadHomeUps(true);runCurrent();assertEquals(listOf(old),vm.state.value.homeUps);assertTrue(vm.state.value.homeUpsBusy)
        advanceUntilIdle();assertEquals(listOf(fresh),vm.state.value.homeUps);assertFalse(vm.state.value.homeUpsBusy)
        f.creatorAction={emptyList()};vm.loadHomeUps(true);advanceUntilIdle()
        assertEquals(listOf(fresh),vm.state.value.homeUps);assertNotNull(vm.state.value.homeUpsError)
        assertEquals(0,f.audioCalls);assertEquals(0,f.writes)
    }
    @Test fun accountSwitchDropsLateRecommendedUpResponseAndAllowsNewRead()=runCase { f,vm->
        f.categoryAction={withContext(NonCancellable){delay(1000)};listOf(Recommendation("a","old",null,author="old UP",owner=8))}
        vm.loadHomeUps();runCurrent();assertTrue(vm.state.value.homeUpsBusy)
        f.memberAccount=Account(9,"new");vm.checkAccount();advanceUntilIdle()
        assertTrue(vm.state.value.homeUps.isEmpty());assertFalse(vm.state.value.homeUpsBusy);assertFalse(vm.state.value.homeUpsLoaded)
        f.categoryAction={listOf(Recommendation("b","new",null,author="new UP",owner=9))};vm.loadHomeUps();advanceUntilIdle()
        assertEquals(9L,vm.state.value.homeUps.single().mid);assertEquals(0,f.audioCalls);assertEquals(0,f.writes)
    }
    @Test fun allCategoriesPreloadWithTwoConcurrentReadsAndTapsReuseResults()=runCase { f,vm->
        var active=0;var peak=0;val reads=mutableListOf<HomeCategory>()
        f.categoryAction={c->reads+=c;active++;peak=maxOf(peak,active);delay(100);active--;listOf(Recommendation("a",c.label,null))}
        vm.loadRecommendations();advanceUntilIdle();val original=vm.state.value.recommendations
        vm.preloadRecommendationCategories();runCurrent();assertEquals(2,peak);assertEquals(original,vm.state.value.recommendations)
        vm.selectRecommendationCategory(HomeCategory.MUSIC);runCurrent();advanceUntilIdle()
        assertEquals(6,reads.size);assertEquals(6,reads.distinct().size);assertEquals("音乐",vm.state.value.recommendations.single().title)
        for(c in HomeCategory.entries.filter{it!=HomeCategory.ALL&&it!=HomeCategory.LIVE}) {
            vm.selectRecommendationCategory(c)
            assertEquals(c.label,vm.state.value.recommendations.single().title);assertFalse(vm.state.value.recommendationBusy)
        }
        vm.preloadRecommendationCategories();advanceUntilIdle();assertEquals(6,reads.size)
        assertEquals(0,f.audioCalls);assertEquals(0,f.videoCalls);assertEquals(0,f.writes);assertNull(f.lastSnapshot)
    }
    @Test fun manualHomeRefreshReplacesCachedCategoriesAndCreatorsAndWaitsWithoutPlayback()=runCase { f,vm->
        f.categoryAction={c->listOf(Recommendation("old",c.label,null,author="old UP",owner=8))}
        vm.loadRecommendations();vm.loadHomeUps();vm.preloadRecommendationCategories();advanceUntilIdle()
        vm.selectRecommendationCategory(HomeCategory.STUDY);advanceUntilIdle()
        val reads=mutableListOf<HomeCategory>();val retries=f.retryCalls;val allReads=f.recommendationCalls
        f.categoryAction={c->reads+=c;delay(100);listOf(Recommendation("new","new ${c.label}",null,author="new UP",owner=9))}
        val refresh=launch{vm.refreshHome()};runCurrent();assertFalse(refresh.isCompleted)
        advanceUntilIdle();assertTrue(refresh.isCompleted);assertEquals(retries+1,f.retryCalls)
        assertEquals("new 学习",vm.state.value.recommendations.single().title)
        assertEquals(9L,vm.state.value.homeUps.single().mid)
        assertEquals(allReads+1,f.recommendationCalls)
        for(c in HomeCategory.entries.filter{it!=HomeCategory.ALL&&it!=HomeCategory.LIVE}) {
            vm.selectRecommendationCategory(c);assertEquals("new ${c.label}",vm.state.value.recommendations.single().title)
            assertFalse(vm.state.value.recommendationBusy)
        }
        assertTrue(reads.containsAll(HomeCategory.entries.filter{it!=HomeCategory.ALL&&it!=HomeCategory.LIVE}))
        assertEquals(0,f.audioCalls);assertEquals(0,f.videoCalls);assertEquals(0,f.writes);assertNull(f.lastSnapshot)
    }
    @Test fun failedPreloadKeepsOtherTabsAndAccountSwitchNeverUsesPriorCache()=runCase { f,vm->
        f.categoryAction={c->if(c==HomeCategory.EMOTION)throw PlatformFailure("restricted",-412);listOf(Recommendation("a","old ${c.label}",null))}
        vm.preloadRecommendationCategories();advanceUntilIdle();vm.selectRecommendationCategory(HomeCategory.STUDY)
        assertEquals("old 学习",vm.state.value.recommendations.single().title)
        vm.selectRecommendationCategory(HomeCategory.EMOTION);advanceUntilIdle();assertEquals("restricted",vm.state.value.recommendationError)
        f.categoryAction={c->withContext(NonCancellable){delay(1000)};listOf(Recommendation("a","late ${c.label}",null))}
        vm.preloadRecommendationCategories(true);runCurrent();f.memberAccount=Account(9,"new");vm.checkAccount();advanceUntilIdle()
        f.categoryAction={c->listOf(Recommendation("b","new ${c.label}",null))};vm.selectRecommendationCategory(HomeCategory.MUSIC);advanceUntilIdle()
        assertEquals("new 音乐",vm.state.value.recommendations.single().title);assertEquals(0,f.audioCalls);assertEquals(0,f.writes)
    }
    @Test fun defaultFavoriteOnboardingPersistsPerAccountAndNeverWritesRemotely()=runCase { f,vm->
        vm.ensureDefaultFavoriteFolder();advanceUntilIdle();assertTrue(vm.state.value.defaultFolderPrompt)
        vm.selectDefaultFavoriteFolder(FavoriteFolder(404,"not owned",0));advanceUntilIdle()
        assertTrue(f.settings.current().defaultFavoriteFolders.isEmpty())
        vm.selectDefaultFavoriteFolder(vm.state.value.folders.single());advanceUntilIdle()
        assertEquals(DefaultFavoriteFolder(9,"音乐"),f.settings.current().defaultFavoriteFolders["7"])
        assertFalse(vm.state.value.defaultFolderPrompt)
        f.memberAccount=Account(8,"second");vm.loginFinished();advanceUntilIdle()
        assertTrue(vm.state.value.defaultFolderPrompt)
        vm.selectDefaultFavoriteFolder(vm.state.value.folders.single());advanceUntilIdle()
        assertEquals(setOf("7","8"),f.settings.current().defaultFavoriteFolders.keys)
        vm.logout();advanceUntilIdle();assertEquals(2,f.settings.current().defaultFavoriteFolders.size)
        assertEquals(0,f.writes);assertEquals(0,f.audioCalls)
    }
    @Test fun directFavoriteFreezesRequestedVideoAndBusyTapsDoNotDuplicateThenNextTapRemoves()=runCase { f,vm->
        vm.loadFolders();advanceUntilIdle();vm.selectDefaultFavoriteFolder(vm.state.value.folders.single());advanceUntilIdle()
        vm.inspect(bv);advanceUntilIdle()
        f.videoAction={id->f.video.copy(bvid=id,aid=22)}
        f.favoriteFoldersAction={_,aid->delay(100);listOf(FavoriteFolder(9,"音乐",0,contains=if(aid==null)null else f.member))}
        val other="BV1U1421r7SM";val reports=mutableListOf<String>()
        vm.toggleDefaultFavorite(other){reports+=it};runCurrent();vm.toggleDefaultFavorite(bv){reports+=it}
        f.playerState.value=f.playerState.value.copy(title="another playing track",positionMs=99000)
        advanceUntilIdle()
        assertEquals(listOf(Triple(7L,22L,9L)),f.favoriteWrites);assertEquals(bv,vm.state.value.video!!.bvid)
        vm.toggleDefaultFavorite(other){reports+=it};advanceUntilIdle();assertEquals(2,f.writes)
        assertTrue(reports.last().contains("取消收藏"));assertEquals(false,vm.state.value.currentFavoritePresent);assertFalse(f.member)
        assertEquals(0,f.audioCalls);assertFalse("replace" in f.events)
    }
    @Test fun removedDefaultFolderRequiresNewChoiceAndUnknownMembershipCannotWrite()=runCase { f,vm->
        vm.loadFolders();advanceUntilIdle();vm.selectDefaultFavoriteFolder(vm.state.value.folders.single());advanceUntilIdle()
        f.favoriteFoldersAction={_,_->emptyList()};vm.toggleDefaultFavorite(bv);advanceUntilIdle()
        assertTrue(vm.state.value.defaultFolderPrompt);assertNull(f.settings.current().defaultFavoriteFolders["7"]);assertEquals(0,f.writes)
        f.favoriteFoldersAction={_,_->listOf(FavoriteFolder(9,"音乐",0))}
        vm.selectDefaultFavoriteFolder(FavoriteFolder(9,"音乐",0));advanceUntilIdle()
        vm.toggleDefaultFavorite(bv);advanceUntilIdle();assertEquals(0,f.writes);assertNull(vm.state.value.currentFavoritePresent)
        assertEquals(9L,f.settings.current().defaultFavoriteFolders["7"]!!.id)
    }
    @Test fun unknownRemovalCannotBecomeFalseSuccessOrBeRetried()=runCase { f,vm->
        vm.selectDefaultFavoriteFolder(FavoriteFolder(9,"音乐",0));advanceUntilIdle()
        f.member=true;f.favoriteOutcome=MutationOutcome.UNKNOWN
        val reports=mutableListOf<String>()
        vm.toggleDefaultFavorite(bv){reports+=it};advanceUntilIdle()
        assertEquals(false,f.pending["7"]!!.add);assertEquals(1,f.writes)
        assertNull(vm.state.value.currentFavoritePresent);assertTrue(reports.single().contains("待核对"))
        vm.toggleDefaultFavorite(bv);advanceUntilIdle();assertEquals(1,f.writes)
        vm.reconcileFavorite();advanceUntilIdle();assertEquals(1,f.writes);assertTrue(f.member)
        f.favoriteOutcome=MutationOutcome.CONFIRMED;vm.toggleDefaultFavorite(bv);advanceUntilIdle()
        assertEquals(2,f.writes);assertFalse(f.member);assertEquals(false,vm.state.value.currentFavoritePresent)
    }
    @Test fun togglingUsesFreshMembershipRatherThanCachedFilledStar()=runCase { f,vm->
        vm.selectDefaultFavoriteFolder(FavoriteFolder(9,"音乐",0));advanceUntilIdle()
        f.member=true;vm.readCurrentFavorite(bv);advanceUntilIdle();assertEquals(true,vm.state.value.currentFavoritePresent)
        f.member=false;vm.toggleDefaultFavorite(bv);advanceUntilIdle()
        assertEquals(1,f.writes);assertTrue(f.member);assertEquals(true,vm.state.value.currentFavoritePresent)
    }
    @Test fun defaultFolderChangedDuringReadCannotRemoveOldFolder()=runCase { f,vm->
        vm.selectDefaultFavoriteFolder(FavoriteFolder(9,"音乐",0));advanceUntilIdle()
        f.favoriteFoldersAction={_,_->
            f.settings.update(UserSettings(defaultFavoriteFolders=mapOf("7" to DefaultFavoriteFolder(404,"新默认"))))
            listOf(FavoriteFolder(9,"音乐",1,true))
        }
        vm.toggleDefaultFavorite(bv);advanceUntilIdle()
        assertEquals(0,f.writes);assertTrue(vm.state.value.error!!.contains("默认收藏夹已改变"))
    }
    @Test fun returningFromNotificationInvalidatesCachedFavoriteStateWithoutWriting()=runCase { f,vm->
        vm.selectDefaultFavoriteFolder(FavoriteFolder(9,"音乐",0));advanceUntilIdle()
        f.member=true;vm.readCurrentFavorite(bv);advanceUntilIdle();assertEquals(true,vm.state.value.currentFavoritePresent)
        f.member=false;vm.backgrounded();vm.foregrounded();advanceUntilIdle()
        assertEquals(false,vm.state.value.currentFavoritePresent)
        assertEquals(0,f.writes);assertEquals(0,f.audioCalls)
    }
    @Test fun notificationUnknownRemovalIsJournaledAndNextTapCannotReverseOrRepeatIt()=runCase { f,_->
        f.settings.update(UserSettings(defaultFavoriteFolders=mapOf("7" to DefaultFavoriteFolder(9,"音乐"))))
        f.member=true;f.favoriteOutcome=MutationOutcome.UNKNOWN
        val action=NotificationFavorite(f.accounts,f.settings,f.content,f.folders)
        assertTrue(action.toggle(bv){true}.message.contains("待核对"));assertEquals(false,f.pending["7"]!!.add)
        assertTrue(runCatching{action.toggle(bv){true}}.exceptionOrNull() is PlatformFailure)
        assertEquals(1,f.writes);assertTrue(f.member)
    }
    @Test fun uncertainDirectFavoriteKeepsJournalAndBlocksNextTapWithoutFalseSuccess()=runCase { f,vm->
        vm.loadFolders();advanceUntilIdle();vm.selectDefaultFavoriteFolder(vm.state.value.folders.single());advanceUntilIdle()
        f.favoriteOutcome=MutationOutcome.UNKNOWN;val reports=mutableListOf<String>()
        vm.toggleDefaultFavorite(bv){reports+=it};advanceUntilIdle()
        assertEquals(1,f.writes);assertNotNull(vm.state.value.mutationPending);assertNull(vm.state.value.currentFavoritePresent)
        vm.toggleDefaultFavorite(bv){reports+=it};advanceUntilIdle();assertEquals(1,f.writes)
        assertTrue(reports.first().contains("待核对"))
        vm.reconcileFavorite();advanceUntilIdle();assertNull(vm.state.value.mutationPending);assertEquals(1,f.writes)
        f.favoriteOutcome=MutationOutcome.CONFIRMED;vm.toggleDefaultFavorite(bv);advanceUntilIdle();assertEquals(2,f.writes)
    }
    @Test fun favoriteSelectorKeepsItsVideoTargetAndDoesNotChangeDefaultFolder()=runCase { f,vm->
        vm.loadFolders();advanceUntilIdle();vm.selectDefaultFavoriteFolder(vm.state.value.folders.single());advanceUntilIdle()
        val other="BV1U1421r7SM";f.videoAction={id->f.video.copy(bvid=id,aid=22)}
        vm.chooseFavorite(other);advanceUntilIdle();assertEquals(22L,vm.state.value.favoriteTarget!!.aid)
        f.videoAction=null;vm.inspect(bv);advanceUntilIdle();vm.modifyFavorite(vm.state.value.folders.single(),true);advanceUntilIdle()
        assertEquals(Triple(7L,22L,9L),f.favoriteWrites.single());assertEquals(9L,f.settings.current().defaultFavoriteFolders["7"]!!.id)
        vm.dismissFavorite();assertNull(vm.state.value.favoriteTarget);assertEquals(0,f.audioCalls)
    }
    @Test fun defaultCreationUsesNewVerifiedIdentityAndPendingCreationNeverSetsDefault()=runCase { f,vm->
        vm.openDefaultFavoriteFolder();advanceUntilIdle()
        vm.createFolder("音乐",true,true);advanceUntilIdle()
        assertEquals(DefaultFavoriteFolder(101,"音乐"),f.settings.current().defaultFavoriteFolders["7"])
        assertEquals(1,f.createCalls);assertFalse(vm.state.value.defaultFolderPrompt)
        vm.openDefaultFavoriteFolder();advanceUntilIdle();f.createResult=FolderCreationOutcome(true,"等待核对")
        vm.createFolder("another",false,true);advanceUntilIdle()
        assertEquals(101L,f.settings.current().defaultFavoriteFolders["7"]!!.id);assertTrue(vm.state.value.defaultFolderPrompt)
        assertTrue(vm.state.value.folderCreationPending);assertEquals(0,f.writes)
    }
    @Test fun accountChangeCancelsDelayedFavoriteBeforeAnyWriteOrStaleReadIndicator()=runCase { f,vm->
        vm.loadFolders();advanceUntilIdle();vm.selectDefaultFavoriteFolder(vm.state.value.folders.single());advanceUntilIdle()
        f.favoriteFoldersAction={_,_->withContext(NonCancellable){delay(1000)};listOf(FavoriteFolder(9,"音乐",0,false))}
        vm.toggleDefaultFavorite(bv);runCurrent();f.memberAccount=Account(8,"other");vm.checkAccount();advanceUntilIdle()
        assertEquals(0,f.writes);assertNull(vm.state.value.currentFavoritePresent)
        assertNull(f.settings.current().defaultFavoriteFolders["8"]);assertNotNull(f.settings.current().defaultFavoriteFolders["7"])
    }
    @Test fun defaultFolderSurvivesReadFailureAndRecreatedModelSkipsOnboarding()=runCase { f,vm->
        vm.loadFolders();advanceUntilIdle();vm.selectDefaultFavoriteFolder(vm.state.value.folders.single());advanceUntilIdle()
        f.favoriteFoldersAction={_,_->throw PlatformFailure("network")}
        vm.toggleDefaultFavorite(bv);advanceUntilIdle();assertEquals(9L,f.settings.current().defaultFavoriteFolders["7"]!!.id);assertEquals(0,f.writes)
        val owner=androidx.lifecycle.ViewModelStore();val recreated=f.vm();owner.put("recreated",recreated)
        try {advanceUntilIdle();recreated.ensureDefaultFavoriteFolder();advanceUntilIdle();assertFalse(recreated.state.value.defaultFolderPrompt)}finally{owner.clear()}
    }

    private class CloudFixture(val f:Fixture) {
        val rows=MutableStateFlow<Map<String,RemoteHistoryData>>(emptyMap());var reads=0;var posts=0;var clears=0;var fail=false
        var remote=listOf(RemoteHistoryItem(1,"BV1xx411c7mD",2,2,"云端歌曲",positionMs=42000,viewedAt=50000))
        val cloud=object:RemoteHistoryStore {
            override fun observe(account:String)=rows.map{it[account] ?: RemoteHistoryData()}
            override suspend fun read(account:String)=rows.value[account] ?: RemoteHistoryData()
            override suspend fun changeMode(account:String,data:RemoteHistoryData,cutoff:Long){clears++;f.histories[account]?.value=emptyList();rows.value=rows.value+(account to data)}
            override suspend fun save(account:String,data:RemoteHistoryData,expectedEpoch:Long){check(read(account).epoch==expectedEpoch);rows.value=rows.value+(account to data)}
        }
        val sync=RemoteHistorySync(object:RemoteHistoryPort {
            override suspend fun paused()=false
            override suspend fun page(cursor:HistoryCursor?):RemoteHistoryPage{reads++;if(fail)throw PlatformFailure("offline");return RemoteHistoryPage(remote,null)}
            override suspend fun report(item:RemoteHistoryItem,account:String):RemoteHistoryItem{posts++;return item}
            override suspend fun delete(key:String?,account:String){posts++;remote=emptyList()}
        },cloud,f.accounts,f.clock)
    }
    private fun cloudCase(block:suspend TestScope.(Fixture,CloudFixture,MainViewModel)->Unit)=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));val f=Fixture();val c=CloudFixture(f);val vm=f.vm(cloud=c.cloud,sync=c.sync);val owner=androidx.lifecycle.ViewModelStore();owner.put("cloud",vm)
        try{runCurrent();block(f,c,vm)}finally{owner.clear();Dispatchers.resetMain()}
    }
    @Test fun syncDefaultOffDoesNotReadCloudAndFailedEnableRetainsLocal()=cloudCase { f,c,vm->
        f.histories["7"]!!.value=listOf(LocalHistoryEntry("7",VideoRef(bv,1,1),"local",null,1000,1000));runCurrent()
        assertFalse(vm.state.value.remoteHistory.enabled);assertEquals(0,c.reads)
        c.fail=true;vm.setHistorySync(true,confirmation="是");runCurrent();assertEquals(0,c.clears);assertEquals("local",vm.state.value.history.single().title);assertFalse(vm.state.value.remoteHistory.enabled)
        assertEquals(0,c.posts)
    }
    @Test fun cloudResumeIgnoresLocalPrivacySwitchAndStalePausedSnapshot()=cloudCase { f,c,vm->
        vm.setHistorySync(true,confirmation="是");runCurrent();assertTrue(vm.state.value.remoteHistory.enabled);assertEquals(1,c.clears)
        f.settings.update(f.settings.current().copy(historyEnabled=false));runCurrent()
        f.savedPlayback=PlaybackSnapshot(ResumeSnapshot(account="7",entries=listOf(QueueEntry("old",bv,2,2,"old")),order=listOf("old"),currentId="old",positionMs=1234),1000)
        vm.playVideo(bv);runCurrent();assertEquals(2,f.lastSnapshot!!.entries.first{it.id==f.lastSnapshot!!.currentId}.part);assertEquals(42000L,f.lastSnapshot!!.positionMs);assertEquals(0,c.posts)
        vm.setHistorySync(false);runCurrent();assertFalse(vm.state.value.remoteHistory.enabled);assertTrue(vm.state.value.history.isEmpty());assertEquals(2,c.clears)
    }
    @Test fun cloudDeletionIsExplicitAndLogoutHousekeepingNeverDeletesOfficialHistory()=cloudCase { f,c,vm->
        vm.setHistorySync(true,confirmation="是");runCurrent();vm.deleteLocalHistory(vm.state.value.history.single());runCurrent();assertEquals(1,c.posts)
        f.settings.update(f.settings.current().copy(historyDeleteOnExit=true));runCurrent();vm.logout();runCurrent();assertEquals(1,c.posts);assertFalse(vm.state.value.remoteHistory.enabled);assertTrue(c.cloud.read("7").enabled)
    }
    @Test fun confirmationForPreviousAccountCannotClearNewAccount()=cloudCase { f,c,vm->
        f.memberAccount=Account(8,"other");vm.checkAccount();runCurrent()
        vm.setHistorySync(true,"7","是");runCurrent();assertEquals(0,c.clears);assertEquals(0,c.reads);assertTrue(vm.state.value.message.contains("账号已变化"))
    }

    @Test fun enablingWithoutTypedYesCannotPauseReadOrClearHistory()=cloudCase { f,c,vm->
        f.events.clear();vm.setHistorySync(true);runCurrent();assertTrue(vm.state.value.message.contains("请输入“是”"));assertEquals(0,c.clears);assertEquals(0,c.reads);assertTrue(f.events.isEmpty())
        vm.setHistorySync(true,confirmation="否");runCurrent();assertFalse(vm.state.value.remoteHistory.enabled);assertEquals(0,c.clears);assertEquals(0,c.reads)
    }

    @Test fun videoDetailsRejectLateVideoAndAccountResultsWithoutPlayback()=runCase { f,vm->
        f.videoAction={bvid->if(bvid==bv)withContext(NonCancellable){delay(1000)};f.video.copy(bvid=bvid,description=bvid)}
        val other="BV1xx411c7mE"
        vm.openVideoDetails(bv);runCurrent();vm.openVideoDetails(other);runCurrent()
        assertEquals(other,vm.state.value.videoDetails.video!!.bvid)
        advanceTimeBy(1001);runCurrent();assertEquals(other,vm.state.value.videoDetails.bvid)
        vm.openVideoDetails(bv);runCurrent();f.accounts.logout();runCurrent();advanceTimeBy(1001);runCurrent()
        assertNull(vm.state.value.videoDetails.bvid);assertEquals(0,f.audioCalls)
        assertFalse(f.playerState.value.requested);assertFalse(f.events.contains("replace"))
    }
    @Test fun closedDetailsDropLateCompletionAndLiveDoesNotReadDetails()=runCase { f,vm->
        f.videoAction={withContext(NonCancellable){delay(1000)};f.video.copy(description="late")}
        vm.openVideoDetails(bv);runCurrent();vm.closeVideoDetails();advanceTimeBy(1001);runCurrent()
        assertNull(vm.state.value.videoDetails.video)
        f.playerState.value=f.playerState.value.copy(live=true);runCurrent()
        val reads=f.videoCalls;vm.openVideoDetails(bv);runCurrent();assertEquals(reads,f.videoCalls)
        assertEquals(0,f.audioCalls);assertFalse(f.playerState.value.requested)
    }

}

