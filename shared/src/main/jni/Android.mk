LOCAL_PATH := $(call my-dir)

# 通用 C 实现，无架构汇编、CPU 特性依赖或系统 libopus 依赖。
include $(LOCAL_PATH)/opus-1.3.1/celt_sources.mk
include $(LOCAL_PATH)/opus-1.3.1/silk_sources.mk
include $(LOCAL_PATH)/opus-1.3.1/opus_sources.mk

include $(CLEAR_VARS)
LOCAL_MODULE := xcertplay_opus
LOCAL_SRC_FILES := opus_encoder_jni.c $(addprefix opus-1.3.1/,$(CELT_SOURCES) $(SILK_SOURCES) $(SILK_SOURCES_FLOAT) $(OPUS_SOURCES) $(OPUS_SOURCES_FLOAT))
LOCAL_C_INCLUDES := $(LOCAL_PATH)/opus-1.3.1/include $(LOCAL_PATH)/opus-1.3.1/celt $(LOCAL_PATH)/opus-1.3.1/silk $(LOCAL_PATH)/opus-1.3.1/silk/float
LOCAL_CFLAGS := -O2 -std=c99 -DOPUS_BUILD -DUSE_ALLOCA -DHAVE_LRINT -DHAVE_LRINTF
LOCAL_LDLIBS := -lm
include $(BUILD_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := xcertplay_i2c
LOCAL_SRC_FILES := linux_i2c_jni.c
include $(BUILD_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := local_hotspot_radio
LOCAL_SRC_FILES := local_hotspot_radio.c
LOCAL_CFLAGS := -Wall -Wextra -Werror
include $(BUILD_SHARED_LIBRARY)

# usbfs helpers (detach kernel driver / set configuration) for wired CarPlay bring-up.
include $(CLEAR_VARS)
LOCAL_MODULE := xcertplay_usbioctl
LOCAL_SRC_FILES := usb_host_ioctl.c
LOCAL_CFLAGS := -Wall -Wextra
include $(BUILD_SHARED_LIBRARY)
