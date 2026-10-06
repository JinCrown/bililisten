package app.bililisten.playback

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.CommandButton
import androidx.media3.session.SessionCommand
import app.bililisten.R
import app.bililisten.playbackEntryTitle
import app.bililisten.shared.Video

internal fun MediaItem.withNotificationVideo(video:Video,artwork:ByteArray?=null):MediaItem {
    val entry=queueEntry() ?: return this
    if(video.bvid!=entry.bvid || video.parts.none{it.cid==entry.cid})return this
    val extras=Bundle(mediaMetadata.extras).apply {putString("queueTitle",entry.title)}
    return buildUpon().setMediaMetadata(mediaMetadata.buildUpon()
        .setTitle(playbackEntryTitle(video,entry,entry.title))
        .setArtist(video.author.ifBlank{"B站视频"})
        .setSubtitle(if(video.parts.size>1)"P${entry.part} · ${video.title}" else "B站视频")
        .setArtworkData(artwork,MediaMetadata.PICTURE_TYPE_FRONT_COVER)
        .setExtras(extras).build()).build()
}

internal data class NotificationFavoriteTarget(val id:String,val bvid:String,val account:String,val generation:Long)

@androidx.media3.common.util.UnstableApi
internal object NotificationControls {
    const val PREVIOUS="app.bililisten.NOTIFICATION_PREVIOUS"
    const val NEXT="app.bililisten.NOTIFICATION_NEXT"
    fun buttons(target:Bundle,previous:Boolean,next:Boolean,favorite:Boolean):List<CommandButton> {
        fun button(action:String,icon:Int,title:String,enabled:Boolean)=
            CommandButton.Builder(CommandButton.ICON_UNDEFINED).setCustomIconResId(icon)
                .setDisplayName(title).setSessionCommand(SessionCommand(action,Bundle(target)))
                .setEnabled(enabled)
        return listOf(
            button(PREVIOUS,R.drawable.ic_notification_previous,"上一首",previous).setSlots(CommandButton.SLOT_BACK).build(),
            button(NEXT,R.drawable.ic_notification_next,"下一首",next).setSlots(CommandButton.SLOT_FORWARD).build(),
            button(NotificationFavoriteTickets.ACTION,R.drawable.ic_notification_favorite,"收藏／取消收藏",favorite).setSlots(CommandButton.SLOT_BACK_SECONDARY,CommandButton.SLOT_OVERFLOW).build())
    }
    fun skip(player:Player,action:String,target:Bundle,account:String,generation:Long):Boolean {
        val item=player.currentMediaItem ?: return false
        if(item.queueEntry()==null || item.mediaMetadata.extras?.getBoolean("live")==true ||
            target.getString("id")!=item.mediaId || target.getLong("epoch",-1)!=item.mediaMetadata.extras?.getLong("epoch") ||
            target.getString("account")!=account || target.getLong("generation",-1)!=generation)return false
        when(action) {
            NEXT->if(player.hasNextMediaItem() && player.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM))player.seekToNextMediaItem() else return false
            PREVIOUS->if(player.hasPreviousMediaItem() && player.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM))player.seekToPreviousMediaItem()
                else if(player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM))player.seekTo(0) else return false
            else->return false
        }
        return true
    }
}

/** A short-lived, one-use ticket prevents an exported activity intent from authorizing a write. */
internal object NotificationFavoriteTickets {
    const val ACTION="app.bililisten.NOTIFICATION_FAVORITE"
    private var pending:Pair<String,NotificationFavoriteTarget>?=null
    @Synchronized fun issue(target:NotificationFavoriteTarget):String {
        val token=java.util.UUID.randomUUID().toString();pending=token to target;return token
    }
    @Synchronized fun take(token:String?):NotificationFavoriteTarget? {
        val value=pending?.takeIf{it.first==token} ?: return null
        pending=null;return value.second
    }
}
