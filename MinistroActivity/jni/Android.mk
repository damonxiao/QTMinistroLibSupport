LOCAL_PATH:= $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE    := libministro
LOCAL_CFLAGS    := -Werror
LOCAL_SRC_FILES := chmode.c extract.cpp

LOCAL_LDLIBS    := -lm -llog
LOCAL_C_INCLUDES  += system/core/include/cutils
LOCAL_SHARED_LIBRARIES := libcutils

include $(BUILD_SHARED_LIBRARY)
