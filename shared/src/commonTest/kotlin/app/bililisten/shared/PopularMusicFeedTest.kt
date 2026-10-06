package app.bililisten.shared

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.random.Random
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class PopularMusicFeedTest {
    private fun rows(start:Int,count:Int)=List(count){i->PopularMusic("BV"+(start+i).toString().padStart(10,'0'),"song ${start+i}","","UP",6000000)}
    @Test fun cacheSwapsToDifferentVideosWithoutNetworkOrHighestPlayResorting()=runTest {
        var reads=0
        val feed=PopularMusicFeed({_,_,ready->reads++;val all=rows(1,36);ready(all.take(3));all},{1000},{1},Random(42))
        val first=feed.load(emptySet()){}
        val second=feed.load(first.map{it.key}.toSet()){}
        assertEquals(12,first.size);assertEquals(12,second.size);assertEquals(1,reads)
        assertTrue(first.none{old->second.any{it.key==old.key}})
        val third=feed.load(second.map{it.key}.toSet()){}
        assertTrue(third.none{old->second.any{it.key==old.key}});assertEquals(1,reads)
    }
    @Test fun unusedCachedRowsAppearBeforeSlowRefillAndVerifiedBvidsAreExcluded()=runTest {
        var reads=0;var secondExclusions=emptySet<String>()
        val updates=mutableListOf<Pair<Long,List<PopularMusic>>>()
        val feed=PopularMusicFeed({round,excluded,ready->
            reads++;if(round==0)rows(1,18) else {
                secondExclusions=excluded;delay(1000);val all=rows(50,18);ready(all.take(4));all
            }
        },{1000},{1},Random(42))
        val first=feed.load(emptySet()){}
        val job=async{feed.load(first.map{it.key}.toSet()){updates+=testScheduler.currentTime to it}}
        runCurrent();assertEquals(6,updates.single().second.size);assertEquals(0L,updates.single().first)
        assertFalse(job.isCompleted);assertTrue(secondExclusions.containsAll(rows(1,18).map{it.key}))
        advanceUntilIdle();assertEquals(12,job.await().size);assertEquals(2,reads)
        assertTrue(updates.all{(_,list)->list.none{row->first.any{it.key==row.key}}})
    }
    @Test fun firstVerifiedBatchIsVisibleBeforeRemainingDetailsFinish()=runTest {
        var visible=emptyList<PopularMusic>()
        val feed=PopularMusicFeed({_,_,ready->val all=rows(1,18);delay(100);ready(all.take(4));delay(1000);all},{1000},{1})
        val job=async{feed.load(emptySet()){visible=it}}
        advanceTimeBy(101);runCurrent();assertEquals(4,visible.size);assertFalse(job.isCompleted)
        advanceUntilIdle();assertEquals(12,job.await().size)
    }
    @Test fun changedSessionDiscardsLateRowsAndOldPool()=runTest {
        var stamp=1L;var reads=0
        val feed=PopularMusicFeed({_,_,_->reads++;delay(1000);rows(reads*100,18)},{1000},{stamp})
        val old=async{runCatching{feed.load(emptySet()){}}}
        runCurrent();stamp=2;advanceUntilIdle();assertTrue(old.await().exceptionOrNull() is CancellationException)
        val fresh=feed.load(emptySet()){};assertTrue(fresh.all{it.title.startsWith("song 2")})
        assertEquals(2,reads)
    }
    @Test fun stalePoolIsRevalidatedAndFailuresRemainVisible()=runTest {
        var now=1000L;var reads=0;var fail=false
        val feed=PopularMusicFeed({_,_,_->reads++;if(fail)throw PlatformFailure("restricted",-412);rows(reads*100,18)},{now},{1})
        val first=feed.load(emptySet()){};now+=6L*3600000+1;fail=true
        assertFailsWith<PlatformFailure>{feed.load(first.map{it.key}.toSet()){}}
        assertEquals(2,reads)
    }
}
