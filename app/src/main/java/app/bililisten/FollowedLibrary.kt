package app.bililisten

import app.bililisten.shared.*

internal data class FollowedLibraryRow(val source:ContentSource,val followed:Boolean) {
    val typeLabel get()=if(source.ref.kind==SourceKind.UP_COLLECTION)"合集" else "收藏夹"
    val statusLabel get()=if(followed) {
        if(source.ref.kind==SourceKind.UP_COLLECTION)"已追更的合集" else "追更的别人收藏夹"
    } else "本机保存的${typeLabel}入口"
}

/** Remote subscription identity wins; a saved local shortcut never invents a remote follow. */
internal fun followedLibraryRows(state:ScreenState):List<FollowedLibraryRow> {
    val kinds=setOf(SourceKind.PUBLIC_FAVORITES,SourceKind.UP_COLLECTION)
    val remote=state.sources?.takeIf{state.sourceListOwner==null}?.sources.orEmpty()
        .filter{it.ref.kind in kinds}.distinctBy{it.ref}
    val keys=remote.map{it.ref}.toSet()
    val local=state.bookmarks.filter{it.source.kind in kinds&&it.source !in keys}.distinctBy{it.source}
        .map{FollowedLibraryRow(ContentSource(it.source,it.title,it.ownerName,it.total,it.cover),false)}
    return remote.map{FollowedLibraryRow(it,true)}+local
}
