LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE := libtermux-bootstrap
LOCAL_SRC_FILES := termux-bootstrap-zip.S termux-bootstrap.c
LOCAL_LDFLAGS := -Wl,--gc-sections -Wl,-z,relro -Wl,-z,now -Wl,-z,noexecstack
include $(BUILD_SHARED_LIBRARY)
