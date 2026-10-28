LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE := libtermux-bootstrap
LOCAL_SRC_FILES := termux-bootstrap-zip.S termux-bootstrap.c
include $(BUILD_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := libtermux-document-path
LOCAL_SRC_FILES := document-path.c
include $(BUILD_SHARED_LIBRARY)

# Internal managed-job primitives; no enabled job API yet.
include $(CLEAR_VARS)
LOCAL_MODULE := libtermux-managed-job
LOCAL_SRC_FILES := managed-fork.c managed-path.c managed-jni.c
include $(BUILD_SHARED_LIBRARY)
