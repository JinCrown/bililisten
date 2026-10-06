package app.bililisten.platform

import android.content.Intent
import android.graphics.Rect
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.bililisten.*
import app.bililisten.shared.Theme
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/** Screenshots and seeking use synthetic state only; no playback controller or network writes. */
@androidx.media3.common.util.UnstableApi @RunWith(AndroidJUnit4::class)
class PlaybackSeekUiTest {
    private val i=InstrumentationRegistry.getInstrumentation()
    private val app=i.targetContext.applicationContext as ListenApplication
    private val position=mutableLongStateOf(50000L)
    private val enabled=mutableStateOf(true)
    @Volatile private var accent=0
    @Volatile private var thumbWidthPx=0
    private var seeks=0
    private fun node():AccessibilityNodeInfo? {
        fun visit(n:AccessibilityNodeInfo?):AccessibilityNodeInfo? {
            if(n==null||!n.refresh()||!n.isVisibleToUser)return null
            if(n.contentDescription?.toString()=="播放进度")return n
            for(k in 0 until n.childCount)visit(n.getChild(k))?.let{return it}
            return null
        }
        return visit(i.uiAutomation.rootInActiveWindow)
    }
    private fun bounds():Rect {
        val end=System.currentTimeMillis()+10000
        while(System.currentTimeMillis()<end){node()?.let{return Rect().also(it::getBoundsInScreen)};Thread.sleep(50)}
        error("Missing synthetic slider")
    }
    private fun fixture(a:MainActivity,theme:Theme,font:Float,light:Boolean,narrow:Boolean){i.runOnMainSync{
        a.setContent{ListenTheme(theme){val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,font)){
                accent=(if(light)ImmersivePink else MaterialTheme.colorScheme.primary).toArgb()
                thumbWidthPx=with(LocalDensity.current){13.dp.roundToPx()}
                Column(Modifier.fillMaxSize().background(if(light)Color(0xFF42344F) else MaterialTheme.colorScheme.background)
                    .safeDrawingPadding().padding(24.dp)){
                    Text("进度预览 · 示例数据",fontSize=12.sp)
                    Spacer(Modifier.height(40.dp))
                    Box(if(narrow)Modifier.width(320.dp) else Modifier.fillMaxWidth()){
                        PlaybackSeek(position.longValue,100000L,enabled.value,{seeks++;position.longValue=it},light)
                    }
                }
            }
        }}
    }}
    private fun settle(){i.waitForIdleSync();Thread.sleep(200)}
    private fun event(down:Long,action:Int,x:Float,y:Float){
        val event=MotionEvent.obtain(down,android.os.SystemClock.uptimeMillis(),action,x,y,0)
        event.source=InputDevice.SOURCE_TOUCHSCREEN
        assertTrue(i.uiAutomation.injectInputEvent(event,true));event.recycle()
    }
    private fun longestColoredRun(bitmap:android.graphics.Bitmap,x:Int,r:Rect):Pair<Int,Int>{
        var start=-1;var best=-1 to -1
        for(y in r.top.coerceAtLeast(0)..r.bottom.coerceAtMost(bitmap.height-1)){
            val match=bitmap.getPixel(x,y)==accent
            if(match&&start<0)start=y
            if(start>=0&&(!match||y==r.bottom.coerceAtMost(bitmap.height-1))){
                val end=if(match)y else y-1
                if(end-start>best.second-best.first)best=start to end
                start=-1
            }
        }
        return best
    }
    @Test fun paintedThumbAndTrackAlignAndSeekingStillWorks(){
        assertFalse(app.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked)
        val baseline=InstrumentationRegistry.getArguments().getString("sliderAlignmentBaseline")=="1"
        val prefix=if(baseline)"baseline" else "fixed"
        val folder=File(app.filesDir,"player-buffering-evidence").apply{check(mkdirs()||isDirectory)}
        val rows=JSONArray()
        val frames=JSONArray()
        val a=i.startActivitySync(Intent(app,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        try {
            for((theme,font,name)in listOf(Triple(Theme.LIGHT,1f,"light"),Triple(Theme.DARK,1f,"dark"),
                Triple(Theme.LIGHT,1.6f,"large"),Triple(Theme.DARK,1f,"immersive"))){
                i.runOnMainSync{enabled.value=true;position.longValue=50000L}
                fixture(a,theme,font,name=="immersive",name=="large");settle()
                for(fraction in listOf(.25f,.5f,.75f)){
                    i.runOnMainSync{position.longValue=(fraction*100000).toLong()};settle()
                    val r=bounds();val bitmap=checkNotNull(i.uiAutomation.takeScreenshot())
                    try {
                        val thumbX=(r.left+(r.width()-thumbWidthPx)*fraction+thumbWidthPx/2f).roundToInt()
                        File(folder,"$prefix-seek-diagnostic.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
                        val palette=mutableMapOf<Int,Int>()
                        for(x in listOf(r.left+thumbWidthPx+8,thumbX))for(y in r.top.coerceAtLeast(0)..r.bottom.coerceAtMost(bitmap.height-1)){
                            val pixel=bitmap.getPixel(x,y);palette[pixel]=(palette[pixel] ?: 0)+1
                        }
                        frames.put(JSONObject().put("case",name).put("fraction",fraction).put("bounds",r.toShortString())
                            .put("accent",accent.toUInt().toString(16)).put("thumbX",thumbX).put("thumbWidthPx",thumbWidthPx)
                            .put("columnColors",JSONObject(palette.mapKeys{it.key.toUInt().toString(16)})))
                        // Accessibility expands the horizontal touch bounds beyond the painted track.
                        val track=longestColoredRun(bitmap,r.left+r.width()/8,r)
                        val thumb=((thumbX-thumbWidthPx).coerceAtLeast(0)..(thumbX+thumbWidthPx).coerceAtMost(bitmap.width-1))
                            .map{longestColoredRun(bitmap,it,r)}.maxBy{it.second-it.first}
                        assertTrue("Visible track pixels",track.first>=0&&track.second>track.first)
                        assertTrue("Visible circular thumb",thumb.first>=0&&thumb.second-thumb.first>thumbWidthPx*.65f)
                        val trackCenter=(track.first+track.second)/2.0;val thumbCenter=(thumb.first+thumb.second)/2.0
                        val delta=thumbCenter-trackCenter
                        rows.put(JSONObject().put("case",name).put("fontScale",font).put("fraction",fraction)
                            .put("thumbCenterY",thumbCenter).put("trackCenterY",trackCenter).put("centerDeltaPx",delta))
                        if(!baseline)assertTrue("$name painted thumb and track must align; delta=$delta pixels",abs(delta)<=1.5)
                        if(fraction==.5f)File(folder,"$prefix-seek-$name.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
                    }finally{bitmap.recycle()}
                }
                i.runOnMainSync{position.longValue=50000L};settle();val r=bounds()
                val down=android.os.SystemClock.uptimeMillis()
                event(down,MotionEvent.ACTION_DOWN,r.exactCenterX(),r.exactCenterY())
                for(k in 1..12){Thread.sleep(15);event(down,MotionEvent.ACTION_MOVE,r.exactCenterX()+r.width()*.25f*k/12,r.exactCenterY())}
                event(down,MotionEvent.ACTION_UP,r.left+r.width()*.75f,r.exactCenterY());settle()
                assertTrue("Synthetic slider still commits a seek",seeks>0&&position.longValue in 70000L..80000L)
                i.runOnMainSync{enabled.value=false};settle();val prior=seeks
                val tap=android.os.SystemClock.uptimeMillis()
                event(tap,MotionEvent.ACTION_DOWN,r.left+r.width()*.3f,r.exactCenterY())
                event(tap,MotionEvent.ACTION_UP,r.left+r.width()*.3f,r.exactCenterY());settle()
                assertEquals("Disabled seeking remains disabled",prior,seeks)
            }
        }finally{
            File(folder,"$prefix-seek-alignment.json").writeText(JSONObject().put("baseline",baseline).put("samples",rows).put("frames",frames)
                .put("syntheticSeekCalls",seeks).put("playbackCommands",0).put("remoteWrites",0).toString(2))
            i.runOnMainSync{a.finish()}
        }
    }
}
