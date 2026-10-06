package app.bililisten.platform

import android.content.Intent
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import android.content.res.Configuration
import android.graphics.Rect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.*
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.bililisten.*
import app.bililisten.shared.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@androidx.media3.common.util.UnstableApi @RunWith(AndroidJUnit4::class)
class UpUiTest {
    private val i=InstrumentationRegistry.getInstrumentation()
    private val app=i.targetContext.applicationContext as ListenApplication
    private fun find(label:String):AccessibilityNodeInfo? {
        fun visit(n:AccessibilityNodeInfo?):AccessibilityNodeInfo? {
            if(n==null||!n.refresh()||!n.isVisibleToUser)return null
            if(n.text?.toString()==label||n.contentDescription?.toString()==label)return n
            for(k in 0 until n.childCount)visit(n.getChild(k))?.let{return it}
            return null
        };return visit(i.uiAutomation.rootInActiveWindow)
    }
    private fun waitFor(label:String) {val end=System.currentTimeMillis()+12000;while(System.currentTimeMillis()<end){if(find(label)!=null)return;Thread.sleep(50)};error("Missing $label")}
    private fun click(label:String) {
        waitFor(label);i.waitForIdleSync();val leaf=find(label) ?: error("Missing $label")
        val bounds=Rect();leaf.getBoundsInScreen(bounds)
        var n:AccessibilityNodeInfo?=leaf;while(n!=null&&!n.isClickable)n=n.parent
        if(n?.performAction(AccessibilityNodeInfo.ACTION_CLICK)!=true) {
            assertFalse("Visible bounds for $label",bounds.isEmpty)
            val at=android.os.SystemClock.uptimeMillis()
            for(action in listOf(android.view.MotionEvent.ACTION_DOWN,android.view.MotionEvent.ACTION_UP)) {
                val event=android.view.MotionEvent.obtain(at,android.os.SystemClock.uptimeMillis(),action,bounds.centerX().toFloat(),bounds.centerY().toFloat(),0)
                event.source=android.view.InputDevice.SOURCE_TOUCHSCREEN
                assertTrue(i.uiAutomation.injectInputEvent(event,true));event.recycle()
            }
        }
        i.waitForIdleSync();Thread.sleep(250)
    }
    private fun toggleRecentSync() {
        fun visit(n:AccessibilityNodeInfo?):AccessibilityNodeInfo? {
            if(n==null||!n.refresh()||!n.isVisibleToUser)return null
            if(n.isCheckable&&n.isEnabled)return n
            for(k in 0 until n.childCount)visit(n.getChild(k))?.let{return it};return null
        }
        val end=System.currentTimeMillis()+10000
        var node=visit(i.uiAutomation.rootInActiveWindow)
        while(node==null&&System.currentTimeMillis()<end){Thread.sleep(50);node=visit(i.uiAutomation.rootInActiveWindow)}
        node=node ?: error("Missing enabled sync switch")
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK));i.waitForIdleSync();Thread.sleep(250)
    }
    private fun input(value:String) {
        fun visit(n:AccessibilityNodeInfo?):AccessibilityNodeInfo? {
            if(n==null||!n.refresh()||!n.isVisibleToUser)return null
            if(n.packageName?.toString()==app.packageName&&n.actionList.any{it.id==AccessibilityNodeInfo.ACTION_SET_TEXT})return n
            for(k in 0 until n.childCount)visit(n.getChild(k))?.let{return it};return null
        }
        val editor=visit(i.uiAutomation.rootInActiveWindow) ?: error("Missing editor")
        assertTrue(editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply{putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value)}))
    }
    private fun revealUpTab() {
        if(find("UP收藏")==null) {
            var n=find("我的收藏") ?: error("Missing source tabs")
            while(!n.isScrollable&&n.parent!=null)n=n.parent
            assertTrue(n.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD));Thread.sleep(200)
        }
        click("UP收藏")
    }
    private fun shot(name:String) {
        val folder=File(app.filesDir,"up-library-evidence").apply{mkdirs()}
        i.uiAutomation.takeScreenshot()?.let{bmp->File(folder,"fixture-$name.png").outputStream().use{bmp.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bmp.recycle()}
    }
    private class Fixture(private val base:AppDependencies,theme:Theme,library:SourceRepository?=null,seeded:List<CollectionSnapshot> = emptyList(),homeSamples:List<Recommendation> = emptyList(),defaultConfigured:Boolean=true,historyFixture:Boolean=false) {
        val profile=UpProfile(8,"音乐 UP · 示例",avatar=homeSamples.firstOrNull()?.avatar.orEmpty(),signature="这是用于验证界面的示例数据",videos=1887)
        val rows=List(1887){FavoriteItem("BV"+(it+1).toString().padStart(10,'0'),"示例歌曲 ${it+1}",author=profile.name,duration=241)}
        val books=mutableMapOf<Pair<String,SourceRef>,CollectionSnapshot>()
        val checkpoints=mutableMapOf<Pair<String,SourceRef>,SourceCheckpoint>()
        val playback=MutableStateFlow(PlaybackView(connected=true))
        val categoryReads=mutableListOf<HomeCategory>()
        var audioCalls=0;var remoteWrites=0;var folderCreates=0;var createdPrivate=false
        var videoParts=listOf(VideoPart(1,1,"P1",241))
        var remembered:LocalHistoryEntry?=null
        val historyMode=MutableStateFlow(RemoteHistoryData());var historyClears=0;var historyWrites=0
        private val session=MutableStateFlow(AccountSession(SessionStamp("7",1),SessionStatus.AUTHENTICATED,Account(7,"示例账号")))
        val layouts=mutableMapOf<String,LibraryPreferences>()
        val favoriteMembership=mutableSetOf<Pair<Long,Long>>()
        val ownFolders=mutableListOf(FavoriteFolder(1,"音乐收藏夹",2),FavoriteFolder(2,"美食收藏夹",3),FavoriteFolder(3,"生活收藏夹",4))
        val owner=ViewModelStore()
        val vm:MainViewModel
        init {
            seeded.forEach{books[it.account to it.source]=it}
            val deps=base.copy(historySync=null,engagement=null,
                sources=library ?: base.sources,
                recommendations=object:RecommendationRepository {
                    override suspend fun accountHome(previous:Set<String>,ready:suspend (List<PopularMusic>)->Unit)=List(12){index->
                        PopularMusic("BV"+(200+index).toString().padStart(10,'0'),"旅行随记 · 示例 $index","","旅行 UP",23)
                    }.filter{it.bvid !in previous}
                    override suspend fun accountCreators(previous:Set<Long>)=listOf(UpProfile(99,"旅行 UP · 示例"))
                    override suspend fun popularMusic()=listOf(
                        PopularMusic("BV1xx411c7mD","一首好歌 · 示例",homeSamples.firstOrNull()?.cover.orEmpty(),profile.name,5210000),
                        PopularMusic("BV1xx411c7mE","那些年一起听过的歌 · 示例",homeSamples.getOrNull(1)?.cover.orEmpty(),profile.name,12600000,150),
                        PopularMusic("BV1xx411c7mF","音乐合集 · 示例",homeSamples.getOrNull(2)?.cover.orEmpty(),profile.name,25000000,collection=ContentSource(SourceRef(SourceKind.UP_COLLECTION,22,8,CollectionKind.SEASON),"音乐合集 · 示例",profile.name,42)),
                    )
                    override suspend fun creators()=List(10){index->UpProfile(8L+index,if(index==0)profile.name else "音乐 UP · 示例 $index",homeSamples.getOrNull(index)?.avatar ?: profile.avatar)}
                    override suspend fun candidates()=List(5){index->Recommendation("BV1xx411c7m${('D'.code+index).toChar()}","把生活里的小美好，听进耳朵 · 示例 ${index+1}",null,homeSamples.getOrNull(index)?.cover.orEmpty(),profile.name,241,profile.mid,profile.avatar)}
                    override suspend fun candidates(category:HomeCategory):List<Recommendation> { categoryReads+=category;return candidates() }
                },
                library=object:LibraryPreferenceRepository {
                    override suspend fun read(stamp:SessionStamp)=layouts[stamp.account] ?: LibraryPreferences()
                    override suspend fun update(stamp:SessionStamp,change:(LibraryPreferences)->LibraryPreferences):LibraryPreferences {
                        require(stamp==session.value.stamp);return change(read(stamp)).checked(stamp.account).also{layouts[stamp.account]=it}
                    }
                },
                accounts=object:AccountRepository {
                    override val session=this@Fixture.session
                    override suspend fun verify()=session.value.account
                    override suspend fun accept(cookie:String)=error("unused")
                    override suspend fun logout()=error("unused")
                    override fun requireCurrent(stamp:SessionStamp){require(stamp==session.value.stamp)}
                },
                folderCreation=object:FolderCreationRepository {
                    override suspend fun inspect(stamp:SessionStamp)=FolderCreationOutcome(false,"")
                    override suspend fun create(stamp:SessionStamp,title:String,private:Boolean):FolderCreationOutcome {
                        require(stamp==session.value.stamp);folderCreates++;createdPrivate=private
                        ownFolders+=FavoriteFolder(100L+folderCreates,title,0,attr=if(private)1 else 0)
                        return FolderCreationOutcome(false,"已回读确认创建成功")
                    }
                    override suspend fun reconcile(stamp:SessionStamp)=error("unused")
                },
                favorites=object:FavoriteRepository {
                    override suspend fun folders(account:Long,aid:Long?)=ownFolders.map{it.copy(contains=aid?.let{aid->(it.id to aid) in favoriteMembership})}
                    override suspend fun page(folder:Long,page:Int)=error("unused")
                    override suspend fun change(account:Long,aid:Long,folder:Long,add:Boolean):MutationOutcome{require(account==7L);remoteWrites++;if(add)favoriteMembership.add(folder to aid) else favoriteMembership.remove(folder to aid);return MutationOutcome.CONFIRMED}
                    override suspend fun pending(account:String):MutationRecord?=null
                    override suspend fun reconcile(account:Long):FavoriteMembership?=null
                },
                content=object:ContentRepository {
                    override suspend fun video(bvid:String)=Video(bvid,1,"示例歌曲",videoParts,cover=homeSamples.firstOrNull()?.cover.orEmpty(),author=profile.name,owner=8,duration=241)
                    override suspend fun search(keyword:String,page:Int)=SearchPage(emptyList(),page,1,0)
                    override suspend fun audio(bvid:String,cid:Long):String{audioCalls++;error("unused")}
                },
                history=object:LocalHistoryRepository {
                    override suspend fun latestVideo(account:String,bvid:String)=remembered?.takeIf{it.account==account&&it.video.bvid==bvid}
                    override fun observe(account:String)=flowOf(if(homeSamples.isEmpty())emptyList<LocalHistoryEntry>() else List(3){index->LocalHistoryEntry(account,VideoRef("BV1xx411c7mD",index+1L,index+1),"一段好声音，陪你慢慢听 · 示例 ${index+1}",null,45000,1000-index.toLong())})
                    override suspend fun delete(account:String,video:VideoRef?){}
                    override suspend fun prune(account:String,policy:HistoryRetentionPolicy,now:Long){}
                },
                collections=object:CollectionStore {
                    override suspend fun bookmarks(account:String)=books.values.filter{it.account==account&&it.bookmarked}
                    override suspend fun bookmark(account:String,source:ContentSource,saved:Boolean){val prior=books[account to source.ref] ?: CollectionSnapshot(account,source.ref,emptyList(),false,0,0);books[account to source.ref]=prior.copy(bookmarked=saved,title=source.title,ownerName=source.ownerName,total=source.count,cover=source.cover)}
                    override suspend fun collection(account:String,source:SourceRef)=books[account to source]
                    override suspend fun saveCollection(snapshot:CollectionSnapshot){books[snapshot.account to snapshot.source]=snapshot}
                    override suspend fun mutation(account:String):MutationRecord?=null
                    override suspend fun saveMutation(record:MutationRecord)=error("unused")
                    override suspend fun removeMutation(account:String){}
                    override suspend fun updateCheckpoint(account:String,source:SourceRef)=checkpoints[account to source]
                    override suspend fun saveUpdate(checkpoint:SourceCheckpoint){checkpoints[checkpoint.account to checkpoint.source]=checkpoint}
                },
                snapshots=object:PlaybackStateStore {override suspend fun load(account:String):PlaybackSnapshot?=null;override suspend fun checkpoint(snapshot:PlaybackSnapshot,heard:LocalHistoryEntry?){}},
                playback=object:PlaybackPort {
                    override val state=playback
                    override suspend fun replace(snapshot:ResumeSnapshot,play:Boolean){snapshot.checked();state.value=PlaybackView(connected=true,title=snapshot.entries.first{it.id==snapshot.currentId}.title,queue=snapshot.playbackEntries(),currentId=snapshot.currentId,positionMs=snapshot.positionMs,durationMs=200000,mode=snapshot.mode,requested=play,canNext=true)}
                    override suspend fun flush(){};override suspend fun clear(){}
                    override suspend fun mode(mode:PlayMode){};override suspend fun forget(video:VideoRef?){}
                    override suspend fun live(room:LiveRoom,consent:Boolean)=error("unused")
                    override suspend fun edit(edit:QueueEdit,expectedVersion:Long)=error("unused")
                    override fun seekTo(positionMs:Long){};override fun pause(reason:PauseReason){}
                    override fun toggle(){};override fun next(){};override fun previous(){};override fun seekBy(delta:Long){};override fun close(){}
                },
                settings=object:SettingsRepository {override val settings=MutableStateFlow(UserSettings(theme=theme,defaultFavoriteFolders=if(defaultConfigured)mapOf("7" to DefaultFavoriteFolder(1,"音乐收藏夹")) else emptyMap()));override suspend fun update(value:UserSettings){settings.value=value}},
                upLibrary=object:UpLibraryRepository {
                    override suspend fun search(keyword:String,page:Int)=UpSearchPage(listOf(profile),page,1,false)
                    override suspend fun profile(mid:Long)=profile
                    override suspend fun uploads(mid:Long,page:Int,order:UploadOrder,keyword:String):SourceContentPage {
                        val matches=rows.filter{it.title.contains(keyword)}
                        return SourceContentPage(ContentSource(profile.source,"${profile.name}的投稿",profile.name,matches.size),matches.drop((page-1)*30).take(30),page,page*30<matches.size)
                    }
                },
                onAccountConfirmed={},retryAccount={},home=null,downloads=null,subtitles=null,lyrics=null,searchAssist=null,
            )
            val cloud=object:RemoteHistoryStore {
                override fun observe(account:String)=historyMode.map{if(account=="7")it else RemoteHistoryData()}
                override suspend fun read(account:String)=if(account=="7")historyMode.value else RemoteHistoryData()
                override suspend fun changeMode(account:String,data:RemoteHistoryData,cutoff:Long){require(account=="7");historyClears++;remembered=null;historyMode.value=data}
                override suspend fun save(account:String,data:RemoteHistoryData,expectedEpoch:Long){require(account=="7"&&historyMode.value.epoch==expectedEpoch);historyMode.value=data}
            }
            val sync=RemoteHistorySync(object:RemoteHistoryPort {
                override suspend fun paused()=false
                override suspend fun page(cursor:HistoryCursor?)=RemoteHistoryPage(listOf(RemoteHistoryItem(1,"BV1xx411c7mD",142,42,"B站同步 · 示例歌曲",positionMs=95123,viewedAt=1000)),null)
                override suspend fun report(item:RemoteHistoryItem,account:String):RemoteHistoryItem{historyWrites++;return item}
                override suspend fun delete(key:String?,account:String){historyWrites++}
            },cloud,deps.accounts,deps.clock)
            vm=MainViewModel(if(historyFixture)deps.copy(historySync=sync,history=SyncedHistoryRepository(deps.history,cloud))else deps);owner.put("fixture",vm)
        }
    }
    private fun start():MainActivity {
        assertFalse(app.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked)
        return i.startActivitySync(Intent(app,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
    }
    private fun show(a:MainActivity,theme:Theme,font:Float,library:SourceRepository?=null,seeded:List<CollectionSnapshot> = emptyList(),homeSamples:List<Recommendation> = emptyList(),defaultConfigured:Boolean=true,historyFixture:Boolean=false):Fixture {
        lateinit var fixture:Fixture
        i.runOnMainSync {
            val base=app.dependencies();base.playback.close();fixture=Fixture(base,theme,library,seeded,homeSamples,defaultConfigured,historyFixture)
            a.setContent {
                val density=LocalDensity.current
                val config=Configuration(LocalConfiguration.current).apply{if(font>1f)screenWidthDp=320}
                CompositionLocalProvider(LocalDensity provides Density(density.density,font),LocalConfiguration provides config) {
                    Column((if(font>1f)Modifier.width(320.dp) else Modifier.fillMaxWidth()).fillMaxHeight().safeDrawingPadding()) {
                        Text("界面预览 · 示例数据",Modifier.padding(horizontal=20.dp,vertical=6.dp),fontSize=11.sp)
                        Box(Modifier.weight(1f)){key(fixture.vm){ListenScreen(fixture.vm)}}
                    }
                }
            }
        }
        waitFor(if(defaultConfigured)"收藏" else "新建并设为默认");return fixture
    }
    @Test fun fullUpFlowSavesReopensAndPlaysAll1887ThroughFakePortThenReturnsFromCreator() {
        val a=start();val f=show(a,Theme.LIGHT,1f)
        try {
            click("收藏");revealUpTab();waitFor("搜索 / 添加 UP 主");shot("up-favorites-empty-light")
            click("搜索 / 添加 UP 主");input("音乐 UP");click("提交 UP 主搜索")
            waitFor("查看 ${f.profile.name} 的投稿");shot("up-search-light");click("查看 ${f.profile.name} 的投稿")
            waitFor("UP 主投稿");waitFor("1887 个投稿");shot("up-uploads-light")
            click("加入 UP 收藏");waitFor("已加入 UP 收藏 · 点击移除")
            click("返回");click("返回");waitFor("搜索 / 添加 UP 主");waitFor("查看 ${f.profile.name} 的投稿")
            shot("up-favorites-saved-light");click("查看 ${f.profile.name} 的投稿");waitFor("已加入 UP 收藏 · 点击移除")
            click("随机播放");waitFor("查看 UP 主投稿")
            assertEquals(1887,f.vm.state.value.queue.size);assertEquals(PlayMode.SHUFFLE,f.vm.state.value.mode)
            assertEquals(0,f.audioCalls);assertEquals(0,f.remoteWrites)
            click("查看 UP 主投稿");waitFor("UP 主投稿");click("返回");waitFor("查看 UP 主投稿")
            click("返回");waitFor("UP 主投稿");click("返回");waitFor("搜索 / 添加 UP 主")
            assertNull(find("搜索或添加 UP 主"));click("管理");waitFor("收藏管理");click("返回收藏");waitFor("搜索 / 添加 UP 主")
        }finally{i.runOnMainSync{f.owner.clear();a.finish()}}
    }
    @Test fun darkAndLargeFontKeepThirdTabSearchAndUploadActionsReadable() {
        val a=start()
        try {
            for((theme,font,name) in listOf(Triple(Theme.DARK,1f,"dark"),Triple(Theme.LIGHT,1.6f,"large"))) {
                val f=show(a,theme,font)
                try {
                    click("收藏");revealUpTab();click("搜索 / 添加 UP 主");input("音乐 UP");click("提交 UP 主搜索")
                    waitFor("查看 ${f.profile.name} 的投稿");click("查看 ${f.profile.name} 的投稿")
                    waitFor("加入 UP 收藏");waitFor("播放全部");waitFor("随机播放");shot("up-uploads-$name")
                    assertEquals(0,f.audioCalls);assertEquals(0,f.remoteWrites)
                }finally{i.runOnMainSync{f.owner.clear()}}
            }
        }finally{i.runOnMainSync{a.finish()}}
    }
    private class MixedLibrary:SourceRepository {
        val folder=ContentSource(SourceRef(SourceKind.PUBLIC_FAVORITES,42,8),"示例收藏夹","示例作者",200)
        val season=ContentSource(SourceRef(SourceKind.UP_COLLECTION,42,8,CollectionKind.SEASON),"示例合集","示例作者",40)
        val reads=mutableListOf<SourceRef>()
        override suspend fun publicFolders(owner:Long,page:Int)=error("unused")
        var followedStatus=false;var followWrites=0
        override suspend fun resolve(link:SourceLink,account:Long?):SourceContentPage {
            require(link.id==season.ref.id&&link.owner==season.ref.owner&&link.collectionKind==CollectionKind.SEASON)
            return content(season.ref,account,1)
        }
        override suspend fun isFollowed(account:Long,source:SourceRef)=followedStatus
        override suspend fun follow(account:Long,source:SourceRef,follow:Boolean):MutationOutcome {require(source==season.ref);followWrites++;followedStatus=follow;return MutationOutcome.CONFIRMED}
        override suspend fun followed(account:Long,page:Int)=SourceListPage(if(page==1)listOf(folder) else listOf(folder,season),page,page==1)
        override suspend fun content(source:SourceRef,account:Long?,page:Int):SourceContentPage {
            reads+=source;val info=if(source==folder.ref)folder else season.also{require(source==it.ref)}
            val rows=List(info.count){FavoriteItem("BV"+(it+1).toString().padStart(10,'0'),"示例歌曲 ${it+1}")}
            return SourceContentPage(info,rows.drop((page-1)*20).take(20),page,page*20<rows.size)
        }
    }
    @Test fun mergedFollowedFoldersAndSeasonsOpenCorrectSourceKeepOldShortcutAndPlayAll() {
        val a=start();val repo=MixedLibrary()
        val old=repo.folder.copy(title="旧收藏夹名称")
        val local=repo.folder.copy(ref=repo.folder.ref.copy(id=99),title="本机旧入口")
        fun saved(c:ContentSource)=CollectionSnapshot("7",c.ref,emptyList(),false,0,0,c.title,c.ownerName,c.count,true,c.cover)
        val f=show(a,Theme.LIGHT,1f,repo,listOf(saved(old),saved(local)))
        try {
            click("收藏");waitFor("追更合集");waitFor("UP收藏");assertNull(find("他人收藏"))
            click("追更合集");waitFor("追更的别人收藏夹");waitFor("收藏夹");waitFor("本机旧入口")
            assertNull(find("旧收藏夹名称"));click("加载更多追更");waitFor("已追更的合集");waitFor("合集")
            shot("merged-followed-light")
            click("示例合集");waitFor("播放全部");assertEquals(repo.season.ref,f.vm.state.value.sourceContent!!.source.ref)
            click("返回");waitFor("追更的别人收藏夹");click("示例收藏夹");waitFor("播放全部")
            assertEquals(repo.folder.ref,f.vm.state.value.sourceContent!!.source.ref)
            click("播放全部");waitFor("查看 UP 主投稿");assertEquals(200,f.vm.state.value.queue.size)
            assertTrue(f.vm.state.value.queue.all{it.source==repo.folder.ref});assertEquals(0,f.audioCalls);assertEquals(0,f.remoteWrites)
            click("返回");waitFor("播放全部");click("返回");waitFor("追更的别人收藏夹")
            click("UP收藏");waitFor("搜索 / 添加 UP 主")
            File(app.filesDir,"up-library-evidence/merged-ui.json").writeText("""{"removedOtherTab":true,"mixedPages":true,"typesAndSourceRoutes":true,"legacyBookmarkKept":true,"remoteLocalDedup":true,"folderQueueEntries":200,"fixtureAudioRequests":0,"realPlayerTouched":false}""")
        }finally{i.runOnMainSync{f.owner.clear();a.finish()}}
    }
    @Test fun mergedFollowedTypesRemainReadableInDarkAndLargeFont() {
        val a=start()
        try {
            for((theme,font,name) in listOf(Triple(Theme.DARK,1f,"dark"),Triple(Theme.LIGHT,1.6f,"large"))) {
                val repo=MixedLibrary();val f=show(a,theme,font,repo)
                try {
                    click("收藏");click("追更合集");waitFor("追更的别人收藏夹");assertNull(find("他人收藏"))
                    click("加载更多追更");waitFor("已追更的合集");waitFor("收藏夹");waitFor("合集")
                    shot("merged-followed-$name");assertEquals(0,f.audioCalls);assertEquals(0,f.remoteWrites)
                }finally{i.runOnMainSync{f.owner.clear()}}
            }
        }finally{i.runOnMainSync{a.finish()}}
    }
    private fun dragSource(from:String,to:String,cancel:Boolean=false) {
        val start=android.graphics.Rect();val end=android.graphics.Rect()
        find("拖动来源：$from")!!.getBoundsInScreen(start);find("拖动来源：$to")!!.getBoundsInScreen(end)
        val x=start.centerX().toFloat();val y=start.centerY().toFloat();val finish=end.centerY().toFloat()
        val time=android.os.SystemClock.uptimeMillis()
        fun send(action:Int,value:Float){val event=android.view.MotionEvent.obtain(time,android.os.SystemClock.uptimeMillis(),action,x,value,0);event.source=android.view.InputDevice.SOURCE_TOUCHSCREEN;assertTrue(i.uiAutomation.injectInputEvent(event,true));event.recycle()}
        send(android.view.MotionEvent.ACTION_DOWN,y)
        for(step in 1..24){send(android.view.MotionEvent.ACTION_MOVE,y+(finish-y)*step/24);Thread.sleep(16)}
        send(if(cancel)android.view.MotionEvent.ACTION_CANCEL else android.view.MotionEvent.ACTION_UP,finish);Thread.sleep(350)
    }
    @Test fun localManagementHidesRestoresDragsAndKeepsCategoriesAndPlaybackIndependent() {
        val a=start();val repo=MixedLibrary();val f=show(a,Theme.LIGHT,1f,repo)
        try {
            click("收藏");waitFor("音乐收藏夹");waitFor("美食收藏夹");assertNull(find("整理"));assertNull(find("打开收藏或合集来源"))
            click("管理");waitFor("收藏管理");waitFor("隐藏来源：美食收藏夹");click("隐藏来源：美食收藏夹");waitFor("恢复显示：美食收藏夹")
            dragSource("生活收藏夹","音乐收藏夹",true)
            assertTrue(f.vm.state.value.library.layout(LibrarySection.MINE)!!.order.isEmpty())
            dragSource("生活收藏夹","音乐收藏夹")
            assertEquals(3L,f.vm.state.value.library.layout(LibrarySection.MINE)!!.order.first().id)
            shot("management-light");click("返回收藏");waitFor("生活收藏夹");assertNull(find("美食收藏夹"))
            assertEquals(listOf(3L,1L),f.vm.state.value.library.apply(LibrarySection.MINE,f.vm.state.value.folders){SourceRef(SourceKind.OWN_FAVORITES,it.id,7)}.map{it.id})
            click("管理");click("恢复显示：美食收藏夹");click("返回收藏");waitFor("美食收藏夹")
            click("追更合集");waitFor("示例收藏夹");click("管理");waitFor("隐藏来源：示例合集")
            assertEquals(2,f.vm.state.value.managementSources.size);dragSource("示例合集","示例收藏夹")
            click("隐藏来源：示例收藏夹");click("返回收藏");waitFor("示例合集");assertNull(find("示例收藏夹"))
            assertFalse(f.vm.state.value.sources!!.hasMore)
            click("管理");click("全部显示");click("默认顺序");click("返回收藏");waitFor("示例收藏夹")
            click("UP收藏");click("搜索 / 添加 UP 主");input("音乐 UP");click("提交 UP 主搜索");waitFor("查看 ${f.profile.name} 的投稿");click("查看 ${f.profile.name} 的投稿");click("加入 UP 收藏");click("返回");click("返回")
            waitFor("查看 ${f.profile.name} 的投稿");click("管理");waitFor("隐藏来源：${f.profile.name}的投稿");click("隐藏来源：${f.profile.name}的投稿");click("返回收藏");waitFor("UP 收藏已隐藏")
            click("管理");click("全部显示");click("返回收藏");waitFor("查看 ${f.profile.name} 的投稿")
            assertEquals(0,f.audioCalls);assertEquals(0,f.remoteWrites);assertTrue(f.vm.state.value.queue.isEmpty())
            File(app.filesDir,"up-library-evidence/management-ui.json").writeText("""{"localHiddenRestore":true,"threeCategories":true,"actualTouchReorder":true,"touchCancelKeptOrder":true,"secondPageMovedSourceVisible":true,"removedHeaderPlus":true,"remoteWrites":0,"fixtureAudioRequests":0,"realPlayerTouched":false}""")
        }finally{i.runOnMainSync{f.owner.clear();a.finish()}}
    }
    @Test fun managementDarkAndLargeFontKeepVisibilityAndDragControlsReadable() {
        val a=start()
        try {
            for((theme,font,name) in listOf(Triple(Theme.DARK,1f,"dark"),Triple(Theme.LIGHT,1.6f,"large"))) {
                val f=show(a,theme,font,MixedLibrary())
                try {
                    click("收藏");click("管理");waitFor("隐藏来源：音乐收藏夹");waitFor("隐藏来源：美食收藏夹");waitFor("拖动来源：生活收藏夹")
                    click("隐藏来源：美食收藏夹");waitFor("恢复显示：美食收藏夹");shot("management-$name")
                    assertEquals(0,f.audioCalls);assertEquals(0,f.remoteWrites)
                }finally{i.runOnMainSync{f.owner.clear()}}
            }
        }finally{i.runOnMainSync{a.finish()}}
    }

    @Test fun scopedPlusCreatesSyncedFolderAndReadsThenConfirmsCollectionThroughFakePorts() {
        val a=start();val repo=MixedLibrary();val f=show(a,Theme.LIGHT,1f,repo)
        try {
            click("收藏");waitFor("我的收藏夹");click("新建收藏夹");waitFor("收藏夹名称")
            assertEquals(0,f.folderCreates);shot("create-folder-light")
            input("  夜晚听歌  ");click("私密收藏夹开关");waitFor("仅自己可见")
            click("创建并同步到 B站");waitFor("已回读确认创建成功");click("知道了")
            waitFor("夜晚听歌");assertEquals(1,f.folderCreates);assertTrue(f.createdPrivate)
            assertEquals("夜晚听歌",f.vm.state.value.folders.last().title)
            click("追更合集");waitFor("新增合集");assertNull(find("打开来源链接"))
            click("新增合集");waitFor("B站合集链接");input("https://www.bilibili.com/list/ml42");click("读取合集")
            waitFor("请输入 B 站合集的完整分享链接");assertEquals(0,repo.followWrites)
            input("https://space.bilibili.com/8/lists/42?type=season");shot("add-collection-light");click("读取合集")
            waitFor("追更");assertEquals(repo.season.ref,f.vm.state.value.sourceContent!!.source.ref);assertEquals(0,repo.followWrites)
            click("追更");waitFor("追更此合集？");assertEquals(0,repo.followWrites);click("确认");waitFor("已追更")
            assertEquals(1,repo.followWrites);assertTrue(f.vm.state.value.queue.isEmpty());assertEquals(0,f.audioCalls)
            File(app.filesDir,"up-library-evidence/source-additions-ui.json").writeText("""{"scopedPlusButtons":true,"trimmedPrivateFolderCreate":true,"createdFolderRefreshed":true,"invalidLinkRetainedForm":true,"verifiedSeasonBeforeConfirm":true,"followRequiresConfirmation":true,"fakeFolderCreates":1,"fakeFollowWrites":1,"realRemoteWrites":0,"realPlayerTouched":false}""")
        }finally{i.runOnMainSync{f.owner.clear();a.finish()}}
    }

    private fun revealHome(label:String) {
        repeat(10){
            if(find(label)!=null)return
            fun scroll(n:AccessibilityNodeInfo?):Boolean {
                if(n==null)return false
                if(n.isScrollable && n.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))return true
                return (0 until n.childCount).any{scroll(n.getChild(it))}
            }
            scroll(i.uiAutomation.rootInActiveWindow);Thread.sleep(250)
        };waitFor(label)
    }
    private fun revealPlayerControl(label:String) {
        repeat(10) {
            if(find(label)!=null)return
            fun vertical(n:AccessibilityNodeInfo?):AccessibilityNodeInfo? {
                if(n==null||!n.refresh()||!n.isVisibleToUser)return null
                for(k in 0 until n.childCount)vertical(n.getChild(k))?.let{return it}
                return n.takeIf{it.isScrollable&&it.actionList.any{action->action.id==AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id}}
            }
            val scroll=vertical(i.uiAutomation.rootInActiveWindow) ?: error("Missing vertical player scroller")
            assertTrue(scroll.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD));i.waitForIdleSync();Thread.sleep(250)
        }
        shot("favorite-player-control-missing");waitFor(label)
    }
    private fun homeToTop() {
        repeat(10) {
            fun vertical(n:AccessibilityNodeInfo?):AccessibilityNodeInfo? {
                if(n==null||!n.refresh()||!n.isVisibleToUser)return null
                for(k in 0 until n.childCount)vertical(n.getChild(k))?.let{return it}
                return n.takeIf{it.isScrollable&&it.actionList.any{action->action.id==AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id}}
            }
            val scroll=vertical(i.uiAutomation.rootInActiveWindow) ?: return
            if(!scroll.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD))return
            i.waitForIdleSync();Thread.sleep(150)
        }
    }
    /** README captures use local, explicitly seeded artwork and isolated account/playback ports. */
    @Test fun readmeShowcaseFromLocalFixtures()=runBlocking {
        val args=InstrumentationRegistry.getArguments()
        org.junit.Assume.assumeTrue(args.getString("readmeShowcase")=="1")
        val covers=args.getString("showcaseCovers").orEmpty().split(',').filter{it.isNotBlank()}
        require(covers.size==3 && covers.all{CoverStore.safeUrl(it)!=null})
        val samples=covers.mapIndexed{index,cover->Recommendation("BV"+(300+index).toString().padStart(10,'0'),"示例内容",null,cover,"示例创作者",241,8,cover)}
        val localSources=object:SourceRepository {
            override suspend fun publicFolders(owner:Long,page:Int)=SourceListPage(emptyList(),page,false)
            override suspend fun followed(account:Long,page:Int)=SourceListPage(emptyList(),page,false)
            override suspend fun resolve(link:SourceLink,account:Long?)=error("unused")
            override suspend fun content(source:SourceRef,account:Long?,page:Int)=error("unused")
        }
        for(cover in covers)assertNotNull("Seed artwork before running this capture",app.covers.load(cover))
        val a=start()
        try {
            for((theme,id)in listOf(Theme.LIGHT to "light",Theme.DARK to "dark")) {
                val f=show(a,theme,1f,library=localSources,homeSamples=samples)
                try {
                    i.runOnMainSync{f.vm.loadRecommendations()}
                    waitFor("收听推荐：把生活里的小美好，听进耳朵 · 示例 1");Thread.sleep(600);shot("readme-home-$id")
                    revealHome("查看推荐 UP：${f.profile.name}");Thread.sleep(400);shot("readme-recommendations-$id")
                    click("收藏");waitFor("我的收藏夹");shot("readme-favorites-$id")
                    click("我的");waitFor("我的收听");shot("readme-mine-$id")
                    click("设置");waitFor("音乐推荐");shot("readme-settings-$id")
                    assertEquals(0,f.audioCalls);assertEquals(0,f.remoteWrites)
                }finally{i.runOnMainSync{f.owner.clear()}}
            }
        }finally{i.runOnMainSync{a.finish()}}
    }
    @Test fun refinedHomeShowsCreatorsAndOpensUploadsAcrossThemesAndLargeFont()=runBlocking {
        val a=start()
        val samples=withContext(Dispatchers.IO){app.recommendations.candidates(HomeCategory.MUSIC)}
        try {
            for((theme,font,id) in listOf(Triple(Theme.LIGHT,1f,"light"),Triple(Theme.DARK,1f,"dark"),Triple(Theme.LIGHT,1.6f,"large"))) {
                val f=show(a,theme,font,homeSamples=samples)
                try {
                    i.runOnMainSync{f.vm.loadRecommendations()}
                    waitFor("首页搜索栏");waitFor("收听推荐：把生活里的小美好，听进耳朵 · 示例 1");waitFor("推荐")
                    val bar=Rect();find("首页搜索栏")!!.getBoundsInScreen(bar)
                    assertTrue("Compact 48dp search bar",bar.height()<=49*app.resources.displayMetrics.density)
                    assertTrue("Narrow 8dp page edge",kotlin.math.abs(bar.left/app.resources.displayMetrics.density-8f)<1f)
                    assertNull(find("哔哩听视频"));shot("home-refined-$id")
                    val beforeReads=f.categoryReads.toList();click("推荐分类：音乐")
                    assertEquals(HomeCategory.MUSIC,f.vm.state.value.recommendationCategory);assertFalse(f.vm.state.value.recommendationBusy)
                    assertEquals(beforeReads,f.categoryReads);click("推荐分类：推荐");assertEquals(HomeCategory.ALL,f.vm.state.value.recommendationCategory)
                    assertEquals(10,f.vm.state.value.homeUps.size);revealHome("查看推荐 UP：${f.profile.name}");shot("home-creators-$id")
                    click("查看推荐 UP：${f.profile.name}");waitFor("UP 主投稿");waitFor("1887 个投稿")
                    click("返回");waitFor("首页");revealHome("最近");revealHome("听到 0:45 · P1");shot("home-recent-$id")
                    revealHome("最近收听更多：一段好声音，陪你慢慢听 · 示例 1")
                    click("最近收听更多：一段好声音，陪你慢慢听 · 示例 1");waitFor("继续收听");waitFor("去 B 站看");waitFor("删除这条历史")
                    i.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);Thread.sleep(250);waitFor("首页")
                    click("收藏");waitFor("我的收藏夹");shot("compact-favorites-$id")
                    click("我的");waitFor("我的收听");shot("compact-mine-$id")
                    click("设置");waitFor("外观");shot("compact-settings-$id")
                    assertEquals(0,f.audioCalls);assertEquals(0,f.remoteWrites);assertTrue(f.vm.state.value.queue.isEmpty())
                }finally{i.runOnMainSync{f.owner.clear()}}
            }
            File(app.filesDir,"up-library-evidence/home-refined-ui.json").writeText("""{"lightDarkLargeFont":true,"search48dp":true,"pageSideInsetDp":8,"favoritesMineSettingsAcrossThemes":true,"recommendedUpOpensUploads":true,"recommendedUpCount":10,"categorySwitchUsesPreload":true,"recentReachable":true,"recentCardMoreActions":true,"fixtureAudioRequests":0,"realRemoteWrites":0,"realPlayerTouched":false}""")
        } finally {i.runOnMainSync{a.finish()}}
    }
    @Test fun popularMusicShowsDifferentRowLayoutAndExactTypesAcrossThemes()=runBlocking {
        val a=start()
        val samples=withContext(Dispatchers.IO){app.recommendations.candidates(HomeCategory.MUSIC)}
        val source=ContentSource(SourceRef(SourceKind.UP_COLLECTION,22,8,CollectionKind.SEASON),"音乐合集 · 示例","示例 UP",42)
        var sourceReads=0
        val sources=object:SourceRepository {
            override suspend fun publicFolders(owner:Long,page:Int)=error("unused")
            override suspend fun followed(account:Long,page:Int)=SourceListPage(emptyList(),page,false)
            override suspend fun resolve(link:SourceLink,account:Long?)=error("unused")
            override suspend fun content(ref:SourceRef,account:Long?,page:Int):SourceContentPage {
                assertEquals(source.ref,ref);sourceReads++
                return SourceContentPage(source,listOf(FavoriteItem("BV1xx411c7mD","示例歌曲")),page,false)
            }
        }
        try {
            for((theme,font,id) in listOf(Triple(Theme.LIGHT,1f,"light"),Triple(Theme.DARK,1f,"dark"),Triple(Theme.LIGHT,1.6f,"large"))) {
                val f=show(a,theme,font,library=sources,homeSamples=samples)
                try {
                    revealHome("推荐视频")
                    waitFor("200 万+ · 音乐")
                    val root=Rect();i.uiAutomation.rootInActiveWindow.getBoundsInScreen(root)
                    val title=Rect();find("推荐视频")!!.getBoundsInScreen(title)
                    val density=app.resources.displayMetrics.density
                    val distance=(title.top-root.top-72*density).coerceAtLeast(0f)
                    if(distance>16*density) {
                        val from=root.bottom-100*density;val to=(from-distance).coerceAtLeast(root.top+80*density)
                        val down=android.os.SystemClock.uptimeMillis();val x=root.left+root.width()*.4f
                        fun event(action:Int,y:Float) {
                            val e=android.view.MotionEvent.obtain(down,android.os.SystemClock.uptimeMillis(),action,x,y,0)
                            e.source=android.view.InputDevice.SOURCE_TOUCHSCREEN;assertTrue(i.uiAutomation.injectInputEvent(e,true));e.recycle()
                        }
                        event(android.view.MotionEvent.ACTION_DOWN,from)
                        for(k in 1..20){Thread.sleep(20);event(android.view.MotionEvent.ACTION_MOVE,from+(to-from)*k/20)}
                        Thread.sleep(200);event(android.view.MotionEvent.ACTION_MOVE,to)
                        event(android.view.MotionEvent.ACTION_UP,to);Thread.sleep(400)
                    }
                    shot("popular-music-$id")
                    waitFor("多分 P · 共 150 P");waitFor("合集 · 42 个视频")
                    waitFor("单视频");waitFor("521.0 万播放")
                    shot("popular-music-$id")
                    assertEquals(3,f.vm.state.value.popularMusic.size)
                    assertEquals(0,f.audioCalls);assertEquals(0,f.remoteWrites);assertTrue(f.vm.state.value.queue.isEmpty())
                    click("打开推荐视频：音乐合集 · 示例，合集 · 42 个视频，2500.0 万播放")
                    waitFor("示例 UP的合集");waitFor("示例歌曲");waitFor("播放全部");waitFor("随机播放");assertEquals(0,f.audioCalls)
                    click("返回")
                    revealHome("推荐 UP");shot("popular-music-before-creators-$id")
                }finally{i.runOnMainSync{f.owner.clear()}}
            }
            assertEquals(3,sourceReads)
        }finally{i.runOnMainSync{a.finish()}}
    }
    @Test fun onlinePopularMusicHasVerifiedCountsTypesAndCollectionRoutes()=runBlocking {
        val rows=withContext(Dispatchers.IO){app.recommendations.popularMusic()}
        assertTrue("Actual high-play music returned",rows.isNotEmpty())
        assertTrue(rows.all{it.plays>=PopularMusic.MIN_PLAYS});assertEquals(rows.size,rows.distinctBy{it.key}.size)
        for(row in rows.take(3)) {
            row.collection?.let { source->
                val page=withContext(Dispatchers.IO){app.sources.content(source.ref,app.accounts.session.value.account?.id,1)}
                assertEquals(source.ref,page.source.ref);assertTrue(page.items.isNotEmpty())
            } ?: run {
                val video=withContext(Dispatchers.IO){app.content.video(row.bvid)}
                assertEquals(row.parts,video.parts.size);assertTrue(video.views!!>=PopularMusic.MIN_PLAYS)
            }
            assertNotNull(withContext(Dispatchers.IO){app.covers.load(row.cover)})
        }
        File(app.filesDir,"up-library-evidence/popular-music-online.json").apply{parentFile!!.mkdirs()}.writeText(
            """{"count":${rows.size},"collections":${rows.count{it.collection!=null}},"multiParts":${rows.count{it.collection==null&&it.parts>1}},"minPlays":${rows.minOf{it.plays}},"realAudioRequests":0,"remoteWrites":0}""")
    }
    @Test fun musicRefreshKeepsRowAndNextSectionBoundsAcrossPartialBatches() {
        val a=start()
        val checks=mutableListOf<String>()
        try {
            for((theme,font,name) in listOf(Triple(Theme.LIGHT,1f,"light"),Triple(Theme.DARK,1f,"dark"),Triple(Theme.LIGHT,1.6f,"large"))) {
                fun rows(count:Int,long:Boolean)=List(count){index->
                    PopularMusic("BV"+(index+1).toString().padStart(10,'0'),if(long)"那些年一起听过的经典歌曲音乐现场与合集 · 示例 $index" else "歌曲 $index","","示例 UP $index",6000000,if(index==1)150 else 1)
                }
                val display=mutableStateOf(ScreenState(popularMusic=rows(12,false)))
                i.runOnMainSync {
                    a.setContent {
                        val density=LocalDensity.current
                        CompositionLocalProvider(LocalDensity provides Density(density.density,font)) {
                            ListenTheme(theme) {
                                Column((if(font>1f)Modifier.width(320.dp) else Modifier.fillMaxWidth()).fillMaxHeight().safeDrawingPadding().padding(horizontal=8.dp)) {
                                    HomePopularMusic(display.value){}
                                    Text("推荐 UP")
                                }
                            }
                        }
                    }
                }
                fun bounds(label:String):Rect {waitFor(label);i.waitForIdleSync();Thread.sleep(100);return Rect().also{find(label)!!.getBoundsInScreen(it)}}
                fun firstLabel()=display.value.popularMusic.first().let{"打开推荐视频：${it.title}，${it.typeLabel}，${it.playLabel}"}
                val up=bounds("推荐 UP");val heading=bounds("推荐视频");val row=bounds(firstLabel())
                for((count,long,busy) in listOf(Triple(1,true,true),Triple(2,false,true),Triple(3,true,true),Triple(12,true,false),Triple(12,false,false))) {
                    i.runOnMainSync{display.value=display.value.copy(popularMusic=rows(count,long),popularMusicBusy=busy)}
                    bounds(firstLabel())
                    assertEquals("Next section stays put: $name / $count",up,bounds("推荐 UP"))
                    assertEquals(heading,bounds("推荐视频"));assertEquals(row,bounds(firstLabel()))
                    assertTrue("Play count fits in its row",row.contains(bounds("600.0 万播放")))
                    assertTrue("Author fits in its row",row.contains(bounds("示例 UP 0")))
                    assertTrue("Author line is not cropped",bounds("示例 UP 0").height()>=10*font*app.resources.displayMetrics.density)
                }
                checks+=name
            }
            File(app.filesDir,"up-library-evidence/music-stable-ui.json").apply{parentFile!!.mkdirs()}.writeText(
                """{"themes":3,"batchSizes":[1,2,3,12],"longAndShortTitles":true,"stableRowBounds":true,"stableNextSectionBounds":true,"realAudioRequests":0,"remoteWrites":0}""")
            assertEquals(3,checks.size)
        }finally{i.runOnMainSync{a.finish()}}
    }
    @Test fun onlineMusicRefreshChangesQualifiedBatchAndPublishesEarly()=runBlocking {
        val repository=BiliRecommendationRepository(app.api,{RecentRecommendationWindow(app.clock.nowMs())}) { bytes->
            java.security.MessageDigest.getInstance("MD5").digest(bytes).joinToString(""){"%02x".format(it)}
        }
        val start=android.os.SystemClock.elapsedRealtime();var firstMs=-1L
        val first=withContext(Dispatchers.IO){repository.popularMusic(emptySet()){if(firstMs<0)firstMs=android.os.SystemClock.elapsedRealtime()-start}}
        val firstTotal=android.os.SystemClock.elapsedRealtime()-start
        val secondStart=android.os.SystemClock.elapsedRealtime();var secondReadyMs=-1L
        val second=withContext(Dispatchers.IO){repository.popularMusic(first.map{it.key}.toSet()){if(secondReadyMs<0)secondReadyMs=android.os.SystemClock.elapsedRealtime()-secondStart}}
        val secondTotal=android.os.SystemClock.elapsedRealtime()-secondStart
        val third=withContext(Dispatchers.IO){repository.popularMusic(second.map{it.key}.toSet()) {}}
        for(rows in listOf(first,second,third)) {
            assertTrue(rows.isNotEmpty());assertTrue(rows.all{it.plays>=PopularMusic.MIN_PLAYS})
            assertEquals(rows.size,rows.distinctBy{it.key}.size)
        }
        assertTrue(first.map{it.key}.toSet().intersect(second.map{it.key}.toSet()).isEmpty())
        assertTrue(second.map{it.key}.toSet().intersect(third.map{it.key}.toSet()).isEmpty())
        assertTrue("First rows precede the completed batch",firstMs in 0 until firstTotal)
        File(app.filesDir,"up-library-evidence/music-refresh-online.json").apply{parentFile!!.mkdirs()}.writeText(
            """{"firstCount":${first.size},"secondCount":${second.size},"thirdCount":${third.size},"firstReadyMs":$firstMs,"firstTotalMs":$firstTotal,"secondReadyMs":$secondReadyMs,"secondTotalMs":$secondTotal,"minPlays":${(first+second+third).minOf{it.plays}},"previousBatchOverlap":0,"realAudioRequests":0,"remoteWrites":0}""")
    }
    @Test fun onlineMusicCreatorsKeepExactIdentityAndAvatarLoads()=runBlocking {
        val creators=withContext(Dispatchers.IO){app.recommendations.creators()}
        assertEquals("Ten real recommended creators",10,creators.size);assertTrue("Real music owners returned",creators.isNotEmpty())
        val avatarLoads=withContext(Dispatchers.IO){creators.map{app.covers.load(it.avatar)!=null}}
        assertTrue("Real creator avatars load",avatarLoads.all{it})
        val profile=withContext(Dispatchers.IO){app.upLibrary.profile(creators.first().mid)}
        assertEquals(creators.first().mid,profile.mid);assertEquals(creators.first().name,profile.name)
        File(app.filesDir,"up-library-evidence/home-creators-online.json").writeText("""{"source":"music-ranking","uniqueCreators":${creators.size},"avatarsLoaded":${avatarLoads.count{it}},"firstProfileIdentityConfirmed":true,"audioRequests":0,"remoteWrites":0}""")
    }
    @Test fun onlineRandomCreatorsComeFromMusicRankingAndAvoidThePreviousBatch()=runBlocking {
        val window=RecentRecommendationWindow(app.clock.nowMs())
        val md5:(ByteArray)->String={java.security.MessageDigest.getInstance("MD5").digest(it).joinToString(""){byte->"%02x".format(byte)}}
        val pool=withContext(Dispatchers.IO){app.api.homeCreatorCandidates(window,md5)}
        val repository=BiliRecommendationRepository(app.api,{window},md5)
        val first=withContext(Dispatchers.IO){repository.creators()}
        val second=withContext(Dispatchers.IO){repository.creators(first.map{it.mid}.toSet())}
        val third=withContext(Dispatchers.IO){repository.creators(second.map{it.mid}.toSet())}
        assertTrue(pool.isNotEmpty())
        for(rows in listOf(first,second,third)) {
            assertEquals(minOf(10,pool.size),rows.size);assertEquals(rows.size,rows.distinctBy{it.mid}.size)
            assertTrue(rows.all{up->pool.any{it.mid==up.mid && it.name==up.name && it.avatar==up.avatar}})
        }
        val overlap=first.map{it.mid}.toSet().intersect(second.map{it.mid}.toSet()).size
        val thirdOverlap=second.map{it.mid}.toSet().intersect(third.map{it.mid}.toSet()).size
        assertEquals(maxOf(0,2*minOf(10,pool.size)-pool.size),overlap)
        assertEquals(overlap,thirdOverlap)
        File(app.filesDir,"up-library-evidence/random-creators-online.json").apply{parentFile!!.mkdirs()}.writeText(
            """{"candidateOwners":${pool.size},"batchSize":${first.size},"previousBatchOverlap":$overlap,"thirdPreviousBatchOverlap":$thirdOverlap,"allFromMusicRanking":true,"exactMidNameAvatar":true,"realAudioRequests":0,"remoteWrites":0}""")
    }
    private fun idleFavorite(f:Fixture) {
        val until=System.currentTimeMillis()+12000
        while(f.vm.state.value.busy && System.currentTimeMillis()<until)Thread.sleep(50)
        assertFalse(f.vm.state.value.busy);i.waitForIdleSync();Thread.sleep(250)
    }
    private fun touch(label:String,long:Boolean=false,comparePress:Boolean=false) {
        waitFor(label);i.waitForIdleSync();val rect=Rect();find(label)!!.getBoundsInScreen(rect);assertFalse(rect.isEmpty)
        val before=if(comparePress)i.uiAutomation.takeScreenshot() else null
        val down=android.os.SystemClock.uptimeMillis()
        fun event(action:Int){val e=android.view.MotionEvent.obtain(down,android.os.SystemClock.uptimeMillis(),action,rect.centerX().toFloat(),rect.centerY().toFloat(),0);e.source=android.view.InputDevice.SOURCE_TOUCHSCREEN;assertTrue(i.uiAutomation.injectInputEvent(e,true));e.recycle()}
        event(android.view.MotionEvent.ACTION_DOWN);Thread.sleep(if(long)800 else 180)
        if(comparePress) {
            val during=i.uiAutomation.takeScreenshot()!!;val a=IntArray(rect.width()*rect.height());val b=IntArray(a.size)
            before!!.getPixels(a,0,rect.width(),rect.left,rect.top,rect.width(),rect.height())
            during.getPixels(b,0,rect.width(),rect.left,rect.top,rect.width(),rect.height())
            assertArrayEquals("Category press adds no grey rectangle",a,b)
            shot("category-no-grey-pressed");before.recycle();during.recycle()
        }
        event(android.view.MotionEvent.ACTION_UP);i.waitForIdleSync();Thread.sleep(300)
    }
    @Test fun recentHistorySyncRequiresConfirmationAndCanReturnToLocalWithoutRealWrites()=runBlocking {
        val a=start()
        try {
            for((theme,font,name) in listOf(Triple(Theme.LIGHT,1f,"light"),Triple(Theme.DARK,1.4f,"dark-large"))) {
                val f=show(a,theme,font,historyFixture=true)
                try {
                    f.remembered=LocalHistoryEntry("7",VideoRef("BV1xx411c7mD",1,1),"本机示例",null,1000,1000)
                    click("我的");click("设置");waitFor("实时同步B站最近记录")
                    assertFalse(f.historyMode.value.enabled);assertEquals(0,f.historyClears)
                    toggleRecentSync();waitFor("实时同步B站最近记录会覆盖删除本机的全部最近浏览记录，是否继续")
                    click("开启同步");assertFalse(f.historyMode.value.enabled);assertEquals(0,f.historyClears)
                    input("否");click("开启同步");assertFalse(f.historyMode.value.enabled);assertEquals(0,f.historyClears)
                    shot("history-sync-warning-$name");click("取消");assertFalse(f.historyMode.value.enabled);assertEquals(0,f.historyClears);assertNotNull(f.remembered)
                    toggleRecentSync();input("是");shot("history-sync-typed-$name");click("开启同步");val end=System.currentTimeMillis()+12000
                    while(!f.vm.state.value.remoteHistory.enabled&&System.currentTimeMillis()<end)Thread.sleep(50)
                    assertTrue(f.historyMode.value.enabled);assertEquals(1,f.historyClears);assertNull(f.remembered)
                    assertEquals(42,f.vm.state.value.history.single().video.part);assertEquals(95123L,f.vm.state.value.history.single().positionMs)
                    waitFor("刷新同步记录");shot("history-sync-enabled-$name")
                    toggleRecentSync();val stop=System.currentTimeMillis()+12000
                    while(f.vm.state.value.remoteHistory.enabled&&System.currentTimeMillis()<stop)Thread.sleep(50)
                    assertFalse(f.historyMode.value.enabled);assertEquals(2,f.historyClears);assertTrue(f.vm.state.value.history.isEmpty());assertEquals(0,f.historyWrites);assertEquals(0,f.audioCalls)
                }finally{i.runOnMainSync{f.owner.clear()}}
            }
            File(app.filesDir,"up-library-evidence/history-sync-ui.json").writeText("""{"defaultOff":true,"cancelPreservesLocal":true,"typedYesRequired":true,"emptyAndOtherWordsRejected":true,"cloudPart":42,"cloudPositionMs":95123,"offReturnsToEmptyLocal":true,"lightAndDarkLargeFont":true,"realRemoteWrites":0,"realPlayerTouched":false}""")
        }finally{i.runOnMainSync{a.finish()}}
    }
    @Test fun videoTapResumesPart42AndContinuousDefaultIsVisibleWithoutRealPlayback()=runBlocking {
        val a=start();val f=show(a,Theme.LIGHT,1f)
        try {
            f.videoParts=List(150){VideoPart(it+101L,it+1,"第 ${it+1} 首 · 示例",200)}
            f.remembered=LocalHistoryEntry("7",VideoRef("BV1xx411c7mD",142,42),"第42首",null,95123,1000)
            assertTrue(f.vm.state.value.settings.continuousParts)
            i.runOnMainSync{f.vm.loadRecommendations()}
            click("收听推荐：把生活里的小美好，听进耳朵 · 示例 1");idleFavorite(f)
            assertEquals(150,f.playback.value.queue.size)
            assertEquals(42,f.playback.value.queue.single{it.id==f.playback.value.currentId}.part)
            assertEquals(95123L,f.playback.value.positionMs)
            click("展开播放页");waitFor("1:35");shot("video-continuation-player")
            click("返回");click("我的");click("设置");click("音质与播放")
            waitFor("连续播放分 P");shot("video-continuation-setting")
            assertTrue(f.vm.state.value.settings.continuousParts)
            assertEquals(0,f.audioCalls);assertEquals(0,f.remoteWrites)
            File(app.filesDir,"up-library-evidence/video-continuation-ui.json").writeText("""{"parts":150,"resumedPart":42,"positionMs":95123,"defaultContinuous":true,"fakePlayRequestOnly":true,"realAudioCalls":0,"realRemoteWrites":0,"realPlayerTouched":false}""")
        }finally{i.runOnMainSync{f.owner.clear();a.finish()}}
    }
    @Test fun defaultFavoriteOnboardingAndMiniPlayerTapLongPressAcrossThemesAndLargeFont()=runBlocking {
        val a=start()
        val samples=emptyList<Recommendation>()
        try {
            for((theme,font,id) in listOf(Triple(Theme.LIGHT,1f,"light"),Triple(Theme.DARK,1f,"dark"),Triple(Theme.LIGHT,1.6f,"large"))) {
                val f=show(a,theme,font,homeSamples=samples,defaultConfigured=false)
                try {
                    waitFor("新建并设为默认");shot("default-folder-onboarding-$id")
                    click("默认收藏夹候选：音乐收藏夹");idleFavorite(f)
                    assertEquals(1L,f.vm.state.value.settings.defaultFavoriteFolders["7"]!!.id)
                    assertFalse(f.vm.state.value.defaultFolderPrompt)
                    waitFor("推荐分类：音乐");touch("推荐分类：音乐",comparePress=true)
                    val entry=QueueEntry("favorite-fixture","BV1xx411c7mD",1,1,"听着觉得喜欢的歌 · 示例")
                    i.runOnMainSync{f.playback.value=PlaybackView(connected=true,title=entry.title,queue=listOf(entry),currentId=entry.id,positionMs=45000,durationMs=241000)}
                    waitFor("播放条收藏");Thread.sleep(600);shot("default-favorite-mini-$id")
                    touch("播放条收藏");idleFavorite(f);assertEquals(1,f.remoteWrites)
                    assertEquals(true,f.vm.state.value.currentFavoritePresent)
                    touch("播放条收藏");idleFavorite(f);assertEquals(2,f.remoteWrites)
                    assertEquals(false,f.vm.state.value.currentFavoritePresent)
                    touch("播放条收藏",long=true);waitFor("收藏到文件夹：美食收藏夹")
                    assertEquals(2,f.remoteWrites);shot("default-favorite-selector-$id")
                    click("收藏到文件夹：美食收藏夹");waitFor("收藏到「美食收藏夹」？");click("确认");idleFavorite(f)
                    assertEquals(3,f.remoteWrites);assertEquals(1L,f.vm.state.value.settings.defaultFavoriteFolders["7"]!!.id)
                    i.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);Thread.sleep(300)
                    click("展开播放页");revealPlayerControl("播放器收藏");assertNull(find("左滑查看字幕"));shot("default-favorite-player-$id")
                    touch("播放器收藏");idleFavorite(f);assertEquals(true,f.vm.state.value.currentFavoritePresent);assertEquals(4,f.remoteWrites)
                    touch("播放器收藏");idleFavorite(f);assertEquals(false,f.vm.state.value.currentFavoritePresent);assertEquals(5,f.remoteWrites)
                    assertTrue((2L to 1L) in f.favoriteMembership)
                    touch("播放器收藏",long=true);waitFor("收藏到文件夹：美食收藏夹");assertEquals(5,f.remoteWrites)
                    i.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);Thread.sleep(300);click("返回");waitFor("首页")
                    click("我的");click("设置");click("默认收藏夹");waitFor("新建并设为默认")
                    click("新建并设为默认");waitFor("新建默认收藏夹");input("随手收藏")
                    click("私密收藏夹开关");click("创建并设为默认");idleFavorite(f);waitFor("已创建并设为默认收藏夹")
                    click("知道了");assertEquals(DefaultFavoriteFolder(101,"随手收藏"),f.vm.state.value.settings.defaultFavoriteFolders["7"])
                    assertTrue(f.createdPrivate);assertEquals(1,f.folderCreates);shot("default-favorite-settings-$id")
                    assertEquals(45000L,f.playback.value.positionMs);assertEquals(entry,f.playback.value.queue.single());assertFalse(f.playback.value.requested)
                    assertEquals(0,f.audioCalls)
                }finally{i.runOnMainSync{f.owner.clear()}}
            }
            File(app.filesDir,"up-library-evidence/default-favorite-ui.json").writeText("""{"lightDarkLargeFont":true,"categoryPressedPixelsUnchanged":true,"hintRemoved":true,"loginDefaultChooser":true,"localDefaultSetting":true,"miniAndPlayerTapToggles":true,"miniAndPlayerPhysicalLongPress":true,"otherFolderDoesNotChangeDefault":true,"otherFolderMembershipPreserved":true,"createPrivateDefault":true,"fixtureAudioRequests":0,"realRemoteWrites":0,"realPlayerTouched":false}""")
        }finally{i.runOnMainSync{a.finish()}}
    }

    @Test fun musicRecommendationSettingSwitchesVideoAndUpAcrossThemesWithoutPlayback()=runBlocking {
        val a=start()
        try {
            for((theme,font,id) in listOf(Triple(Theme.LIGHT,1f,"light"),Triple(Theme.DARK,1f,"dark"),Triple(Theme.LIGHT,1.6f,"large"))) {
                val f=show(a,theme,font)
                try {
                    waitFor("首页");assertTrue(f.vm.state.value.settings.musicRecommendations)
                    click("我的");click("设置");waitFor("音乐推荐");shot("music-recommendation-setting-$id")
                    click("音乐推荐开关");Thread.sleep(400);assertFalse(f.vm.state.value.settings.musicRecommendations)
                    assertTrue(f.vm.state.value.popularMusic.all{it.plays==23L});assertEquals(99L,f.vm.state.value.homeUps.single().mid)
                    click("返回");click("首页");revealHome("B站首页推荐");shot("account-home-video-$id")
                    revealHome("B站首页推荐创作者");waitFor("旅行 UP · 示例");shot("account-home-up-$id")
                    assertNull(find("音乐热榜创作者"));assertNull(find("200 万+ · 音乐"))
                    click("我的");click("设置");click("音乐推荐开关");Thread.sleep(400);assertTrue(f.vm.state.value.settings.musicRecommendations)
                    click("返回");click("首页");homeToTop();revealHome("200 万+ · 音乐");revealHome("音乐热榜创作者")
                    assertEquals(0,f.audioCalls);assertEquals(0,f.remoteWrites);assertTrue(f.playback.value.queue.isEmpty())
                }finally{i.runOnMainSync{f.owner.clear()}}
            }
        }finally{i.runOnMainSync{a.finish()}}
    }

}
