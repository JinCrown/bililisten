package app.bililisten.platform

import android.content.Intent
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.bililisten.*
import app.bililisten.shared.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@androidx.media3.common.util.UnstableApi
@RunWith(AndroidJUnit4::class)
class SearchAssistUiTest {
    private val i=InstrumentationRegistry.getInstrumentation()
    private val app=i.targetContext.applicationContext as ListenApplication
    private fun find(label:String):AccessibilityNodeInfo? {
        fun visit(n:AccessibilityNodeInfo?):AccessibilityNodeInfo? {
            if(n==null || !n.refresh() || !n.isVisibleToUser)return null
            if(n.text?.toString()==label || n.contentDescription?.toString()==label)return n
            for(k in 0 until n.childCount)visit(n.getChild(k))?.let{return it}
            return null
        }
        return visit(i.uiAutomation.rootInActiveWindow)
    }
    private fun waitFor(label:String) {
        val end=System.currentTimeMillis()+20000
        while(System.currentTimeMillis()<end){if(find(label)!=null)return;Thread.sleep(100)}
        error("Missing $label")
    }
    private fun click(label:String) {
        waitFor(label);var n=find(label)
        while(n!=null && !n.isClickable)n=n.parent
        assertTrue("Clickable $label",n?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true)
        Thread.sleep(250)
    }
    private fun input(value:String) {
        waitFor("搜索关键词")
        fun editor(n:AccessibilityNodeInfo?):AccessibilityNodeInfo? {
            if(n==null || !n.refresh() || !n.isVisibleToUser)return null
            if(n.packageName?.toString()==app.packageName && n.actionList.any{it.id==AccessibilityNodeInfo.ACTION_SET_TEXT})return n
            for(k in 0 until n.childCount)editor(n.getChild(k))?.let{return it}
            return null
        }
        val field=editor(i.uiAutomation.rootInActiveWindow) ?: error("No editable search field")
        assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply{putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value)}))
        Thread.sleep(100)
    }
    private fun start():MainActivity {
        assertFalse(app.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked)
        val a=i.startActivitySync(Intent(app,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        waitFor("首页");return a
    }
    private fun shot(name:String) {
        val folder=File(app.filesDir,"search-assist-evidence").apply{mkdirs()}
        i.uiAutomation.takeScreenshot()?.let{bmp->File(folder,"$name.png").outputStream().use{bmp.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bmp.recycle()}
    }
    @Test fun typingSelectingAndClearingWorkWithKeyboardAcrossThemesAndLargeFont() {
        val a=start()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        try {
            for((theme,font,name) in listOf(Triple(Theme.LIGHT,1f,"light"),Triple(Theme.DARK,1f,"dark"),Triple(Theme.LIGHT,1.6f,"large"))) {
                val query=mutableStateOf("");val editing=mutableStateOf(true);var chosen=""
                val controller=SearchAssistController(scope,object:SearchAssistRepository {
                    override suspend fun hot()=listOf(SearchWord("音乐现场 · 示例"),SearchWord("今天听什么 · 示例"))
                    override suspend fun suggest(query:String)=listOf(SearchWord("${query}现场 · 示例"),SearchWord("${query}演唱会 · 示例"))
                },object:Clock{override fun nowMs()=System.currentTimeMillis()})
                fun choose(value:String){chosen=value;query.value=value;editing.value=false;controller.hide()}
                i.runOnMainSync{a.setContent {ListenTheme(theme) {
                    val density=LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density,font)) {
                        val words by controller.state.collectAsState()
                        LaunchedEffect(controller,query.value,editing.value){if(editing.value)controller.show(query.value)}
                        Surface(Modifier.fillMaxSize()) {
                            Column(Modifier.fillMaxSize().safeDrawingPadding().then(if(font>1f)Modifier.width(320.dp) else Modifier.fillMaxWidth())) {
                                Text("界面预览 · 示例数据")
                                SearchPill(query.value,{query.value=it;editing.value=true},{choose(query.value)},{},
                                    onFocus={editing.value=true},requestFocus=editing.value)
                                LazyColumn(Modifier.weight(1f).imePadding(),contentPadding=PaddingValues(15.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                    if(editing.value)searchAssistItems(words,::choose,controller::retryHot)
                                    else item{Text("示例搜索结果")}
                                }
                            }
                        }
                    }
                }}}
                waitFor("热搜推荐");waitFor("搜索 音乐现场 · 示例");shot("fixture-hot-$name")
                click("搜索 音乐现场 · 示例");waitFor("示例搜索结果");assertEquals("音乐现场 · 示例",chosen)
                click("搜索关键词");input("周深");waitFor("搜索联想");waitFor("搜索 周深现场 · 示例");shot("fixture-suggest-$name")
                click("搜索 周深现场 · 示例");waitFor("示例搜索结果");assertEquals("周深现场 · 示例",chosen)
                click("清空关键词");waitFor("热搜推荐");assertNull(find("搜索 周深现场 · 示例"))
                controller.hide()
            }
        }finally{scope.cancel();i.runOnMainSync{a.finish()}}
    }
    @Test fun officialHintsAndSelectionReachExistingSearchWithoutAutoplay()=runBlocking<Unit> {
        val hot=app.searchAssist.hot();val suggestions=app.searchAssist.suggest("周深")
        assertTrue("Official hot words returned",hot.isNotEmpty());assertTrue("Official suggestions returned",suggestions.isNotEmpty())
        val a=start()
        try {
            val vm=MainActivity::class.java.getDeclaredField("playbackVm").apply{isAccessible=true}.get(a) as MainViewModel
            assertFalse(vm.state.value.playRequested)
            val before=vm.state.value.positionMs
            click("搜索关键词");waitFor("热搜推荐");waitFor("搜索 "+hot.first().label)
            input("周深");waitFor("搜索联想");waitFor("搜索 "+suggestions.first().label)
            click("搜索 "+suggestions.first().label)
            assertEquals(suggestions.first().keyword,vm.state.value.searchKeyword)
            withTimeout(25000){while(vm.state.value.searchBusy)delay(100)}
            assertNotNull("Actual video search result required, not just navigation",vm.state.value.search)
            assertTrue(vm.state.value.search!!.items.isNotEmpty())
            assertNull(vm.state.value.error)
            assertNull(find("搜索联想"));assertFalse(vm.state.value.playRequested);assertEquals(before,vm.state.value.positionMs)
            File(app.filesDir,"search-assist-evidence/official.json").apply{parentFile!!.mkdirs();writeText(
                """{"hotCount":${hot.size},"suggestionCount":${suggestions.size},"selectionEnteredSearch":true,"autoplay":false,"pausedPositionRetained":true,"actualVideoResultsRequiredAndLoaded":true,"videoCount":${vm.state.value.search!!.items.size}}""")}
        }finally{i.runOnMainSync{a.finish()}}
    }
}
