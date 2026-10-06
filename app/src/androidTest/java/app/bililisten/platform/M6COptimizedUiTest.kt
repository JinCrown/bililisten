package app.bililisten.platform

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Black-box checks also run against R8: no references to app classes or private fields. */
@UnstableApi @RunWith(AndroidJUnit4::class)
class M6COptimizedUiTest {
    private val i=InstrumentationRegistry.getInstrumentation()
    private fun nodes():List<AccessibilityNodeInfo> {val out=mutableListOf<AccessibilityNodeInfo>();fun walk(n:AccessibilityNodeInfo?){if(n==null)return;out+=n;for(k in 0 until n.childCount)walk(n.getChild(k))};walk(i.uiAutomation.rootInActiveWindow);return out}
    private fun find(label:String)=nodes().firstOrNull{it.text?.toString()==label||it.contentDescription?.toString()==label}
    private fun waitFor(label:String){val end=System.currentTimeMillis()+25000;while(System.currentTimeMillis()<end){if(find(label)!=null)return;Thread.sleep(100)};error("Missing $label")}
    private fun click(label:String){waitFor(label);var n=find(label)!!;while(!n.isClickable&&n.parent!=null)n=n.parent;assertTrue(n.performAction(AccessibilityNodeInfo.ACTION_CLICK));i.waitForIdleSync();Thread.sleep(250)}
    private fun <T> main(block:()->T):T {val out=AtomicReference<T>();val error=AtomicReference<Throwable>();i.runOnMainSync{try{out.set(block())}catch(e:Throwable){error.set(e)}};error.get()?.let{throw it};return out.get()}
    @Test fun optimizedRealTrackCommandsKeepPausePositionAndRenderSettingsPanel() {
        val context=i.targetContext
        i.startActivitySync(Intent().setClassName(context.packageName,"app.bililisten.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        waitFor("首页");Thread.sleep(2000)
        click("我的");click("设置");click("音质与播放");click("实际音质与输出");waitFor("音质与音频输出")
        waitFor("自动最高可用 · 已选择")
        val future=main{MediaController.Builder(context,SessionToken(context,ComponentName(context.packageName,"app.bililisten.playback.ListenService"))).buildAsync()}
        val c=future.get(15,TimeUnit.SECONDS)
        fun command(choice:String?=null):Int=main{c.sendCustomCommand(SessionCommand("app.bililisten.AUDIO_QUALITY",Bundle.EMPTY),Bundle().apply{choice?.let{putString("choice",it)}})}.get(20,TimeUnit.SECONDS).resultCode
        try {
            assertFalse(main{c.playWhenReady});assertTrue(main{c.mediaItemCount}>0)
            assertEquals(0,command())
            val audio=JSONObject(main{c.sessionExtras.getString("audio")}!!)
            val options=audio.getJSONArray("options");assertTrue(options.length()>0)
            val option=(0 until options.length()).map{options.getJSONObject(it)}.first{it.getBoolean("supported")}
            val position=main{c.currentPosition};val id=main{c.currentMediaItem?.mediaId}
            assertEquals(0,command(option.getJSONObject("choice").toString()))
            assertFalse(main{c.playWhenReady});assertEquals(position,main{c.currentPosition});assertEquals(id,main{c.currentMediaItem?.mediaId})
            assertEquals(0,command("{}"));assertFalse(main{c.playWhenReady});assertEquals(position,main{c.currentPosition})
            waitFor("自动最高可用 · 已选择")
            val screenshot=i.uiAutomation.takeScreenshot()
            val out=java.io.File(context.getExternalFilesDir(null),"m6c-evidence").apply{mkdirs()}
            screenshot?.let{bitmap->out.resolve("audio-panel-optimized.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}}
            out.resolve("optimized.json").writeText("""{"realTrackRefresh":true,"pausedManualSwitch":true,"autoRestored":true,"positionPreserved":true,"autoplay":false,"appReflectionUsed":false}""")
            println("OPTIMIZED_RESULT realTrackRefresh=true pausedManualSwitch=true positionPreserved=true autoplay=false")
        }finally{main{MediaController.releaseFuture(future)}}
    }
}
