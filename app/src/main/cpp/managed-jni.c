#define _GNU_SOURCE
#include <jni.h>
#include "managed-fork.h"

/* Library ABI marker only; this does not probe kernel or seccomp support. */
JNIEXPORT jint JNICALL
Java_com_termux_app_control_jobs_ManagedNative_nativeAbi(JNIEnv *env, jclass type) {
    (void)env; (void)type;
    return 1;
}

/* Worker-only probe: a pipe keeps the disposable child alive until signaled. */
JNIEXPORT jint JNICALL
Java_com_termux_app_control_jobs_ManagedNative_nativeProbePidfd(JNIEnv *env, jclass type) {
    (void)env; (void)type;
    return termux_managed_probe_pidfd();
}
