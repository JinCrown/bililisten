package app.bililisten.shared

import kotlinx.serialization.json.Json
import kotlin.test.*

class EffectsTest {
    private open class Engine:EffectEngine {
        var owned=true;var on=false;var closed=0;var rejectEnable=false
        override val control get()=owned
        override val enabled get()=on
        override fun enable(value:Boolean){if(!rejectEnable)on=value}
        override fun close(){closed++}
    }
    private class Eq:Engine(),EqualizerEngine {
        override var frequencies=listOf(60_000,1_000_000,12_000_000)
        override var range=-1500..1500
        override val presets=listOf(EffectPreset(7,"Native"))
        override var currentPreset=-1
        var values=mutableListOf(0,0,0);var rejectLevel=false;var rejectPreset=false
        override fun preset(id:Int){if(!rejectPreset){currentPreset=id;values=mutableListOf(100,200,-100)}}
        override fun level(index:Int,value:Int){if(!rejectLevel)values[index]=value}
        override fun levels()=values.toList()
    }
    private class Bass:Engine(),BassEngine {
        override var adjustable=true;var value=0
        override fun strength(value:Int){this.value=if(adjustable)value/100*100 else 400}
        override fun strength()=value
    }
    private class Factory:EffectsFactory {
        val eqs=mutableListOf<Eq>();val boosts=mutableListOf<Bass>();val callbacks=mutableListOf<(Boolean)->Unit>()
        var failEq=false;var configure:(Eq)->Unit={}
        override fun equalizer(session:Int,changed:(Boolean)->Unit):EqualizerEngine {
            assertTrue(session>0);if(failEq)throw UnsupportedOperationException()
            callbacks+=changed;return Eq().also{configure(it);eqs+=it}
        }
        override fun bass(session:Int,changed:(Boolean)->Unit):BassEngine {assertTrue(session>0);callbacks+=changed;return Bass().also{boosts+=it}}
    }
    private val enabled=EffectsSettings(enabled=true)
    private val target=EffectsContext(42,2,2)
    @Test fun oldSettingsDefaultOffAndRoundTripPreservesPrivacyAndSpeed() {
        assertFalse(Json.decodeFromString<UserSettings>("{}").effects.enabled)
        val s=UserSettings(historyEnabled=false).copy(effects=enabled.copy(bass=true,bassStrength=650,bands=listOf(EffectBand(60_000,-300))))
        assertEquals(s,Json.decodeFromString<UserSettings>(Json.encodeToString(s)))
        assertFalse(s.effects.copy(enabled=false).enabled);assertFalse(s.historyEnabled)
    }
    @Test fun disabledOrZeroSessionNeverCreatesGlobalEffects() {
        val f=Factory();val c=EffectsController(f)
        c.sync(EffectsSettings(),target);assertTrue(f.eqs.isEmpty())
        c.sync(enabled,EffectsContext());assertEquals(EffectPhase.WAITING_SESSION,c.state.equalizer.phase);assertTrue(f.eqs.isEmpty())
    }
    @Test fun nativePresetUsesDeviceIdentityAndReadsActualBands() {
        val f=Factory();val c=EffectsController(f);c.sync(enabled.copy(preset="Native"),target)
        assertEquals(7,f.eqs.single().currentPreset);assertEquals(listOf(100,200,-100),c.state.equalizer.bands.map{it.levelMilliBel})
        assertEquals("Native",c.state.equalizer.actualPreset);assertEquals(EffectPhase.APPLIED,c.state.equalizer.phase)
    }
    @Test fun customUsesFrequencyIdentityClampsRangeAndClearsPreset() {
        val f=Factory();val c=EffectsController(f)
        c.sync(enabled.copy(bands=listOf(EffectBand(12_000_000,2400),EffectBand(60_000,-2400))),target)
        assertEquals(listOf(-1500,0,1500),f.eqs.single().levels())
        val edit=enabled.copy(preset="Native").custom(c.state.equalizer.bands,1_000_000,-100)
        assertNull(edit.preset);assertEquals(listOf(-1500,-100,1500),edit.bands.map{it.levelMilliBel})
    }
    @Test fun missingPresetFallsBackFlatWithoutLosingSavedPreference() {
        val c=EffectsController(Factory());val pref=enabled.copy(preset="Missing")
        c.sync(pref,target);assertTrue(c.state.equalizer.bands.all{it.levelMilliBel==0});assertNull(c.state.equalizer.actualPreset)
        assertTrue(c.state.equalizer.message.contains("回退"));assertEquals("Missing",pref.preset)
    }
    @Test fun rejectedWriteOrPresetCannotReportApplied() {
        for(preset in listOf(false,true)) {
            val f=Factory().apply{configure={if(preset)it.rejectPreset=true else it.rejectLevel=true}}
            val c=EffectsController(f);c.sync(enabled.copy(preset=if(preset)"Native" else null,bands=listOf(EffectBand(60_000,100))),target)
            assertEquals(EffectPhase.FAILED,c.state.equalizer.phase);assertEquals(1,f.eqs.single().closed);assertFalse(f.eqs.single().on)
        }
    }
    @Test fun rejectedEnableCannotReportApplied() {
        val f=Factory().apply{configure={it.rejectEnable=true}};val c=EffectsController(f);c.sync(enabled,target)
        assertEquals(EffectPhase.FAILED,c.state.equalizer.phase);assertEquals(1,f.eqs.single().closed)
    }
    @Test fun unsupportedEqualizerDoesNotBlockRoundedBassReadback() {
        val f=Factory().apply{failEq=true};val c=EffectsController(f);c.sync(enabled.copy(bass=true,bassStrength=650),target)
        assertEquals(EffectPhase.UNSUPPORTED,c.state.equalizer.phase);assertEquals(EffectPhase.APPLIED,c.state.bass.phase);assertEquals(600,c.state.bass.actualBassStrength)
    }
    @Test fun fixedStrengthIsReadBackWithoutClaimingAdjustable() {
        val f=Factory();val c=EffectsController(f);val pref=enabled.copy(bass=true)
        c.sync(pref,target);f.boosts.single().adjustable=false;c.sync(pref.copy(bassStrength=900),target)
        assertEquals(400,c.state.bass.actualBassStrength);assertFalse(c.state.bass.adjustableBass)
    }
    @Test fun sessionAndRouteRebuildReleaseOldHandlesAndIgnoreOldCallbacks() {
        val f=Factory();val c=EffectsController(f);c.sync(enabled,target);val stale=f.callbacks.first()
        c.sync(enabled,target.copy(sessionId=43));assertEquals(1,f.eqs.first().closed)
        c.sync(enabled,target.copy(sessionId=43,routeId=9,routeType=8));assertEquals(3,f.eqs.size)
        val before=c.state;stale(true);assertEquals(before,c.state);assertEquals(3,f.eqs.size)
        c.close();assertTrue(f.eqs.all{it.closed==1});assertFalse(f.eqs.last().on)
    }
    @Test fun sameSessionRefreshDoesNotRecreateOrRetryFailureWithoutUserAction() {
        val f=Factory();val c=EffectsController(f);c.sync(enabled,target)
        repeat(20){c.sync(enabled,target)};assertEquals(1,f.eqs.size)
        f.eqs.last().on=false;c.sync(enabled,target);assertEquals(EffectPhase.FAILED,c.state.equalizer.phase)
        repeat(20){c.sync(enabled,target)};assertEquals(1,f.eqs.size)
        c.sync(enabled,target,force=true);assertEquals(2,f.eqs.size);assertEquals(EffectPhase.APPLIED,c.state.equalizer.phase)
    }
    @Test fun controlLossAndRegainAppliesLatestPreferences() {
        val f=Factory();val c=EffectsController(f);c.sync(enabled,target);val eq=f.eqs.single()
        eq.owned=false;f.callbacks.first()(false);assertEquals(EffectPhase.CONTROL_LOST,c.state.equalizer.phase)
        c.sync(enabled.copy(bands=listOf(EffectBand(60_000,-200))),target);eq.owned=true;f.callbacks.first()(true)
        assertEquals(-200,eq.levels().first());assertEquals(EffectPhase.APPLIED,c.state.equalizer.phase)
    }
    @Test fun externalParameterDriftReleasesProcessingAndRetainsPreference() {
        val f=Factory();val c=EffectsController(f);c.sync(enabled.copy(bass=true),target)
        f.eqs.single().values[0]=900;f.boosts.single().value=900;c.refresh()
        assertEquals(EffectPhase.FAILED,c.state.equalizer.phase);assertEquals(EffectPhase.FAILED,c.state.bass.phase)
        assertEquals(1,f.eqs.single().closed);assertEquals(1,f.boosts.single().closed)
    }
    @Test fun malformedPreferencesAndNativeCapsDoNotCreateFalseActiveState() {
        assertFailsWith<IllegalArgumentException>{EffectsSettings(bassStrength=1001).checked()}
        assertFailsWith<IllegalArgumentException>{EffectsSettings(bands=listOf(EffectBand(1,0),EffectBand(1,0))).checked()}
        val f=Factory().apply{configure={it.frequencies=emptyList()}};val c=EffectsController(f);c.sync(enabled,target)
        assertEquals(EffectPhase.FAILED,c.state.equalizer.phase);assertEquals(1,f.eqs.single().closed)
    }
    @Test fun masterResetReleasesAndLateCallbackDoesNotReenable() {
        val f=Factory();val c=EffectsController(f);c.sync(enabled.copy(bass=true),target);val late=f.callbacks.toList()
        c.sync(EffectsSettings(),target);late.forEach{it(true)}
        assertEquals(EffectPhase.OFF,c.state.equalizer.phase);assertEquals(1,f.eqs.size);assertFalse(f.eqs.single().on);assertFalse(f.boosts.single().on)
    }
}
