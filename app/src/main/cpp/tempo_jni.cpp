#include <jni.h>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <memory>
#include <stdexcept>
#include <vector>
#include "SoundTouch.h"

namespace {
constexpr int batchFrames = 2048;
struct Tempo {
    soundtouch::SoundTouch engine;
    int channels;
    std::vector<float> samples;
    Tempo(int rate, int count, float speed, float pitch) : channels(count), samples(batchFrames * count) {
        engine.setSampleRate(rate);
        engine.setChannels(count);
        engine.setSetting(SETTING_USE_QUICKSEEK, 0);
        engine.setTempo(speed);
        engine.setPitch(pitch);
    }
};
Tempo &tempo(jlong handle) {
    if (!handle) throw std::runtime_error("closed tempo processor");
    return *reinterpret_cast<Tempo *>(handle);
}
void fail(JNIEnv *env) { env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "音频变速处理失败"); }
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_bililisten_playback_SoundTouchNative_create(JNIEnv *env, jobject, jint rate, jint count, jfloat speed, jfloat pitch) {
    try {
        if (rate < 8000 || rate > 192000 || count < 1 || count > 8 || !std::isfinite(speed) || speed < .1f || speed > 8.f || !std::isfinite(pitch) || pitch < .1f || pitch > 8.f)
            throw std::runtime_error("invalid format");
        return reinterpret_cast<jlong>(new Tempo(rate, count, speed, pitch));
    } catch (...) { fail(env); return 0; }
}

extern "C" JNIEXPORT void JNICALL
Java_app_bililisten_playback_SoundTouchNative_put(JNIEnv *env, jobject, jlong handle, jobject buffer, jint offset, jint frames) {
    try {
        auto &t = tempo(handle);
        auto *bytes = static_cast<uint8_t *>(env->GetDirectBufferAddress(buffer));
        auto capacity = env->GetDirectBufferCapacity(buffer);
        auto size = static_cast<jlong>(frames) * t.channels * 2;
        if (!bytes || frames < 0 || frames > batchFrames || offset < 0 || offset > capacity - size || offset % 2 != 0) throw std::runtime_error("invalid buffer");
        auto *pcm = reinterpret_cast<int16_t *>(bytes + offset);
        for (int n = 0; n < frames * t.channels; ++n) t.samples[n] = pcm[n] / 32768.f;
        t.engine.putSamples(t.samples.data(), frames);
    } catch (...) { fail(env); }
}

extern "C" JNIEXPORT jint JNICALL
Java_app_bililisten_playback_SoundTouchNative_receive(JNIEnv *env, jobject, jlong handle, jobject buffer) {
    try {
        auto &t = tempo(handle);
        auto *pcm = static_cast<int16_t *>(env->GetDirectBufferAddress(buffer));
        auto capacity = env->GetDirectBufferCapacity(buffer);
        if (!pcm || capacity < batchFrames * t.channels * 2) throw std::runtime_error("invalid output");
        auto frames = t.engine.receiveSamples(t.samples.data(), batchFrames);
        for (unsigned n = 0; n < frames * t.channels; ++n) {
            float value = t.samples[n];
            if (!std::isfinite(value)) throw std::runtime_error("invalid sample");
            pcm[n] = static_cast<int16_t>(std::lround(std::clamp(value * 32768.f, -32768.f, 32767.f)));
        }
        return frames * t.channels * 2;
    } catch (...) { fail(env); return 0; }
}

extern "C" JNIEXPORT void JNICALL
Java_app_bililisten_playback_SoundTouchNative_finish(JNIEnv *env, jobject, jlong handle) {
    try { tempo(handle).engine.flush(); } catch (...) { fail(env); }
}

extern "C" JNIEXPORT jint JNICALL
Java_app_bililisten_playback_SoundTouchNative_available(JNIEnv *env, jobject, jlong handle) {
    try { return tempo(handle).engine.numSamples(); } catch (...) { fail(env); return 0; }
}

extern "C" JNIEXPORT void JNICALL
Java_app_bililisten_playback_SoundTouchNative_release(JNIEnv *, jobject, jlong handle) {
    delete reinterpret_cast<Tempo *>(handle);
}
