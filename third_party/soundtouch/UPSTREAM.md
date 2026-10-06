# SoundTouch 2.4.1

Upstream: https://codeberg.org/soundtouch/soundtouch

Exact release source: https://codeberg.org/soundtouch/soundtouch/archive/2.4.1.tar.gz

SHA-256: `35d404e6e8c2ebd12fb4000da6fadd75c99e37eed2126a04721828c11c0377ec`

Copyright Olli Parviainen. LGPL 2.1 or later; full license in COPYING.TXT and APK assets/licenses/soundtouch-LGPL-2.1.txt. The app's About page displays it.

The ordinary source files were extracted unchanged. The upstream tarball's unused Lazarus example symlink to an absent build product was omitted. The complete original tarball is retained with the release's corresponding-source package. SoundTouch is built as **libsoundtouch.so**, dynamically linked from the separate **libbilitempo.so** adapter, with **libc++_shared.so**. No upstream algorithm source is modified. The project's original code uses the root noncommercial source-available LICENSE. That license does not apply to SoundTouch or restrict rights granted by the LGPL.

Build: install Android NDK 28.2.13676358 under the configured SDK, JDK 17+, then run `gradlew :app:buildTempoNative`. Android.mk, Application.mk and tempo_jni.cpp are in app/src/main/cpp. On Windows the task uses relative paths to avoid AGP's ndk-build JSON parser error with CJK paths. NDK outputs are app/build/tempo/lib/{armeabi-v7a,arm64-v8a,x86,x86_64}; all outputs are generated, never downloaded opaque binaries. NDK flexible page sizes are enabled.

The accompanying source ZIP contains the full library, adapter and build files. Rebuild a modified compatible SoundTouch as a shared library and replace lib/<abi>/libsoundtouch.so in a copy of the APK, zipalign and sign with your own test key. No application object relink is required. Reverse engineering for debugging modifications to this LGPL component is permitted. An Android signature change cannot update the existing installation; use a separate test installation or keep/export local data before changing signatures.

Integration keeps interleaved channels together, floating-point internal samples, full overlap search (QUICKSEEK=0), music defaults, and original pitch. It consumes streamed PCM, produces streamed PCM, and saves no audio. Media3 alone owns parameter-drain/flush transitions and media clock scaling. Platform speed processing and the default Sonic chain are disabled, so tempo is applied only once; 1x with normal pitch bypasses processing. Reset/seek releases the old native handle.

References: Bilibili's historical ijkplayer SoundTouch integration is an implementation reference, **not evidence about the current closed-source official app**. https://github.com/bilibili/soundtouch . Music tuning and stereo coherence guidance: https://www.surina.net/soundtouch/README.html . VLC reference: https://github.com/videolan/vlc/blob/master/modules/audio_filter/scaletempo.c . mpv reference: https://github.com/mpv-player/mpv/blob/master/audio/filter/af_scaletempo2.c .
