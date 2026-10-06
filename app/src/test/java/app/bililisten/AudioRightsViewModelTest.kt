package app.bililisten

import app.bililisten.shared.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

@OptIn(ExperimentalCoroutinesApi::class)
class AudioRightsViewModelTest {
    private inline fun <reified T> unused():T=Proxy.newProxyInstance(T::class.java.classLoader,arrayOf(T::class.java)){_,method,_->error("Unexpected ${method.name}")} as T
    @Test fun officialReturnBypassesThrottleRechecksActualTracksAndNeverStartsPlayback()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var verified=0;var refreshed=0;var paused=0;var cleared=0;var expired=false
        val accounts=object:AccountRepository {
            override val session=MutableStateFlow(AccountSession(SessionStamp("7",1),SessionStatus.AUTHENTICATED,Account(7,"fixture",Membership.VIP)))
            override suspend fun verify():Account? {verified++;if(expired){session.value=AccountSession(SessionStamp("guest",2),SessionStatus.GUEST);throw PlatformFailure("登录已失效",-101)};return session.value.account}
            override suspend fun accept(cookie:String)=error("unused")
            override suspend fun logout()=error("unused")
            override fun requireCurrent(stamp:SessionStamp){require(stamp==session.value.stamp)}
        }
        val entry=QueueEntry("a","BV1xx411c7mD",1,1,"fixture")
        val playback=object:PlaybackPort {
            override val state=MutableStateFlow(PlaybackView(connected=true,queue=listOf(entry),currentId="a"))
            override suspend fun audioQuality(choice:AudioChoice?){assertNull(choice);refreshed++}
            override fun pause(reason:PauseReason){paused++;assertEquals(PauseReason.EXTERNAL_VIDEO,reason)}
            override suspend fun replace(snapshot:ResumeSnapshot,play:Boolean)=error("Must not start playback")
            override suspend fun flush(){}
            override suspend fun clear(){cleared++;state.value=PlaybackView(connected=true)}
            override suspend fun mode(mode:PlayMode)=error("unused")
            override suspend fun forget(video:VideoRef?)=error("unused")
            override suspend fun live(room:LiveRoom,consent:Boolean)=error("unused")
            override suspend fun edit(edit:QueueEdit,expectedVersion:Long)=error("unused")
            override fun seekTo(positionMs:Long)=error("unused")
            override fun toggle()=error("Must not play")
            override fun next()=error("unused");override fun previous()=error("unused");override fun seekBy(delta:Long)=error("unused");override fun close(){}
        }
        val history=object:LocalHistoryRepository {
            override fun observe(account:String)=flowOf(emptyList<LocalHistoryEntry>())
            override suspend fun prune(account:String,policy:HistoryRetentionPolicy,now:Long){}
            override suspend fun delete(account:String,video:VideoRef?)=error("unused")
        }
        val snapshots=object:PlaybackStateStore {override suspend fun load(account:String):PlaybackSnapshot?=null;override suspend fun checkpoint(snapshot:PlaybackSnapshot,heard:LocalHistoryEntry?)=error("unused")}
        val favorites=object:FavoriteRepository {
            override suspend fun pending(account:String):MutationRecord?=null
            override suspend fun folders(account:Long,aid:Long?)=error("unused")
            override suspend fun page(folder:Long,page:Int)=error("unused")
            override suspend fun change(account:Long,aid:Long,folder:Long,add:Boolean)=error("unused")
            override suspend fun reconcile(account:Long)=error("unused")
        }
        val settings=object:SettingsRepository {override val settings=MutableStateFlow(UserSettings());override suspend fun update(value:UserSettings){settings.value=value}}
        val clock=object:Clock{override fun nowMs()=1000L}
        val vm=MainViewModel(AppDependencies(accounts,favorites,unused(),unused(),unused(),unused(),unused(),unused(),history,snapshots,settings,playback,BiliJumpPort,clock,DiagnosticLog(clock)))
        val store=androidx.lifecycle.ViewModelStore().apply{put("test",vm)}
        try {
            runCurrent();assertEquals(1,verified)
            vm.openOfficialRights({false},{error("Cannot launch")});runCurrent();assertEquals(0,paused)
            var opened=""
            vm.openOfficialRights({true},{opened=it});runCurrent()
            assertEquals("https://account.bilibili.com/account/big",opened);assertEquals(1,paused)
            vm.backgrounded();vm.foregrounded();runCurrent()
            assertEquals(2,verified);assertEquals(1,refreshed);assertFalse(vm.state.value.playRequested)
            assertEquals(Membership.VIP,vm.state.value.account?.membership)
            vm.openOfficialRights({true},{});runCurrent();expired=true
            vm.backgrounded();vm.foregrounded();runCurrent()
            assertEquals(1,cleared);assertNull(vm.state.value.account);assertEquals(LoginPhase.EXPIRED,vm.state.value.loginPhase)
            assertEquals(1,refreshed)
        }finally{store.clear();Dispatchers.resetMain()}
    }
}
