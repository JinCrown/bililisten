package app.bililisten

import app.bililisten.shared.*
import org.junit.Assert.*
import org.junit.Test

class FollowedLibraryTest {
    private val folder=ContentSource(SourceRef(SourceKind.PUBLIC_FAVORITES,42,8),"收藏夹","作者",200,"folder-cover")
    private val season=ContentSource(SourceRef(SourceKind.UP_COLLECTION,42,8,CollectionKind.SEASON),"合集","作者",40,"season-cover")
    private fun saved(source:ContentSource)=CollectionSnapshot("7",source.ref,emptyList(),false,0,0,source.title,source.ownerName,source.count,true,source.cover)
    @Test fun mixedRemoteSourcesKeepTypeIdentityAndRemoteWinsDuplicateLocalShortcut() {
        val old=folder.copy(title="旧名称",cover="old-cover")
        val local=folder.copy(ref=folder.ref.copy(id=43),title="本机旧入口")
        val up=ContentSource(SourceRef(SourceKind.UP_UPLOADS,8,8),"UP 投稿","作者",1887)
        val rows=followedLibraryRows(ScreenState(sources=SourceListPage(listOf(folder,season,folder),1,true),bookmarks=listOf(saved(old),saved(season),saved(local),saved(up))))
        assertEquals(listOf(folder,season,local),rows.map{it.source})
        assertEquals(listOf("收藏夹","合集","收藏夹"),rows.map{it.typeLabel})
        assertEquals(listOf("追更的别人收藏夹","已追更的合集","本机保存的收藏夹入口"),rows.map{it.statusLabel})
        assertEquals(listOf(true,true,false),rows.map{it.followed})
    }
    @Test fun browsedPublicFoldersNeverPretendToBeAccountSubscriptionsAndFailureKeepsLocalShortcuts() {
        val state=ScreenState(sourceListOwner=8,sources=SourceListPage(listOf(folder),1,false),bookmarks=listOf(saved(folder),saved(season)),error="网络失败")
        assertEquals(listOf(folder,season),followedLibraryRows(state).map{it.source})
        assertTrue(followedLibraryRows(state).none{it.followed})
        assertEquals(followedLibraryRows(state),followedLibraryRows(state.copy(sources=null)))
    }
}
