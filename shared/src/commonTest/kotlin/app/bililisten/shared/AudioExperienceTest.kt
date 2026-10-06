package app.bililisten.shared

import kotlinx.serialization.json.Json
import kotlin.test.*

class AudioExperienceTest {
    private val high=AudioTrack(30280,"https://media.example/high","audio/mp4","mp4a.40.2",200000,48000,2)
    private val low=high.copy(id=30216,codec="mp4a.40.5",bandwidth=64000)
    private val flac=high.copy(id=30251,codec="flac",sampleRate=96000)
    @Test fun autoDoesNotCapGuestOrOrdinaryAndManualCanChooseLowerAac() {
        assertEquals(high,AudioExperienceRules.choose(listOf(low,high),AudioChoice()){true}.track)
        assertEquals(low,AudioExperienceRules.choose(listOf(low,high),AudioChoice(low.id,low.codec)){true}.track)
    }
    @Test fun unavailablePreferenceFallsBackWithoutClaimingMembershipInsufficient() {
        val result=AudioExperienceRules.choose(listOf(high),AudioChoice(flac.id,flac.codec)){true}
        assertEquals(high,result.track);assertEquals(AudioRestriction.SOURCE_MISSING,result.restriction)
    }
    @Test fun unsupportedLosslessUsesCompatibleAac() {
        val result=AudioExperienceRules.choose(listOf(high,flac),AudioChoice(flac.id,flac.codec)){it!=flac}
        assertEquals(high,result.track);assertEquals(AudioRestriction.DEVICE_UNSUPPORTED,result.restriction)
    }
    @Test fun emptyResponseIsUnknownRatherThanSourceAbsentOrVipMissing() {
        assertEquals(AudioRestriction.UNKNOWN,AudioExperienceRules.choose(emptyList(),AudioChoice()){true}.restriction)
    }
    @Test fun flacSamplingAloneDoesNotProveHiResAndDolbyIsNotAutomaticallyAtmos() {
        assertFalse(AudioExperienceRules.label(flac).contains("Hi-Res"))
        assertTrue(AudioExperienceRules.label(flac.copy(bitDepth=24)).contains("Hi-Res"))
        assertFalse(AudioExperienceRules.label(flac.copy(codec="ec-3")).contains("全景声"))
        assertTrue(AudioExperienceRules.label(flac.copy(codec="ec+3")).contains("全景声"))
    }
    @Test fun publicOptionsCannotContainSignedMediaUrl() {
        val json=Json.encodeToString(AudioExperience(options=AudioExperienceRules.options(listOf(high,flac)){true}))
        assertFalse(json.contains("https"));assertFalse(json.contains("media.example"))
    }
    @Test fun platformNetworkMalformedAndAccountRestrictionsAreDistinct() {
        assertEquals(AudioRestriction.ACCESS_REQUIRED,AudioExperienceRules.failure(PlatformFailure("login",-101)))
        assertEquals(AudioRestriction.ACCESS_REQUIRED,AudioExperienceRules.failure(PlatformFailure("access",-403)))
        assertEquals(AudioRestriction.PLATFORM_LIMITED,AudioExperienceRules.failure(PlatformFailure("challenge",412)))
        assertEquals(AudioRestriction.NETWORK,AudioExperienceRules.failure(PlatformFailure("网络请求失败")))
        assertEquals(AudioRestriction.UNKNOWN,AudioExperienceRules.failure(PlatformFailure("平台响应格式变化")))
    }
    @Test fun pauseFocusTimerRouteOrIdentityChangeDuringResolutionNeverResumes() {
        assertTrue(AudioExperienceRules.safeToResume(true,true,true,true,true))
        assertFalse(AudioExperienceRules.safeToResume(false,true,true,true,true))
        assertFalse(AudioExperienceRules.safeToResume(true,false,true,true,true))
        assertFalse(AudioExperienceRules.safeToResume(true,true,false,true,true))
        assertFalse(AudioExperienceRules.safeToResume(true,true,true,false,true))
        assertFalse(AudioExperienceRules.safeToResume(true,true,true,true,false))
    }
    @Test fun oldSettingsDecodeToAutoAndChoiceSurvivesRoundTrip() {
        val old=Json.decodeFromString<UserSettings>("""{"historyKeepAll":true}""")
        assertTrue(old.historyKeepAll);assertNull(old.audioChoice.id);assertFalse(old.externalOutputOnly)
        val saved=old.copy(audioChoice=AudioChoice(low.id,low.codec),externalOutputOnly=true,preferredOutputType=8)
        assertEquals(saved,Json.decodeFromString<UserSettings>(Json.encodeToString(saved)).checked())
    }
    @Test fun sameTrackIdWithDifferentCodecIsNotAssumedEquivalent() {
        val options=AudioExperienceRules.options(listOf(high,high.copy(codec="ec-3"))){true}
        assertEquals(2,options.size)
    }
    @Test fun invalidChoiceNeverEntersSettings() {
        assertFailsWith<IllegalArgumentException>{UserSettings(audioChoice=AudioChoice(-1,"flac")).checked()}
        assertFailsWith<IllegalArgumentException>{AudioChoice(1,"unknown").checked()}
    }
}
