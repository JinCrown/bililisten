package app.bililisten.platform

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Rect
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.*
import androidx.compose.ui.unit.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.bililisten.*
import app.bililisten.shared.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Native visual fixtures are labelled and separate from real account data. No remote writes. */
@androidx.media3.common.util.UnstableApi @RunWith(AndroidJUnit4::class)
class UiUpgradeTest {
    private val i=InstrumentationRegistry.getInstrumentation()
    private val app=i.targetContext.applicationContext as ListenApplication
    private fun find(text:String):AccessibilityNodeInfo? {
        fun visit(n:AccessibilityNodeInfo?):AccessibilityNodeInfo?{if(n==null||!n.refresh())return null;if(n.text?.toString()==text||n.contentDescription?.toString()==text)return n;for(k in 0 until n.childCount)visit(n.getChild(k))?.let{return it};return null}
        return visit(i.uiAutomation.rootInActiveWindow)
    }
    private fun waitFor(label:String){val end=System.currentTimeMillis()+15000;while(System.currentTimeMillis()<end){if(find(label)!=null)return;Thread.sleep(100)};error("Missing $label")}
    private fun scroll(forward:Boolean=true){
        val end=System.currentTimeMillis()+10000;var root=i.uiAutomation.rootInActiveWindow
        while(root==null&&System.currentTimeMillis()<end){Thread.sleep(50);root=i.uiAutomation.rootInActiveWindow}
        val r=Rect();assertNotNull("Active window ready for scroll",root);root!!.getBoundsInScreen(r)
        val down=android.os.SystemClock.uptimeMillis();val x=r.centerX().toFloat();val y=r.top+r.height()*(if(forward).70f else .32f);val to=r.top+r.height()*(if(forward).32f else .70f)
        fun event(action:Int,time:Long,y:Float){val e=android.view.MotionEvent.obtain(down,time,action,x,y,0);e.source=android.view.InputDevice.SOURCE_TOUCHSCREEN;assertTrue(i.uiAutomation.injectInputEvent(e,true));e.recycle()}
        event(0,down,y);for(k in 1..10){Thread.sleep(20);event(2,android.os.SystemClock.uptimeMillis(),y+(to-y)*k/10)};event(1,android.os.SystemClock.uptimeMillis(),to);Thread.sleep(250)
    }
    private fun reveal(label:String){repeat(7){if(find(label)!=null)return;scroll()};repeat(14){if(find(label)!=null)return;scroll(false)};waitFor(label)}
    private fun click(label:String){reveal(label);var n=find(label);while(n!=null&&!n.isClickable)n=n.parent;assertTrue("Clickable $label",n?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true);i.waitForIdleSync();Thread.sleep(250)}
    private fun back(){i.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);Thread.sleep(250)}
    private fun start():MainActivity {assertFalse(app.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked);val a=i.startActivitySync(Intent(app,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity;waitFor("首页");Thread.sleep(1500);return a}
    private fun vm(a:MainActivity)=MainActivity::class.java.getDeclaredField("playbackVm").apply{isAccessible=true}.get(a) as MainViewModel
    private fun shot(name:String){Thread.sleep(400);val out=File(app.filesDir,"ui-upgrade-evidence").apply{mkdirs()};i.uiAutomation.takeScreenshot()?.let{b->File(out,"$name.png").outputStream().use{b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};b.recycle()}}
    private fun fixture(a:MainActivity,id:String,theme:Theme=Theme.LIGHT,font:Float=1f,content:@Composable ()->Unit){
        i.runOnMainSync{a.setContent{key(id){ListenTheme(theme){val density=LocalDensity.current;val config=Configuration(LocalConfiguration.current).apply{if(font>1f)screenWidthDp=320};CompositionLocalProvider(LocalDensity provides Density(density.density,font),LocalConfiguration provides config){Surface(Modifier.fillMaxSize()){Column(Modifier.fillMaxSize().safeDrawingPadding()){Text("界面预览 · 示例数据",Modifier.padding(horizontal=20.dp,vertical=6.dp),fontSize=11.sp,color=muted());Box(Modifier.weight(1f).then(if(font>1f)Modifier.width(320.dp) else Modifier.fillMaxWidth())){content()}}}}}}}}
        i.waitForIdleSync();Thread.sleep(600)
    }
    @Test fun coverSourceDiagnostics()=runBlocking {
        val a=start()
        try{
            val account=app.accountKey.toLong();val rows=withContext(Dispatchers.IO){
                val topics=listOf(HomeCategory.EMOTION,HomeCategory.AUDIOBOOK).flatMap{c->app.recommendations.candidates(c).map{c.name to it.cover}}
                val sources=app.api.followedSources(account).sources.filter{it.ref.kind==SourceKind.UP_COLLECTION}.map{"COLLECTION" to it.cover}
                (topics+sources).mapIndexed{index,(group,url)->async{
                    val safe=CoverStore.safeUrl(url);val loaded=app.covers.load(url)!=null
                    """{"index":$index,"group":"$group","nonBlank":${url.isNotBlank()},"acceptedUrl":${safe!=null},"loaded":$loaded,"url":${kotlinx.serialization.json.JsonPrimitive(url)}}"""
                }}.awaitAll()
            }
            val dir=File(app.filesDir,"ui-upgrade-evidence").apply{mkdirs()};File(dir,"cover-source-diagnostics-private.json").writeText(rows.joinToString(",","[","]"))
        }finally{i.runOnMainSync{a.finish()}}
    }
    @Test fun homeCategoriesFetchIndependentRankingsAndRecentTagsWithoutAutoplay()=runBlocking {
        val vault=app.vault.read();val settings=app.settings.settings.first();val before=app.stores.load(app.accountKey)
        val a=start();val model=vm(a);val results=mutableListOf<String>()
        try{
            for(category in HomeCategory.entries){
                i.runOnMainSync{if(model.state.value.recommendationCategory==category)model.loadRecommendations() else model.selectRecommendationCategory(category)}
                val until=System.currentTimeMillis()+60000
                while(System.currentTimeMillis()<until&&model.state.value.recommendationBusy)Thread.sleep(100)
                val s=model.state.value;assertFalse("Category read completes",s.recommendationBusy);assertEquals(category,s.recommendationCategory)
                assertNull("Category read succeeds: ${category.name}",s.recommendationError)
                assertEquals("Category has five real results: ${category.name}",5,s.recommendations.size)
                val decoded=withContext(Dispatchers.IO){s.recommendations.map{async{app.covers.load(it.cover)!=null}}.awaitAll().count{it}}
                assertEquals("All category covers decode: ${category.name}",5,decoded)
                assertFalse(s.playRequested)
                results+="""{"category":"${category.name}","count":${s.recommendations.size},"decodedCovers":$decoded,"restricted":${s.recommendationError!=null}}"""
                if(category in listOf(HomeCategory.EMOTION,HomeCategory.AUDIOBOOK))shot("home-${category.name.lowercase()}-private")
            }
            i.runOnMainSync{model.selectRecommendationCategory(HomeCategory.ALL)}
            waitFor("全站排行榜 · 前 5");assertNull(find("为你推荐"))
            assertTrue("Session retained",vault==app.vault.read());assertEquals(settings,app.settings.settings.first())
            val dir=File(app.filesDir,"ui-upgrade-evidence").apply{mkdirs()};File(dir,"home-category-results.json").writeText(results.joinToString(",","[","]"));shot("home-rankings-private")
            val after=app.stores.load(app.accountKey)
            // A restored service may record a new pause reason/time; the saved queue must stay identical.
            assertTrue("Queue retained: entries=${before?.queue?.entries==after?.queue?.entries},order=${before?.queue?.order==after?.queue?.order},position=${before?.queue?.positionMs} -> ${after?.queue?.positionMs}",before?.queue==after?.queue)
        }finally{i.runOnMainSync{a.finish()}}
    }
    @Test fun followedCollectionsDisplayActualPlatformCoversAndKeepState()=runBlocking {
        val vault=app.vault.read();val settings=app.settings.settings.first();val before=app.stores.load(app.accountKey)
        val account=app.accountKey.toLongOrNull() ?: error("This read-only check needs the existing account")
        val page=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){app.api.followedSources(account)}
        val collections=page.sources.filter{it.ref.kind==SourceKind.UP_COLLECTION}
        assertTrue("Existing followed collections have platform cover URLs",collections.isNotEmpty()&&collections.all{it.cover.isNotBlank()})
        val decoded=withContext(Dispatchers.IO){collections.map{async{app.covers.load(it.cover)!=null}}.awaitAll().count{it}}
        assertEquals("All followed collection covers decode",collections.size,decoded)
        val a=start();val model=vm(a)
        try{
            val s=ScreenState(accountChecked=true,account=Account(7,"示例账户"),foldersLoaded=true,sources=page.copy(sources=collections))
            fixture(a,"real-collection-covers"){ListenContent(model,s)}
            click("收藏");click("追更合集");waitFor("合集封面");shot("followed-real-covers-private")
            assertTrue("Session retained",vault==app.vault.read());assertEquals(settings,app.settings.settings.first())
            assertTrue("Queue retained",before?.copy(savedAt=0)==app.stores.load(app.accountKey)?.copy(savedAt=0))
            File(app.filesDir,"ui-upgrade-evidence").mkdirs();File(app.filesDir,"ui-upgrade-evidence/preservation.json").writeText("""{"accountRetained":true,"settingsRetained":true,"savedQueueAndPositionRetained":true,"realPlatformCoverDisplayed":true,"remoteWrites":false}""")
        }finally{i.runOnMainSync{a.finish()}}
    }
    @Test fun readOnlyRoutesKeepAccountSettingsAndPausedQueue()=runBlocking {
        val settings=app.settings.settings.first();val vault=app.vault.read();val before=app.stores.load(app.accountKey)
        val a=start();val model=vm(a)
        try{
            assertFalse(model.state.value.playRequested)
            click("我的");click("下载管理");waitFor("只保存独立音频，下载完成后持续离线听");click("返回")
            click("缓存管理");waitFor("本机空间");reveal("清理普通缓存");click("返回")
            click("设置");click("外观");waitFor("跟随系统");waitFor("浅色");waitFor("深色");click("返回")
            click("隐私与本机历史");waitFor("你的收听记录");assertNull(find("推荐优先展示音乐"));click("返回")
            click("音质与播放");click("实际音质与输出");waitFor("音质与音频输出");back();click("倍速与定时停止");waitFor("长内容收听");back();click("返回")
            click("均衡器与音效");waitFor("音效总开关");back();click("关于哔哩听视频");waitFor("让好内容陪在耳边");click("返回");click("返回")
            click("首页");click("粘贴链接或 BV 号");waitFor("打开视频");click("返回");click("直播收听");waitFor("查看直播");click("返回")
            click("收藏");if(model.state.value.account!=null){waitFor("我的收藏");click("追更合集");click("UP收藏");click("我的收藏");click("整理");waitFor("让收藏更有条理");back()}
            click("我的");click("最近收听");waitFor("所有来源");click("返回")
            if(find("展开播放页")!=null){click("播放队列");waitFor("正在播放");waitFor("点 × 移出队列 · 按住 ≡ 拖动排序");back();click("展开播放页");reveal("查看字幕");click("查看字幕");waitFor("字幕");click("返回播放页");click("播放更多操作");click("歌词");waitFor("查询歌词候选");click("返回播放页");click("返回")}
            assertFalse(model.state.value.playRequested);assertTrue("Session retained",vault==app.vault.read());assertEquals(settings,app.settings.settings.first())
            assertTrue("Queue and saved position retained",before?.copy(savedAt=0)==app.stores.load(app.accountKey)?.copy(savedAt=0))
            File(app.filesDir,"ui-upgrade-evidence").mkdirs();File(app.filesDir,"ui-upgrade-evidence/preservation.json").writeText("""{"accountRetained":true,"settingsRetained":true,"savedQueueAndPositionRetained":true,"noAutoplay":true,"remoteWrites":false}""")
        }finally{i.runOnMainSync{a.finish()}}
    }
    @Test fun dataTransferEntryAndFilePickerCancellationKeepState()=runBlocking {
        val vault=app.vault.read();val settings=app.settings.settings.first();val before=app.stores.load(app.accountKey)
        val a=start()
        try{
            click("我的");assertNull(find("本机连续收听天数"));click("设置");click("数据转移")
            waitFor("Wi-Fi 迁移");waitFor("发送数据");waitFor("接收数据");waitFor("文件迁移（备用）");shot("transfer-page")
            for(label in listOf("导出文件","导入文件")){
                click(label);val until=System.currentTimeMillis()+10000
                while(System.currentTimeMillis()<until&&i.uiAutomation.rootInActiveWindow?.packageName?.toString()==app.packageName)Thread.sleep(100)
                assertTrue("System document picker opened",i.uiAutomation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui")==true);back();waitFor("Wi-Fi 迁移")
            }
            click("返回");waitFor("让收听更合心意")
            assertTrue("Session retained",vault==app.vault.read());assertEquals(settings,app.settings.settings.first());assertTrue("Queue retained",before?.copy(savedAt=0)==app.stores.load(app.accountKey)?.copy(savedAt=0))
        }finally{i.runOnMainSync{a.finish()}}
    }
    @Test fun transferPageAcrossThemesAndFontSizes(){
        val a=start();val transfer=androidx.lifecycle.ViewModelProvider(a)[TransferViewModel::class.java]
        try{
            for((theme,font,id) in listOf(Triple(Theme.LIGHT,1f,"light"),Triple(Theme.DARK,1f,"dark"),Triple(Theme.LIGHT,1.6f,"large"))){
                fixture(a,"transfer-$id",theme,font){Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)){TransferPage(transfer){}}}
                waitFor("Wi-Fi 迁移");waitFor("发送数据");waitFor("接收数据");shot("fixture-transfer-$id")
                reveal("导出文件");reveal("导入文件")
                for(label in listOf("导出文件","导入文件")){
                    var n=find(label);while(n!=null&&!n.isClickable)n=n.parent
                    val b=Rect();n!!.getBoundsInScreen(b);assertTrue("Visible file action at current font",b.width()>0&&b.height()>0)
                }
            }
        }finally{transfer.leave();i.runOnMainSync{a.finish()}}
    }
    @Test fun bottomTabsHaveNoPressOverlayAndKeepSelectionSemantics(){
        val a=start();val model=vm(a)
        try{
            fixture(a,"bottom-no-ripple"){ListenContent(model,ScreenState(accountChecked=true))}
            var node=find("收藏");while(node!=null&&!node.isClickable)node=node.parent
            assertNotNull(node);val bounds=Rect();node!!.getBoundsInScreen(bounds);assertFalse(bounds.isEmpty)
            val before=i.uiAutomation.takeScreenshot()!!;val at=android.os.SystemClock.uptimeMillis()
            fun event(action:Int){val e=android.view.MotionEvent.obtain(at,android.os.SystemClock.uptimeMillis(),action,bounds.centerX().toFloat(),bounds.centerY().toFloat(),0);e.source=android.view.InputDevice.SOURCE_TOUCHSCREEN;assertTrue(i.uiAutomation.injectInputEvent(e,true));e.recycle()}
            event(0);Thread.sleep(200);val pressed=i.uiAutomation.takeScreenshot()!!;event(1);i.waitForIdleSync()
            var differences=0
            for(y in bounds.top until bounds.bottom)for(x in bounds.left until bounds.right)if(before.getPixel(x,y)!=pressed.getPixel(x,y))differences++
            before.recycle();pressed.recycle();assertEquals("No gray rectangle or ripple while pressing",0,differences)
            waitFor("收藏页标题")
            fun selectedTab(n:AccessibilityNodeInfo?):Boolean {
                if(n==null)return false
                val r=Rect();n.getBoundsInScreen(r)
                if(n.isSelected&&r.top>=bounds.top&&r.bottom<=bounds.bottom)return true
                return (0 until n.childCount).any{selectedTab(n.getChild(it))}
            }
            assertTrue("Selected bottom tab is exposed to accessibility",selectedTab(i.uiAutomation.rootInActiveWindow))
        }finally{i.runOnMainSync{a.finish()}}
    }
    @Test fun rootScreensLightDarkAndLargeFontHaveThreeReachableTabs(){
        val a=start();val model=vm(a);val cover=model.state.value.recommendations.firstOrNull()?.cover ?: model.state.value.metadata.values.firstOrNull()?.cover.orEmpty()
        val state=ScreenState(accountChecked=true,recommendations=listOf(Recommendation("BV1Yt411u7UD","城市散步：把生活里的小美好听进耳朵 · 示例数据",null,cover,"示例作者",1800)),downloads=listOf(
            AudioDownload("fixture-complete","guest",VideoRef("BV1Yt411u7UD",1,1),"声音里的城市散步 · 示例数据",DownloadPhase.COMPLETE,8400000,8400000,digest="a".repeat(64)),
            AudioDownload("fixture-running","guest",VideoRef("BV1Yt411u7UD",2,2),"一段好声音，陪你慢慢听 · 示例数据",DownloadPhase.RUNNING,2400000,6000000),
            AudioDownload("fixture-paused","guest",VideoRef("BV1Yt411u7UD",3,3),"把生活里的小美好听进耳朵 · 示例数据",DownloadPhase.PAUSED,1200000,5200000)))
        try{for((theme,font,id) in listOf(Triple(Theme.LIGHT,1f,"light"),Triple(Theme.DARK,1f,"dark"),Triple(Theme.LIGHT,1.6f,"large"))){
            fixture(a,"root-$id",theme,font){ListenContent(model,state)};waitFor("首页");shot("fixture-home-$id")
            click("收藏");waitFor("请登录账户");shot("fixture-favorites-$id")
            click("我的");waitFor("我的收听");shot("fixture-mine-$id");reveal("下载管理");click("下载管理");waitFor("我的音频");shot("fixture-downloads-$id");click("返回");reveal("缓存管理");click("缓存管理");waitFor("本机空间");shot("fixture-storage-$id");reveal("清理普通缓存");click("返回");click("设置");waitFor("让收听更合心意");shot("fixture-settings-$id")
            for(label in listOf("返回")){val n=find(label)!!;var target=n;while(!target.isClickable&&target.parent!=null)target=target.parent;val b=Rect();target.getBoundsInScreen(b);assertTrue(b.width()>0&&b.height()>0)}
        }}finally{i.runOnMainSync{a.finish()}}
    }
    @Test fun homeSearchAtTopAndLinksWorkAcrossThemesAndFontSizes()=runBlocking {
        val vault=app.vault.read();val settings=app.settings.settings.first();val before=app.stores.load(app.accountKey)
        val a=start();val model=vm(a)
        val state=ScreenState(accountChecked=true,recommendations=listOf(Recommendation("BV1Yt411u7UD","一段好声音 · 示例数据",null,author="示例作者")))
        try {
            for((theme,font,id) in listOf(Triple(Theme.LIGHT,1f,"light"),Triple(Theme.DARK,1f,"dark"),Triple(Theme.LIGHT,1.6f,"large"))) {
                fixture(a,"home-searchbar-$id",theme,font){ListenContent(model,state)}
                waitFor("首页搜索栏");waitFor("搜索关键词");waitFor("搜索视频");waitFor("推荐")
                assertNull(find("哔哩听视频"));assertNull(find("把喜欢的视频，听起来"))
                val search=Rect();find("首页搜索栏")!!.getBoundsInScreen(search)
                val title=Rect();find("推荐")!!.getBoundsInScreen(title)
                assertTrue("Search comes above recommendations",search.bottom<=title.top)
                assertTrue("Search is inside viewport",search.left>=0&&search.right<=app.resources.displayMetrics.widthPixels)
                for(label in listOf("搜索关键词","搜索视频","首页搜索按钮","粘贴链接或 BV 号")) {
                    var node=find(label)!!;while(!node.isClickable&&node.parent!=null)node=node.parent
                    val bounds=Rect();node.getBoundsInScreen(bounds)
                    assertTrue("Reachable $label",node.isClickable&&bounds.width()>0)
                    assertTrue("Touch target 48dp $label",bounds.height()>=48*app.resources.displayMetrics.density-1)
                }
                shot("fixture-home-searchbar-$id")
                click("搜索关键词");waitFor("热搜推荐");waitFor("搜索 B 站视频 · 可输入标题或作者关键词")
                click("返回");waitFor("首页搜索栏")
                click("粘贴链接或 BV 号");waitFor("打开视频");click("返回");waitFor("首页搜索栏")
                click("搜索视频");waitFor("热搜推荐");click("返回");waitFor("首页搜索栏")
                click("首页搜索按钮");waitFor("热搜推荐");click("返回");waitFor("首页搜索栏")
            }
            assertTrue("Session retained",vault==app.vault.read());assertEquals(settings,app.settings.settings.first())
            val after=app.stores.load(app.accountKey)
            assertEquals(before?.queue,after?.queue)
            File(app.filesDir,"ui-upgrade-evidence/home-searchbar.json").writeText("""{"brandRemoved":true,"searchAboveRecommendations":true,"threeThemesAndFontCases":true,"touchTargets48dp":true,"searchAndLinkRoutes":true,"realPlayerTouched":false,"remoteWrites":false}""")
        } finally {i.runOnMainSync{a.finish()}}
    }
    @Test fun playerSubtitlesLyricsAndEffectsFixturesKeepControlsWorking(){
        val a=start();val cover=vm(a).state.value.recommendations.firstOrNull()?.cover.orEmpty()
        val video=Video("BV1Yt411u7UD",1,"声音里的城市散步 · 界面预览",listOf(VideoPart(1,1,"示例分 P")),cover=cover,author="示例作者",duration=1800)
        val entry=QueueEntry("fixture",video.bvid,1,1,video.title)
        var toggles=0;var sought=-1L
        val base=ScreenState(playingTitle=video.title,queue=listOf(entry),currentId=entry.id,positionMs=45000,durationMs=1800000,canSeek=true,canNext=true)
        try{
            fixture(a,"player"){PlayerPage(base,video,entry,base.positionMs,base.durationMs,Modifier,{}, {toggles++},{sought=it},{},{},{},{},{},{},{},{},{},{},{},{})};shot("fixture-player-light");click("播放");assertEquals(1,toggles);reveal("查看字幕")
            val zh=SubtitleOption("zh","zh-CN","中文",SubtitleKind.HUMAN)
            fixture(a,"subtitle"){SubtitlePage(base.copy(subtitles=SubtitleView(VideoRef(video.bvid,1,1),SubtitleStatus.READY,listOf(zh),zh,listOf(SubtitleCue(0,30000,"把城市的喧闹，留在身后"),SubtitleCue(30000,60000,"听见风，也听见生活里的小美好"),SubtitleCue(60000,90000,"慢慢走，慢慢听")))),video,Modifier,{}, {},{sought=it},{},{},{},{},{},{},{})};waitFor("当前字幕");shot("fixture-subtitles");click("慢慢走，慢慢听");assertEquals(60000,sought)
            val c=LyricsCandidate(1,"生活里的小美好","示例歌手","示例专辑",1800000)
            fixture(a,"lyrics"){LyricsPage(base.copy(lyrics=LyricsView(VideoRef(video.bvid,1,1),LyricsStatus.READY,document=LyricsDocument(c,"",listOf(SubtitleCue(0,30000,"把城市的喧闹，留在身后"),SubtitleCue(30000,60000,"听见风，也听见小美好"),SubtitleCue(60000,90000,"慢慢走，慢慢听"))),confirmed=true)),video,Modifier,{}, {},{sought=it},{},{},{},{_,_->},{},{},{})};click("收起搜索");shot("fixture-lyrics")
            val state=mutableStateOf(base.copy(connected=true,effects=EffectsView(equalizer=EffectDetail(EffectPhase.APPLIED,bands=listOf(EffectBand(60000,0),EffectBand(1000000,200)),minLevel=-1500,maxLevel=1500))))
            fixture(a,"effects"){Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp)){AudioEffectsPanel(state.value,{state.value=state.value.copy(settings=state.value.settings.copy(effects=it))},{})}};click("音效总开关");assertTrue(state.value.settings.effects.enabled);shot("fixture-effects")
        }finally{i.runOnMainSync{a.finish()}}
    }
    @Test fun narrowLargeFontPanelsKeepControlsReachable(){
        val a=start();val state=mutableStateOf(ScreenState(connected=true))
        try{
            val longVideo=Video("BV1Yt411u7UD",1,"很长的视频标题，验证大字体播放布局 · 示例数据",emptyList(),author="很长很长的视频作者名称，也要为分 P 标签保留位置",duration=1800)
            val entry=QueueEntry("fixture",longVideo.bvid,1,12,longVideo.title)
            var quickSought=0L
            fixture(a,"narrow-player",Theme.DARK,1.6f){PlayerPage(state.value.copy(queue=listOf(entry),currentId=entry.id,canSeek=true),longVideo,entry,45000,1800000,Modifier,back={},toggle={},seek={},previous={},next={},queue={},mode={},favorite={},subtitles={},official={},notice={},listening={},seekBy={quickSought=it},audio={})}
            waitFor("P12")
            for((label,value) in listOf("后退 15 秒" to -15000L,"前进 15 秒" to 15000L)){
                reveal(label);var n=find(label);while(n!=null&&!n.isClickable)n=n.parent
                assertNotNull("Quick seek button reachable",n);val b=Rect();n!!.getBoundsInScreen(b)
                assertTrue("Quick seek stays in 320dp viewport",b.width()>0&&b.left>=0&&b.right<=320*app.resources.displayMetrics.density)
                click(label);assertEquals(value,quickSought)
            }
            shot("fixture-player-narrow-large");reveal("查看字幕")
            fixture(a,"narrow-audio",Theme.DARK,1.6f){Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)){AudioExperiencePanel(state.value,{},{},{},{},{})}};waitFor("音质与音频输出");reveal("仅使用外部音频输出");shot("fixture-audio-narrow-large")
            fixture(a,"narrow-timer",Theme.LIGHT,1.6f){Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)){LongListeningPanel(state.value,{},{},{})}};reveal("自定义 1—180 分钟");shot("fixture-timer-narrow-large")
            fixture(a,"narrow-effects",Theme.DARK,1.6f){Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)){AudioEffectsPanel(state.value,{state.value=state.value.copy(settings=state.value.settings.copy(effects=it))},{})}};waitFor("音效总开关");click("音效总开关");assertTrue(state.value.settings.effects.enabled);reveal("恢复默认并关闭");click("恢复默认并关闭");assertFalse(state.value.settings.effects.enabled);shot("fixture-effects-narrow-large")
            val video=Video("BV1Yt411u7UD",1,"很长的视频标题：用大字体也能读清楚内容 · 示例数据",emptyList())
            fixture(a,"narrow-subtitle",Theme.LIGHT,1.6f){SubtitlePage(state.value.copy(subtitles=SubtitleView(status=SubtitleStatus.EMPTY,message="视频没有独立字幕；仍可返回播放页继续收听")),video,Modifier,{},{},{},{},{},{},{},{},{},{})};waitFor("暂无独立字幕");waitFor("播放");shot("fixture-subtitle-narrow-large")
            fixture(a,"narrow-lyrics",Theme.LIGHT,1.6f){LyricsPage(state.value,video,Modifier,{},{},{},{},{},{},{_,_->},{},{},{})};waitFor("查询歌词候选");waitFor("播放");shot("fixture-lyrics-narrow-large")
        }finally{i.runOnMainSync{a.finish()}}
    }
    @Test fun tabBackgroundReadsStayStillAndManualPullStillRefreshes() {
        val a=start();val model=vm(a);val state=mutableStateOf(ScreenState(accountChecked=true))
        fun hasProgress(n:AccessibilityNodeInfo?):Boolean {
            if(n==null)return false
            if(n.isVisibleToUser&&n.className?.toString()=="android.widget.ProgressBar")return true
            for(k in 0 until n.childCount)if(hasProgress(n.getChild(k)))return true
            return false
        }
        try {
            fixture(a,"silent-tabs"){ListenContent(model,state.value)}
            for((tab,label) in listOf("收藏" to "收藏页标题","我的" to "我的收听")) {
                i.runOnMainSync{state.value=state.value.copy(busy=false)};click(tab)
                val before=Rect();find(label)!!.getBoundsInScreen(before)
                i.runOnMainSync{state.value=state.value.copy(busy=true)};i.waitForIdleSync();Thread.sleep(300)
                val after=Rect();find(label)!!.getBoundsInScreen(after)
                assertEquals("Background refresh does not shift content",before,after)
                assertFalse("Automatic read shows no refresh/loading indicator",hasProgress(i.uiAutomation.rootInActiveWindow))
                shot(if(tab=="收藏")"fixture-favorites-silent-refresh" else "fixture-mine-silent-refresh")
            }
            var requests=0
            fixture(a,"manual-refresh"){UserRefreshBox(false,{requests++;kotlinx.coroutines.delay(1500)},Modifier.fillMaxSize()){
                androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize()){items(30){Text("下拉刷新测试 $it",Modifier.fillMaxWidth().padding(20.dp))}}
            }}
            scroll(false);val until=System.currentTimeMillis()+1500;while(requests==0&&System.currentTimeMillis()<until)Thread.sleep(50)
            assertEquals("Manual pull triggers refresh",1,requests);shot("fixture-manual-refresh")
            Thread.sleep(1700);assertFalse(hasProgress(i.uiAutomation.rootInActiveWindow))
        }finally{i.runOnMainSync{a.finish()}}
    }
    private fun dragHandle(label:String,dy:Float,hold:Long=0,cancel:Boolean=false) {
        val n=find(label) ?: error("Missing drag handle $label");val b=Rect();n.getBoundsInScreen(b);assertFalse(b.isEmpty)
        val down=android.os.SystemClock.uptimeMillis();val x=b.centerX().toFloat();val y=b.centerY().toFloat()
        fun event(action:Int,at:Float){val e=android.view.MotionEvent.obtain(down,android.os.SystemClock.uptimeMillis(),action,x,at,0);e.source=android.view.InputDevice.SOURCE_TOUCHSCREEN;assertTrue(i.uiAutomation.injectInputEvent(e,true));e.recycle()}
        event(0,y);Thread.sleep(80);for(k in 1..30){event(2,y+dy*k/30);Thread.sleep(20)};if(hold>0)Thread.sleep(hold);event(if(cancel)3 else 1,y+dy);i.waitForIdleSync();Thread.sleep(400)
    }
    @Test fun queueHandleDragsBothWaysCancelsAndScrollsAtEdge() {
        val a=start();val entries=(1..24).map{QueueEntry("queue-$it","BV1Yt411u7UD",it.toLong(),it,"声音里的城市散步 $it · 示例数据")}
        var snapshot=ResumeSnapshot(account="guest",entries=entries,order=entries.map{it.id},currentId=entries[1].id,positionMs=7131)
        val state=mutableStateOf(ScreenState(queue=entries,currentId=entries[1].id,positionMs=7131,canEditQueue=true));val edits=mutableListOf<QueueEdit>()
        try {
            fixture(a,"queue-drag"){QueuePanel(state.value,{}, {edit->edits+=edit;snapshot=QueueEditor.apply(snapshot,edit,snapshot.queueVersion)!!;state.value=state.value.copy(queue=snapshot.playbackEntries(),queueVersion=snapshot.queueVersion)}, {}, {})}
            val first="拖动排序：${entries[0].title}";val second="拖动排序：${entries[1].title}"
            val one=Rect();val two=Rect();find(first)!!.getBoundsInScreen(one);find(second)!!.getBoundsInScreen(two);val row=(two.centerY()-one.centerY()).toFloat()
            dragHandle(first,row*2.25f);assertEquals(2,snapshot.order.indexOf(entries[0].id));assertEquals(1,edits.size)
            dragHandle(first,-row*2.25f);assertEquals(0,snapshot.order.indexOf(entries[0].id));assertEquals(2,edits.size)
            dragHandle(first,row*1.5f,cancel=true);assertEquals(0,snapshot.order.indexOf(entries[0].id));assertEquals(2,edits.size)
            val bottom=Rect();find("点 × 移出队列 · 按住 ≡ 拖动排序")!!.getBoundsInScreen(bottom)
            find(first)!!.getBoundsInScreen(one);dragHandle(first,(bottom.top-one.centerY()-20).toFloat(),hold=1500)
            assertTrue("Edge auto-scroll reaches offscreen entries",snapshot.order.indexOf(entries[0].id)>7);assertEquals(3,edits.size)
            assertEquals(entries[1].id,snapshot.currentId);assertEquals(7131,snapshot.positionMs);assertFalse(state.value.playRequested)
            shot("fixture-queue-dragged")
        }finally{i.runOnMainSync{a.finish()}}
    }
    @Test fun queueLightDarkLargeRemoveAndAccessibleSortKeepStableIds() {
        val a=start();val entries=(1..9).map{QueueEntry("queue-$it","BV1Yt411u7UD",it.toLong(),it,"${listOf("秒针","一格格","笑纳（粤语版）","非你不爱","篇章","时光背面的我","孤独颂歌","白娱自乐","路过人间")[it-1]} · 示例数据")}
        try{for((theme,font,id) in listOf(Triple(Theme.LIGHT,1f,"light"),Triple(Theme.DARK,1f,"dark"),Triple(Theme.LIGHT,1.6f,"narrow-large"))) {
            var snapshot:ResumeSnapshot?=ResumeSnapshot(account="guest",entries=entries,order=entries.map{it.id},currentId=entries[4].id,positionMs=7131)
            val state=mutableStateOf(ScreenState(queue=entries,currentId=entries[4].id,positionMs=7131,canEditQueue=true,metadata=mapOf("BV1Yt411u7UD" to Video("BV1Yt411u7UD",1,"示例歌曲视频",emptyList(),author="示例作者"))))
            fixture(a,"queue-$id",theme,font){QueuePanel(state.value,{}, {edit->snapshot=snapshot?.let{QueueEditor.apply(it,edit,it.queueVersion)};state.value=state.value.copy(queue=snapshot?.playbackEntries().orEmpty(),currentId=snapshot?.currentId,positionMs=snapshot?.positionMs ?: 0)}, {}, {})}
            waitFor("正在播放");shot("fixture-queue-$id")
            val handle=find("拖动排序：${entries[1].title}")!!;val up=handle.actionList.firstOrNull{it.label?.toString()=="上移"};assertNotNull("Accessible reorder",up);assertTrue(handle.performAction(up!!.id));Thread.sleep(250);assertEquals(entries[1].id,snapshot!!.order.first())
            click("从队列移除：${entries[0].title}");assertFalse(snapshot!!.order.contains(entries[0].id));assertEquals(entries[4].id,snapshot!!.currentId);assertEquals(7131,snapshot!!.positionMs)
            reveal("从队列移除：${entries[4].title}");click("从队列移除：${entries[4].title}");assertFalse(snapshot!!.order.contains(entries[4].id));assertEquals(0,snapshot!!.positionMs);assertFalse(state.value.playRequested)
        }}finally{i.runOnMainSync{a.finish()}}
    }
    @Test fun shareAndLoginChromeRequireExplicitActionAndRetainSession()=runBlocking {
        val vault=app.vault.read();val settings=app.settings.settings.first();val before=app.stores.load(app.accountKey)
        val a=i.startActivitySync(Intent(app,MainActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,"https://www.bilibili.com/video/BV1Yt411u7UD").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        try{waitFor("分享转入");waitFor("请选择如何处理；收到分享不会自动播放或收藏。");shot("share-public-preview");click("取消");assertFalse(vm(a).state.value.playRequested)
            val login=i.startActivitySync(Intent(app,WebLoginActivity::class.java).putExtra("darkUi",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as WebLoginActivity
            try{waitFor("连接 B 站账户");waitFor("刷新登录网页");waitFor("未自动返回？重新检查");shot("login-chrome-dark");click("取消登录，返回应用")}finally{i.runOnMainSync{if(!login.isFinishing)login.finish()}}
            assertTrue("Login chrome preserves vault",vault==app.vault.read());assertEquals(settings,app.settings.settings.first());assertTrue(before?.copy(savedAt=0)==app.stores.load(app.accountKey)?.copy(savedAt=0));assertFalse(vm(a).state.value.playRequested)
        }finally{i.runOnMainSync{a.finish()}}
    }
}
