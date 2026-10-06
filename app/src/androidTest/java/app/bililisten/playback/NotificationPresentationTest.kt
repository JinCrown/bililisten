package app.bililisten.playback

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.bililisten.R
import app.bililisten.shared.*
import com.google.common.collect.ImmutableList
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated, unprepared player and inert actions: never starts audio or writes account favorites. */
@androidx.media3.common.util.UnstableApi @RunWith(AndroidJUnit4::class)
class NotificationPresentationTest {
    private val i=InstrumentationRegistry.getInstrumentation()
    @Test fun partTitleArtworkIconAndFavoriteRetainQueueIdentityWithoutPreparingAudio() {
        i.runOnMainSync {
            val entry=QueueEntry("fixture-part-2","BV1xx411c7mD",22,2,"视频总标题")
            val video=Video(entry.bvid,1,"视频总标题",listOf(VideoPart(11,1,"第一首"),VideoPart(22,2,"第二首")),author="示例作者")
            val bitmap=android.graphics.Bitmap.createBitmap(48,48,android.graphics.Bitmap.Config.ARGB_8888).apply{eraseColor(android.graphics.Color.rgb(60,180,120))}
            val bytes=java.io.ByteArrayOutputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it);it.toByteArray()};bitmap.recycle()
            val original=entry.mediaItem(PlayMode.SEQUENTIAL,9)
            val presented=original.withNotificationVideo(video,bytes)
            assertEquals("第二首",presented.mediaMetadata.title.toString());assertEquals(entry,presented.queueEntry())
            assertEquals(original.localConfiguration!!.uri,presented.localConfiguration!!.uri)
            assertEquals(9L,presented.mediaMetadata.extras!!.getLong("epoch"))
            assertArrayEquals(bytes,presented.mediaMetadata.artworkData)
            val player=ExoPlayer.Builder(i.targetContext).build()
            val session=MediaSession.Builder(i.targetContext,player).setId("notification-fixture").build()
            try {
                player.setMediaItem(presented);player.seekTo(12345L)
                val pending=PendingIntent.getBroadcast(i.targetContext,900,Intent("app.bililisten.INERT_TEST").setPackage(i.targetContext.packageName),PendingIntent.FLAG_IMMUTABLE)
                val factory=object:MediaNotification.ActionFactory {
                    override fun createMediaAction(s:MediaSession,icon:IconCompat,title:CharSequence,command:Int)=NotificationCompat.Action.Builder(icon,title,pending).build()
                    override fun createCustomAction(s:MediaSession,icon:IconCompat,title:CharSequence,action:String,extras:Bundle)=NotificationCompat.Action.Builder(icon,title,pending).build()
                    override fun createCustomActionFromCustomCommandButton(s:MediaSession,button:CommandButton)=NotificationCompat.Action.Builder(button.iconResId,button.displayName,pending).build()
                    override fun createMediaActionPendingIntent(s:MediaSession,command:Long)=pending
                }
                val buttons=NotificationControls.buttons(Bundle.EMPTY,true,true,true)
                val provider=DefaultMediaNotificationProvider(i.targetContext).apply{setSmallIcon(R.drawable.ic_notification)}
                val notification=provider.createNotification(session,ImmutableList.copyOf(buttons),factory){}.notification
                assertEquals(R.drawable.ic_notification,notification.smallIcon.resId)
                assertEquals("第二首",notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
                assertTrue(notification.actions.any{it.title=="收藏／取消收藏"})
                assertEquals(R.drawable.ic_notification_previous,notification.actions.first{it.title=="上一首"}.getIcon().resId)
                assertEquals(R.drawable.ic_notification_next,notification.actions.first{it.title=="下一首"}.getIcon().resId)
                assertEquals(R.drawable.ic_notification_favorite,notification.actions.first{it.title=="收藏／取消收藏"}.getIcon().resId)
                assertEquals(12345L,player.currentPosition);assertFalse(player.playWhenReady);assertEquals(Player.STATE_IDLE,player.playbackState)
            }finally{session.release();player.release()}
        }
    }
    @Test fun notificationIconsUseFilledSkipTrianglesAndThinHollowFavorite() {
        fun bitmap(id:Int):android.graphics.Bitmap {
            val b=android.graphics.Bitmap.createBitmap(96,96,android.graphics.Bitmap.Config.ARGB_8888)
            i.targetContext.getDrawable(id)!!.apply{setBounds(0,0,96,96);draw(android.graphics.Canvas(b))}
            return b
        }
        val previous=bitmap(R.drawable.ic_notification_previous)
        val next=bitmap(R.drawable.ic_notification_next)
        val star=bitmap(R.drawable.ic_notification_favorite)
        try {
            assertEquals(255,android.graphics.Color.alpha(previous.getPixel(60,48)))
            assertEquals(255,android.graphics.Color.alpha(next.getPixel(36,48)))
            assertEquals(0,android.graphics.Color.alpha(star.getPixel(48,48)))
            val buttons=NotificationControls.buttons(Bundle.EMPTY,false,false,false)
            assertTrue(buttons.all{it.icon==CommandButton.ICON_UNDEFINED && !it.isEnabled})
            val xml=i.targetContext.resources.getXml(R.drawable.ic_notification_favorite)
            try {while(xml.next()!=org.xmlpull.v1.XmlPullParser.END_DOCUMENT)if(xml.eventType==org.xmlpull.v1.XmlPullParser.START_TAG && xml.name=="path") {
                assertEquals("1.35",xml.getAttributeValue("http://schemas.android.com/apk/res/android","strokeWidth"))
            }}finally{xml.close()}
        }finally{previous.recycle();next.recycle();star.recycle()}
    }
    @Test fun notificationSkipsRemainPausedAndRejectStaleAccountsItemsAndLive() {
        i.runOnMainSync {
            val player=ExoPlayer.Builder(i.targetContext).build()
            fun target()=Bundle().apply{putString("id",player.currentMediaItem!!.mediaId);putLong("epoch",9);putString("account","fixture");putLong("generation",7)}
            try {
                player.setMediaItems((1..3).map{QueueEntry("fixture-$it","BV1xx411c7mD",it.toLong(),it,"示例").mediaItem(PlayMode.SEQUENTIAL,9)},1,12345L)
                val before=target()
                assertTrue(NotificationControls.skip(player,NotificationControls.NEXT,before,"fixture",7))
                assertEquals(2,player.currentMediaItemIndex)
                assertFalse(NotificationControls.skip(player,NotificationControls.PREVIOUS,before,"fixture",7))
                assertFalse(NotificationControls.skip(player,NotificationControls.PREVIOUS,target(),"other",7))
                assertFalse(NotificationControls.skip(player,NotificationControls.PREVIOUS,target(),"fixture",8))
                assertFalse(NotificationControls.skip(player,NotificationControls.NEXT,target(),"fixture",7))
                assertTrue(NotificationControls.skip(player,NotificationControls.PREVIOUS,target(),"fixture",7))
                assertEquals(1,player.currentMediaItemIndex)
                val current=player.currentMediaItem!!
                player.replaceMediaItem(1,current.buildUpon().setMediaMetadata(current.mediaMetadata.buildUpon()
                    .setExtras(Bundle(current.mediaMetadata.extras).apply{putBoolean("live",true)}).build()).build())
                assertFalse(NotificationControls.skip(player,NotificationControls.NEXT,target(),"fixture",7))
                assertEquals(1,player.currentMediaItemIndex);assertFalse(player.playWhenReady);assertEquals(Player.STATE_IDLE,player.playbackState)
                assertTrue(player.availableCommands.contains(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM))
            }finally{player.release()}
        }
    }
    @Test fun favoriteTicketsAreUnforgeableOneUseAndWebViewReallyIdentifiesAndroid() {
        val target=NotificationFavoriteTarget("part-2","BV1xx411c7mD","fixture",7)
        val token=NotificationFavoriteTickets.issue(target)
        assertNull(NotificationFavoriteTickets.take("forged"));assertEquals(target,NotificationFavoriteTickets.take(token));assertNull(NotificationFavoriteTickets.take(token))
        if(app.bililisten.platform.WebSession.available())i.runOnMainSync {assertTrue(android.webkit.WebSettings.getDefaultUserAgent(i.targetContext).contains("Android"))}
    }
    @Test fun frameworkStandardSkipAndCustomSkipBothWorkWithoutStartingAudio() {
        val player=java.util.concurrent.atomic.AtomicReference<ExoPlayer>()
        val session=java.util.concurrent.atomic.AtomicReference<MediaSession>()
        val standard=java.util.concurrent.CountDownLatch(1)
        val custom=java.util.concurrent.CountDownLatch(1)
        i.runOnMainSync {
            val p=ExoPlayer.Builder(i.targetContext).build();player.set(p)
            p.setMediaItems((1..2).map{QueueEntry("framework-$it","BV1xx411c7mD",it.toLong(),it,"示例").mediaItem(PlayMode.SEQUENTIAL,9)})
            p.addListener(object:Player.Listener {override fun onMediaItemTransition(item:androidx.media3.common.MediaItem?,reason:Int) {
                if(item?.mediaId=="framework-2")standard.countDown()
            }})
            session.set(MediaSession.Builder(i.targetContext,p).setId("framework-skip-fixture")
                .setMediaButtonPreferences(NotificationControls.buttons(Bundle.EMPTY,true,true,false))
                .setCallback(object:MediaSession.Callback {
                    override fun onConnect(s:MediaSession,c:MediaSession.ControllerInfo)=MediaSession.ConnectionResult.AcceptedResultBuilder(s)
                        .setAvailableSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                            .add(SessionCommand(NotificationControls.PREVIOUS,Bundle.EMPTY))
                            .add(SessionCommand(NotificationControls.NEXT,Bundle.EMPTY)).build()).build()
                    override fun onCustomCommand(s:MediaSession,c:MediaSession.ControllerInfo,command:SessionCommand,args:Bundle):com.google.common.util.concurrent.ListenableFuture<SessionResult> {
                        val applied=NotificationControls.skip(s.player,command.customAction,args,"fixture",7)
                        if(applied)custom.countDown()
                        return com.google.common.util.concurrent.Futures.immediateFuture(SessionResult(if(applied)SessionResult.RESULT_SUCCESS else SessionError.ERROR_INVALID_STATE))
                    }
                }).build())
            android.media.session.MediaController(i.targetContext,session.get().platformToken).transportControls.skipToNext()
        }
        try {
            assertTrue("Standard headset-compatible skip remains available",standard.await(10,java.util.concurrent.TimeUnit.SECONDS))
            i.runOnMainSync {
                android.media.session.MediaController(i.targetContext,session.get().platformToken).transportControls.sendCustomAction(NotificationControls.PREVIOUS,Bundle().apply {
                    putString("id","framework-2");putLong("epoch",9);putString("account","fixture");putLong("generation",7)
                })
            }
            assertTrue(custom.await(10,java.util.concurrent.TimeUnit.SECONDS))
            i.runOnMainSync {assertEquals(0,player.get().currentMediaItemIndex);assertFalse(player.get().playWhenReady);assertEquals(Player.STATE_IDLE,player.get().playbackState)}
        }finally{i.runOnMainSync{session.get().release();player.get().release()}}
    }
    @Test fun frameworkCustomActionsKeepTheirTargetInArgumentsInsteadOfCommandExtras() {
        val player=java.util.concurrent.atomic.AtomicReference<ExoPlayer>()
        val session=java.util.concurrent.atomic.AtomicReference<MediaSession>()
        val received=java.util.concurrent.atomic.AtomicReference<Pair<Bundle,Bundle>>()
        val latch=java.util.concurrent.CountDownLatch(1)
        i.runOnMainSync {
            player.set(ExoPlayer.Builder(i.targetContext).build())
            session.set(MediaSession.Builder(i.targetContext,player.get()).setId("legacy-notification-fixture").setCallback(object:MediaSession.Callback {
                override fun onConnect(s:MediaSession,c:MediaSession.ControllerInfo)=MediaSession.ConnectionResult.AcceptedResultBuilder(s)
                    .setAvailableSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon().add(SessionCommand(NotificationFavoriteTickets.ACTION,Bundle.EMPTY)).build()).build()
                override fun onCustomCommand(s:MediaSession,c:MediaSession.ControllerInfo,command:SessionCommand,args:Bundle):com.google.common.util.concurrent.ListenableFuture<SessionResult> {
                    received.set(Bundle(command.customExtras) to Bundle(args));latch.countDown()
                    return com.google.common.util.concurrent.Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
            }).build())
            android.media.session.MediaController(i.targetContext,session.get().platformToken).transportControls.sendCustomAction(NotificationFavoriteTickets.ACTION,Bundle().apply{putString("id","part-2");putLong("epoch",9)})
        }
        try {
            assertTrue(latch.await(10,java.util.concurrent.TimeUnit.SECONDS));assertTrue(received.get().first.isEmpty)
            assertEquals("part-2",received.get().second.getString("id"));assertEquals(9L,received.get().second.getLong("epoch"))
        }finally{i.runOnMainSync{session.get().release();player.get().release()}}
    }
    @Test fun lyricsfileSafeParserRunsOnAndroidWithoutInventedWordTimes() {
        val timing=LyricsfileParser.parse("version: '1.0'\nmetadata: {duration_ms: 3000}\nlines:\n  - text: '你好'\n    start_ms: 1000\n    end_ms: 3000\n    words:\n      - {text: '你', start_ms: 1000}\n      - {text: '好', start_ms: 1800}",3000)
        assertEquals(listOf(1000L,1800L),timing.words[0]!!.map{it.fromMs});assertNull(timing.words[0]!!.first().toMs)
    }
}
