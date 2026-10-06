package app.bililisten.shared

import kotlinx.serialization.Serializable

@Serializable enum class LibrarySection { MINE, FOLLOWED, UP }
@Serializable data class LibraryLayout(val section:LibrarySection,val hidden:Set<SourceRef> = emptySet(),
    val order:List<SourceRef> = emptyList(),val updatedAt:Long) {
    fun accepts(source:SourceRef)=when(section) {
        LibrarySection.MINE->source.kind==SourceKind.OWN_FAVORITES
        LibrarySection.FOLLOWED->source.kind in setOf(SourceKind.PUBLIC_FAVORITES,SourceKind.UP_COLLECTION)
        LibrarySection.UP->source.kind==SourceKind.UP_UPLOADS
    }
}
/** Per-category local layout; hidden sources remain available in management. */
@Serializable data class LibraryPreferences(val layouts:List<LibraryLayout> = emptyList()) {
    fun checked(account:String):LibraryPreferences {
        AccountRef(account);require(layouts.size<=3&&layouts.distinctBy{it.section}.size==layouts.size)
        layouts.forEach{layout->
            require(layout.updatedAt in 1 until Long.MAX_VALUE&&layout.hidden.size<=10000&&layout.order.size<=10000)
            require(layout.order.distinct().size==layout.order.size)
            (layout.hidden+layout.order).forEach{ref->ref.checked();require(layout.accepts(ref));require(ref.kind!=SourceKind.OWN_FAVORITES||ref.owner.toString()==account)}
        };return this
    }
    fun layout(section:LibrarySection)=layouts.firstOrNull{it.section==section}
    fun edit(section:LibrarySection,now:Long,change:(LibraryLayout)->LibraryLayout):LibraryPreferences {
        val old=layout(section) ?: LibraryLayout(section,updatedAt=1)
        val changed=change(old).copy(section=section,updatedAt=maxOf(now,old.updatedAt+1))
        return copy(layouts=(layouts.filterNot{it.section==section}+changed).sortedBy{it.section.ordinal})
    }
    fun hidden(source:SourceRef)=layouts.any{source in it.hidden}
    fun <T> apply(section:LibrarySection,rows:List<T>,showHidden:Boolean=false,key:(T)->SourceRef):List<T> {
        val layout=layout(section) ?: return rows
        val ranks=layout.order.withIndex().associate{it.value to it.index}
        return rows.filter{showHidden||key(it) !in layout.hidden}.sortedBy{ranks[key(it)] ?: Int.MAX_VALUE}
    }
    /** Newer local changes win per category; a legacy file has no layout to overwrite. */
    fun merge(incoming:LibraryPreferences)=LibraryPreferences(LibrarySection.entries.mapNotNull{section->
        val local=layout(section);val other=incoming.layout(section)
        if(other!=null&&(local==null||other.updatedAt>local.updatedAt))other else local
    })
}
interface LibraryPreferenceRepository {
    suspend fun read(stamp:SessionStamp):LibraryPreferences
    suspend fun update(stamp:SessionStamp,change:(LibraryPreferences)->LibraryPreferences):LibraryPreferences
}
