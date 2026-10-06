package app.bililisten.platform

import android.content.Intent
import android.graphics.Rect
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
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

/** Synthetic callbacks never reach the real playback controller. */
@androidx.media3.common.util.UnstableApi @RunWith(AndroidJUnit4::class)
class MiniPlayerUiTest {
    private val i=InstrumentationRegistry.getInstrumentation()
    private val app=i.targetContext.applicationContext as ListenApplication
    private var opens=0
    private var otherActions=0
    private fun title():AccessibilityNodeInfo? {
        fun visit(n:AccessibilityNodeInfo?):AccessibilityNodeInfo? {
            if(n==null||!n.refresh()||!n.isVisibleToUser)return null
            if(n.contentDescription?.toString()=="展开播放页")return n
            for(k in 0 until n.childCount)visit(n.getChild(k))?.let{return it}
            return null
        }
        return visit(i.uiAutomation.rootInActiveWindow)
    }
    private fun event(down:Long,action:Int,r:Rect){
        val e=MotionEvent.obtain(down,android.os.SystemClock.uptimeMillis(),action,r.exactCenterX(),r.exactCenterY(),0)
        e.source=InputDevice.SOURCE_TOUCHSCREEN
        assertTrue(i.uiAutomation.injectInputEvent(e,true));e.recycle()
    }
    @Test fun titlePressHasNoRectangularIndicationAndStillOpensPlayer(){
        assertFalse(app.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked)
        val folder=File(app.filesDir,"player-buffering-evidence").apply{check(mkdirs()||isDirectory)}
        val rows=JSONArray()
        val a=i.startActivitySync(Intent(app,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        try {
            for(theme in listOf(Theme.LIGHT,Theme.DARK)){
                i.runOnMainSync{a.setContent{ListenTheme(theme){Column(Modifier.fillMaxSize().safeDrawingPadding(),verticalArrangement=Arrangement.Bottom){
                    MiniPlayer(ScreenState(playingTitle="示例歌曲",durationMs=100000),null,null,25000,
                        {opens++},{otherActions++},{otherActions++},{otherActions++},{otherActions++},{otherActions++})
                }}}}
                i.waitForIdleSync();Thread.sleep(400)
                val r=Rect().also{checkNotNull(title()).getBoundsInScreen(it)}
                val before=checkNotNull(i.uiAutomation.takeScreenshot())
                val down=android.os.SystemClock.uptimeMillis()
                try {
                    event(down,MotionEvent.ACTION_DOWN,r)
                    Thread.sleep(100)
                    val held=checkNotNull(i.uiAutomation.takeScreenshot())
                    try {
                        var changed=0
                        for(x in r.left until r.right)for(y in r.top until r.bottom)if(before.getPixel(x,y)!=held.getPixel(x,y))changed++
                        assertEquals("Title pixels stay unchanged while pressed",0,changed)
                        File(folder,"mini-title-${theme.name.lowercase()}-pressed.png").outputStream().use{held.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
                        rows.put(JSONObject().put("theme",theme.name).put("changedTitlePixels",changed))
                    }finally{held.recycle()}
                }finally{event(down,MotionEvent.ACTION_UP,r);before.recycle()}
                i.waitForIdleSync();Thread.sleep(100)
            }
            assertEquals(2,opens)
            assertEquals(0,otherActions)
            var actionNode=title()
            while(actionNode!=null&&!actionNode.isClickable)actionNode=actionNode.parent
            assertTrue(checkNotNull(actionNode).performAction(AccessibilityNodeInfo.ACTION_CLICK))
            i.waitForIdleSync();assertEquals("Accessibility click still opens player",3,opens)
            File(folder,"mini-title-press.json").writeText(JSONObject().put("samples",rows).put("syntheticOpens",opens)
                .put("otherActions",otherActions).put("playbackCommands",0).put("remoteWrites",0).toString(2))
        }finally{i.runOnMainSync{a.finish()}}
    }
}
