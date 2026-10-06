LOCAL_PATH := $(call my-dir)
ST_PATH := ../../../../third_party/soundtouch
include $(CLEAR_VARS)
LOCAL_MODULE := soundtouch
LOCAL_C_INCLUDES := $(LOCAL_PATH)/$(ST_PATH)/include
LOCAL_CPPFLAGS := -O3 -DSOUNDTOUCH_FLOAT_SAMPLES=1
LOCAL_SRC_FILES := $(addprefix $(ST_PATH)/source/SoundTouch/,AAFilter.cpp FIFOSampleBuffer.cpp FIRFilter.cpp InterpolateCubic.cpp InterpolateLinear.cpp InterpolateShannon.cpp RateTransposer.cpp SoundTouch.cpp TDStretch.cpp cpu_detect_x86.cpp sse_optimized.cpp mmx_optimized.cpp)
include $(BUILD_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := bilitempo
LOCAL_C_INCLUDES := $(LOCAL_PATH)/$(ST_PATH)/include
LOCAL_CPPFLAGS := -O3 -DSOUNDTOUCH_FLOAT_SAMPLES=1 -fvisibility=hidden
LOCAL_SRC_FILES := tempo_jni.cpp
LOCAL_SHARED_LIBRARIES := soundtouch
include $(BUILD_SHARED_LIBRARY)
