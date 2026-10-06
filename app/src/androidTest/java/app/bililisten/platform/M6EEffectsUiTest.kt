package app.bililisten.platform

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.os.SystemClock
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.bililisten.*
import app.bililisten.shared.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@androidx.media3.common.util.UnstableApi @RunWith(AndroidJUnit4::class)
class M6EEffectsUiTest {
    private val i=InstrumentationRegistry.getInstrumentation()
    private val app=i.targetContext.applicationContext as ListenApplication
    private fun find(text:String):AccessibilityNodeInfo? {
        fun visit(n:AccessibilityNodeInfo?):AccessibilityNodeInfo? {if(n==null||!n.refresh())return null;if(n.text?.toString()==text||n.contentDescription?.toString()==text)return n;for(k in 0 until n.childCount)visit(n.getChild(k))?.let{return it};return null}
        return visit(i.uiAutomation.rootInActiveWindow)
    }
    private fun gesture(x1:Float,y1:Float,x2:Float,y2:Float) {
        val down=SystemClock.uptimeMillis()
        fun event(action:Int,x:Float,y:Float,time:Long){val e=MotionEvent.obtain(down,time,action,x,y,0);e.source=android.view.InputDevice.SOURCE_TOUCHSCREEN;assertTrue(i.uiAutomation.injectInputEvent(e,true));e.recycle()}
        event(MotionEvent.ACTION_DOWN,x1,y1,down)
        for(step in 1..10){Thread.sleep(20);event(MotionEvent.ACTION_MOVE,x1+(x2-x1)*step/10,y1+(y2-y1)*step/10,SystemClock.uptimeMillis())}
        event(MotionEvent.ACTION_UP,x2,y2,SystemClock.uptimeMillis());i.waitForIdleSync();Thread.sleep(300)
    }
    private fun scroll():Boolean {
        val bounds=Rect();i.uiAutomation.rootInActiveWindow.getBoundsInScreen(bounds)
        gesture((bounds.right-35).toFloat(),(bounds.top+bounds.height()*.80f),(bounds.right-35).toFloat(),bounds.top+bounds.height()*.35f)
        return true
    }
    private fun waitFor(text:String){val end=System.currentTimeMillis()+15000;while(System.currentTimeMillis()<end){if(find(text)!=null)return;Thread.sleep(100)};error("Missing $text")}
    private fun locate(text:String){repeat(8){if(find(text)!=null)return;if(!scroll())return@repeat};waitFor(text)}
    private fun click(text:String){locate(text);var n=find(text);while(n!=null&&!n.isClickable)n=n.parent;assertTrue(n?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true);i.waitForIdleSync();Thread.sleep(250)}
    private fun start():MainActivity {
        assertFalse(app.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked)
        val a=i.startActivitySync(Intent(app,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        waitFor("首页");return a
    }
    private fun shot(name:String){val out=File(app.filesDir,"m6e-evidence").apply{mkdirs()};i.uiAutomation.takeScreenshot()?.let{bmp->File(out,"$name.png").outputStream().use{bmp.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bmp.recycle()}}
    @Test fun localFixtureNativePresetSliderFailuresAndResetAreExplicit() {
        val a=start();val bands=listOf(EffectBand(60_000,0),EffectBand(1_000_000,0))
        val state=mutableStateOf(ScreenState(connected=true,effects=EffectsView(42,"本机界面测试输出",EffectDetail(EffectPhase.APPLIED,bands=bands,minLevel=-1500,maxLevel=1500,presets=listOf(EffectPreset(7,"本机预设测试"))),EffectDetail(EffectPhase.UNSUPPORTED,message="本机不支持状态测试"))))
        var retries=0
        i.runOnMainSync{a.setContent{ListenTheme(Theme.LIGHT){Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp)){AudioEffectsPanel(state.value,{state.value=state.value.copy(settings=state.value.settings.copy(effects=it))},{retries++})}}}}
        click("音效总开关");assertTrue(state.value.settings.effects.enabled)
        click("选择均衡器预设");click("设备预设 · 本机预设测试");assertEquals("本机预设测试",state.value.settings.effects.preset)
        locate("均衡器 60.0 Hz")
        val sliderBounds=Rect();find("均衡器 60.0 Hz")!!.getBoundsInScreen(sliderBounds)
        gesture(sliderBounds.exactCenterX(),sliderBounds.exactCenterY(),sliderBounds.left+sliderBounds.width()*.35f,sliderBounds.exactCenterY())
        i.waitForIdleSync();assertNull(state.value.settings.effects.preset);assertTrue(state.value.settings.effects.bands.first().levelMilliBel<0)
        click("低音增强开关");locate("低音增强：不支持");waitFor("本机不支持状态测试")
        click("重新检查音效");assertEquals(1,retries);shot("fixture-unsupported")
        click("恢复默认并关闭");assertEquals(EffectsSettings(),state.value.settings.effects)
        i.runOnMainSync{a.finish()}
    }
    @Test fun actualPlayerAndSettingsEntriesStayPaused() {
        val a=start();val f=MainActivity::class.java.getDeclaredField("playbackVm").apply{isAccessible=true};val vm=f.get(a) as MainViewModel
        val deadline=System.currentTimeMillis()+15000;while(!vm.state.value.connected&&System.currentTimeMillis()<deadline)Thread.sleep(100)
        assertTrue(vm.state.value.connected);assertFalse(vm.state.value.playRequested)
        val before=vm.state.value
        click("展开播放页");click("播放更多操作");waitFor("定时");waitFor("歌词");waitFor("音质");assertNull(find("均衡器与音效"))
        i.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);Thread.sleep(300);click("返回");click("我的");click("设置");click("均衡器与音效");waitFor("音效总开关");waitFor("均衡器：未启用");shot("actual-default-off")
        click("恢复默认并关闭");assertFalse(vm.state.value.playRequested);assertEquals(before.positionMs,vm.state.value.positionMs)
        i.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);Thread.sleep(300);click("均衡器与音效");waitFor("音效总开关")
        assertEquals(before.queue,vm.state.value.queue);assertFalse(vm.state.value.playRequested);assertEquals(before.positionMs,vm.state.value.positionMs)
        i.runOnMainSync{a.finish()}
    }
}
