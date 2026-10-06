package app.bililisten.shared

import kotlinx.serialization.Serializable

@Serializable data class EffectBand(val frequencyMilliHz:Int,val levelMilliBel:Int)
@Serializable data class EffectsSettings(val enabled:Boolean=false,val equalizer:Boolean=true,val preset:String?=null,
    val bands:List<EffectBand> = emptyList(),val bass:Boolean=false,val bassStrength:Int=500) {
    fun checked():EffectsSettings {
        require(preset==null || preset.isNotBlank()&&preset.length<=80)
        require(bands.size<=32&&bands.map{it.frequencyMilliHz}.distinct().size==bands.size)
        require(bands.all{it.frequencyMilliHz in 1..100_000_000&&it.levelMilliBel in -2400..2400})
        require(bassStrength in 0..1000);return this
    }
    fun custom(actual:List<EffectBand>,frequency:Int,level:Int)=copy(preset=null,bands=actual.map{if(it.frequencyMilliHz==frequency)it.copy(levelMilliBel=level)else it}).checked()
}
@Serializable enum class EffectPhase(val label:String) {
    OFF("未启用"),WAITING_SESSION("等待音频会话"),APPLIED("系统已启用"),CONTROL_LOST("暂时无法控制"),UNSUPPORTED("不支持"),FAILED("未生效")
}
@Serializable data class EffectPreset(val id:Int,val name:String)
@Serializable data class EffectDetail(val phase:EffectPhase=EffectPhase.OFF,val message:String="",val bands:List<EffectBand> = emptyList(),
    val minLevel:Int=0,val maxLevel:Int=0,val presets:List<EffectPreset> = emptyList(),val actualPreset:String?=null,
    val actualBassStrength:Int?=null,val adjustableBass:Boolean=false)
@Serializable data class EffectsView(val sessionId:Int=0,val routeName:String="",val equalizer:EffectDetail=EffectDetail(),val bass:EffectDetail=EffectDetail())
data class EffectsContext(val sessionId:Int=0,val routeId:Int?=null,val routeType:Int?=null)
interface EffectEngine {
    val control:Boolean;val enabled:Boolean
    fun enable(value:Boolean)
    fun close()
}
interface EqualizerEngine:EffectEngine {
    val frequencies:List<Int>;val range:IntRange;val presets:List<EffectPreset>;val currentPreset:Int
    fun preset(id:Int);fun level(index:Int,value:Int);fun levels():List<Int>
}
interface BassEngine:EffectEngine {val adjustable:Boolean;fun strength(value:Int);fun strength():Int}
interface EffectsFactory {
    fun equalizer(session:Int,changed:(Boolean)->Unit):EqualizerEngine
    fun bass(session:Int,changed:(Boolean)->Unit):BassEngine
}

