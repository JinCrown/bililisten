package app.bililisten

import app.bililisten.shared.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {
    private inline fun <reified T> unused(): T = Proxy.newProxyInstance(T::class.java.classLoader,arrayOf(T::class.java)) { _, method, _ -> error("Unexpected ${method.name}") } as T
    private class Accounts : AccountRepository {
        override val session=MutableStateFlow(AccountSession(SessionStamp("7",1),SessionStatus.AUTHENTICATED,Account(7,"fixture")))
        override suspend fun verify()=session.value.account
        override suspend fun accept(cookie:String)=error("unused")
        override suspend fun logout(){session.value=AccountSession(SessionStamp("guest",2),SessionStatus.GUEST)}
        override fun requireCurrent(stamp:SessionStamp){if(stamp!=session.value.stamp)throw PlatformFailure("stale")}
    }
    private class Player : PlaybackPort {
        override val state=MutableStateFlow(PlaybackView(connected=true)); var replacements=0; var closed=false
        var precise=false
        override fun precisePosition(enabled:Boolean){precise=enabled}
        override suspend fun replace(snapshot:ResumeSnapshot,play:Boolean){replacements++}
        override suspend fun clear(){state.value=PlaybackView(connected=true)}
        override suspend fun flush(){}
        override suspend fun mode(mode:PlayMode){}
        override suspend fun forget(video:VideoRef?){}
        override suspend fun live(room:LiveRoom,consent:Boolean){}
        override suspend fun edit(edit:QueueEdit,expectedVersion:Long){}
        override fun seekTo(positionMs:Long){}
        override fun pause(reason:PauseReason){}
        override fun toggle(){}; override fun next(){}; override fun previous(){}; override fun seekBy(delta:Long){}
        override fun close(){closed=true}
    }
    @Test fun injectedViewModelLoadsIsolatedHistorySilentlyAndDropsOldAccountResult()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val accounts=Accounts(); val player=Player(); val result=CompletableDeferred<Video>()
        val historyAccounts=mutableListOf<String>()
        val content=object:ContentRepository {
            override suspend fun video(bvid:String)=result.await()
            override suspend fun search(keyword:String,page:Int)=error("unused")
            override suspend fun audio(bvid:String,cid:Long)=error("unused")
        }
        val history=object:LocalHistoryRepository {
            override fun observe(account:String):Flow<List<LocalHistoryEntry>> {historyAccounts+=account;return flowOf(emptyList())}
            override suspend fun delete(account:String,video:VideoRef?){}
            override suspend fun prune(account:String,policy:HistoryRetentionPolicy,now:Long){}
        }
        val snapshots=object:PlaybackStateStore {
            override suspend fun load(account:String):PlaybackSnapshot?=null
            override suspend fun checkpoint(snapshot:PlaybackSnapshot,heard:LocalHistoryEntry?){}
        }
        val favorites=object:FavoriteRepository {
            override suspend fun pending(account:String):MutationRecord?=null
            override suspend fun folders(account:Long,aid:Long?)=error("unused")
            override suspend fun page(folder:Long,page:Int)=error("unused")
            override suspend fun change(account:Long,aid:Long,folder:Long,add:Boolean)=error("unused")
            override suspend fun reconcile(account:Long)=error("unused")
        }
        val settings=object:SettingsRepository {override val settings=MutableStateFlow(UserSettings());override suspend fun update(value:UserSettings){settings.value=value}}
        val clock=object:Clock{override fun nowMs()=1000L}
        val vm=MainViewModel(AppDependencies(accounts,favorites,content,unused(),unused(),unused(),unused(),unused(),history,snapshots,settings,player,BiliJumpPort,clock,DiagnosticLog(clock)))
        val owner=androidx.lifecycle.ViewModelStore()
        owner.put("fixture",vm)
        try {
            runCurrent(); assertTrue(vm.state.value.accountChecked); assertEquals(listOf("7"),historyAccounts)
            assertEquals(0,player.replacements)
            vm.inspect("BV1xx411c7mD"); runCurrent(); accounts.logout()
            result.complete(Video("BV1xx411c7mD",1,"old private result",listOf(VideoPart(1,1,""))))
            runCurrent(); assertNull(vm.state.value.video); assertFalse(vm.state.value.busy)
            vm.checkAccount(); runCurrent(); assertNull(vm.state.value.account); assertEquals(listOf("7","guest"),historyAccounts)
            vm.updateSettings(UserSettings(false,30,50));runCurrent();assertEquals(50,vm.state.value.settings.historyLimit)
            vm.timedTextVisible(true);assertFalse("Background reader cannot enable fast sampling",player.precise)
            vm.foregrounded();assertTrue(player.precise);runCurrent()
            vm.backgrounded();assertFalse("Screen off must disable fast sampling",player.precise);runCurrent()
            vm.foregrounded();assertTrue("Returning to a reader restores fast sampling",player.precise)
            vm.timedTextVisible(false);assertFalse("Leaving the reader restores low-frequency sampling",player.precise)
        } finally {owner.clear();Dispatchers.resetMain()}
        assertTrue(player.closed)
    }
}
