package app.bililisten.playback

import android.media.audiofx.AudioEffect
import android.media.audiofx.Equalizer
import android.media.audiofx.BassBoost
import android.os.Handler
import android.os.Looper
import app.bililisten.shared.*

/** Never attaches to global session zero. Released with the playback service. */
class AndroidEffectsFactory:EffectsFactory {
    private abstract class Handle(protected val effect:AudioEffect,changed:(Boolean)->Unit):EffectEngine {
        private var closed=false
        private val handler=Handler(Looper.getMainLooper())
        init {
            try {
            effect.setControlStatusListener {_,control->handler.post{if(!closed)changed(control)}}
            effect.setEnableStatusListener {_,_->handler.post{if(!closed)changed(false)}}
            }catch(error:Exception){closed=true;effect.release();throw error}
        }
        override val control get()=effect.hasControl()
        override val enabled get()=effect.enabled
        override fun enable(value:Boolean){check(effect.setEnabled(value)==AudioEffect.SUCCESS)}
        override fun close(){if(closed)return;closed=true;try{effect.setControlStatusListener(null);effect.setEnableStatusListener(null)}finally{effect.release()}}
    }
    override fun equalizer(session:Int,changed:(Boolean)->Unit):EqualizerEngine {
        require(session>0)
        val eq=Equalizer(0,session)
        return object:Handle(eq,changed),EqualizerEngine {
            override val frequencies get()=(0 until eq.numberOfBands.toInt()).map{eq.getCenterFreq(it.toShort())}
            override val range get()=eq.bandLevelRange.let{it[0].toInt()..it[1].toInt()}
            override val presets get()=(0 until eq.numberOfPresets.toInt()).take(100).map{EffectPreset(it,eq.getPresetName(it.toShort()).take(80))}
            override val currentPreset get()=eq.currentPreset.toInt()
            override fun preset(id:Int)=eq.usePreset(id.toShort())
            override fun level(index:Int,value:Int)=eq.setBandLevel(index.toShort(),value.toShort())
            override fun levels()=(0 until eq.numberOfBands.toInt()).map{eq.getBandLevel(it.toShort()).toInt()}
        }
    }
    override fun bass(session:Int,changed:(Boolean)->Unit):BassEngine {
        require(session>0)
        val boost=BassBoost(0,session)
        return object:Handle(boost,changed),BassEngine {
            override val adjustable get()=boost.strengthSupported
            override fun strength(value:Int)=boost.setStrength(value.toShort())
            override fun strength()=boost.roundedStrength.toInt()
        }
    }
}