/** Owns only this player's positive session. No audio, queue, network or history commands. */
class EffectsController(private val factory:EffectsFactory,private val changed:()->Unit={}) {
    var state=EffectsView();private set
    private var settings=EffectsSettings();private var context=EffectsContext();private var epoch=0L
    private var eq:EqualizerEngine?=null;private var bass:BassEngine?=null
    private var expectedLevels:List<Int> = emptyList();private var expectedBass:Int?=null
    fun sync(value:EffectsSettings,target:EffectsContext,routeName:String="",force:Boolean=false) {
        value.checked()
        val recreate=target!=context||force
        if(recreate){release();context=target;state=EffectsView(target.sessionId,routeName)}
        val altered=value!=settings||recreate;settings=value
        state=state.copy(sessionId=target.sessionId,routeName=routeName)
        if(!value.enabled){release();state=state.copy(equalizer=state.equalizer.copy(phase=EffectPhase.OFF,message="音效已关闭，保留已保存的调节"),bass=state.bass.copy(phase=EffectPhase.OFF,message="未改变原声"));return}
        if(target.sessionId<=0){release();state=state.copy(equalizer=waiting(value.equalizer),bass=waiting(value.bass));return}
        if(altered)apply()else refresh()
    }
    private fun waiting(requested:Boolean)=EffectDetail(if(requested)EffectPhase.WAITING_SESSION else EffectPhase.OFF,message=if(requested)"设置已保存，实际播放建立会话后再应用；不会自动播放" else "未启用")
    private fun callback(ticket:Long):(Boolean)->Unit={regained->if(ticket==epoch){if(regained)apply()else refresh();changed()}}
    private fun apply() {
        if(settings.equalizer) {
            try {
                val engine=eq ?: factory.equalizer(context.sessionId,callback(epoch)).also{eq=it}
                if(!engine.control)state=state.copy(equalizer=state.equalizer.copy(phase=EffectPhase.CONTROL_LOST,message="系统音效控制权暂不可用，保存偏好；可稍后重试"))
                else {
                    val frequencies=engine.frequencies;val range=engine.range;val presets=engine.presets
                    require(frequencies.isNotEmpty()&&frequencies.size<=32&&frequencies.all{it in 1..100_000_000}&&frequencies.distinct().size==frequencies.size)
                    require(range.first>=-2400&&range.last<=2400&&range.first<range.last&&0 in range)
                    val preset=presets.firstOrNull{it.name==settings.preset}
                    var notice=""
                    if(preset!=null){engine.preset(preset.id);check(engine.currentPreset==preset.id)}
                    else {
                        if(settings.preset!=null)notice="本设备没有保存的预设，已回退平直；偏好仍保留"
                        else if(settings.bands.isNotEmpty()&&settings.bands.map{it.frequencyMilliHz}.toSet()!=frequencies.toSet())notice="频段已变化，仅应用相同频率的调节，其他频段归零"
                        frequencies.forEachIndexed {index,freq->engine.level(index,(if(settings.preset!=null)0 else settings.bands.firstOrNull{it.frequencyMilliHz==freq}?.levelMilliBel ?: 0).coerceIn(range))}
                    }
                    expectedLevels=engine.levels();require(expectedLevels.size==frequencies.size&&expectedLevels.all{it in range})
                    // Check the custom setting itself, not just the value after a rejected write.
                    if(preset==null)require(expectedLevels==frequencies.map{(if(settings.preset!=null)0 else settings.bands.firstOrNull{b->b.frequencyMilliHz==it}?.levelMilliBel ?: 0).coerceIn(range)})
                    engine.enable(true);check(engine.enabled&&engine.control)
                    state=state.copy(equalizer=EffectDetail(EffectPhase.APPLIED,notice,frequencies.zip(expectedLevels){f,l->EffectBand(f,l)},range.first,range.last,presets,preset?.name))
                }
            }catch(e:Exception){stopEq();state=state.copy(equalizer=failed(e))}
        }else {stopEq();state=state.copy(equalizer=state.equalizer.copy(phase=EffectPhase.OFF,message="均衡器未启用"))}
        if(settings.bass) {
            try {
                val engine=bass ?: factory.bass(context.sessionId,callback(epoch)).also{bass=it}
                if(!engine.control)state=state.copy(bass=state.bass.copy(phase=EffectPhase.CONTROL_LOST,message="低音增强控制权暂不可用"))
                else {
                    engine.strength(settings.bassStrength);expectedBass=engine.strength();require(expectedBass!! in 0..1000)
                    engine.enable(true);check(engine.enabled&&engine.control)
                    state=state.copy(bass=EffectDetail(EffectPhase.APPLIED,if(engine.adjustable)"显示系统实际回读强度" else "设备仅支持固定强度，显示实际回读值",actualBassStrength=expectedBass,adjustableBass=engine.adjustable))
                }
            }catch(e:Exception){stopBass();state=state.copy(bass=failed(e))}
        }else {stopBass();state=state.copy(bass=state.bass.copy(phase=EffectPhase.OFF,message="低音增强未启用"))}
    }
    fun refresh() {
        eq?.let {engine->try {
            state=state.copy(equalizer=state.equalizer.copy(phase=when{!engine.control->EffectPhase.CONTROL_LOST;!engine.enabled||engine.levels()!=expectedLevels->EffectPhase.FAILED;else->EffectPhase.APPLIED}))
            if(state.equalizer.phase==EffectPhase.FAILED){stopEq();state=state.copy(equalizer=state.equalizer.copy(message="系统音效状态或参数已改变，已释放本机处理；请重试"))}
        }catch(e:Exception){stopEq();state=state.copy(equalizer=failed(e))}}
        bass?.let {engine->try {
            state=state.copy(bass=state.bass.copy(phase=when{!engine.control->EffectPhase.CONTROL_LOST;!engine.enabled||engine.strength()!=expectedBass->EffectPhase.FAILED;else->EffectPhase.APPLIED}))
            if(state.bass.phase==EffectPhase.FAILED){stopBass();state=state.copy(bass=state.bass.copy(message="系统低音增强状态已改变，请重试"))}
        }catch(e:Exception){stopBass();state=state.copy(bass=failed(e))}}
    }
    private fun failed(error:Exception)=EffectDetail(if(error is UnsupportedOperationException)EffectPhase.UNSUPPORTED else EffectPhase.FAILED,
        message=if(error is UnsupportedOperationException)"此音频会话或设备未提供该音效，收听继续" else "音效未成功应用，已释放本机处理；收听继续，偏好保留")
    private fun stop(engine:EffectEngine?){if(engine!=null){runCatching{if(engine.control)engine.enable(false)};runCatching{engine.close()}}}
    private fun stopEq(){val old=eq;eq=null;stop(old);expectedLevels=emptyList()}
    private fun stopBass(){val old=bass;bass=null;stop(old);expectedBass=null}
    private fun release(){epoch++;stopEq();stopBass()}
    fun close(){release();state=EffectsView()}
}
