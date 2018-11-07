LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES:= \
	showYUV.cpp
	
LOCAL_SHARED_LIBRARIES := \
	libcutils \
	libutils \
	libbinder \
    libui \
    libgui \
	libandroid_runtime \
	libstagefright_foundation
	
LOCAL_MODULE:= libshowYUV

LOCAL_MODULE_TAGS := tests

include $(BUILD_SHARED_LIBRARY)
