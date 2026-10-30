LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE := libtermux-bootstrap
LOCAL_SRC_FILES := termux-bootstrap-zip.S termux-bootstrap.c
include $(BUILD_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := libtermux-document-path
LOCAL_SRC_FILES := document-path.c
include $(BUILD_SHARED_LIBRARY)

# Managed-job primitives: owned child launch, wait and signal for app-owned jobs.
include $(CLEAR_VARS)
LOCAL_MODULE := libtermux-managed-job
LOCAL_SRC_FILES := managed-fork.c managed-child.c managed-path.c managed-jni.c
include $(BUILD_SHARED_LIBRARY)

# Automatic glibc compatibility, bionic side; preloaded before termux-exec by the shell profile.
# The glibc side is built by the buildGlibcPreload Gradle task because ndk-build cannot target glibc.
include $(CLEAR_VARS)
LOCAL_MODULE := libtermux-glibc-exec
LOCAL_SRC_FILES := glibc-exec.c
LOCAL_CFLAGS := -DTERMUX_GLIBC_SIDE=0 -fno-builtin
LOCAL_LDLIBS := -ldl
include $(BUILD_SHARED_LIBRARY)
