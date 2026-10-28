#include <jni.h>

/* Library ABI marker only; this does not probe kernel or seccomp support. */
JNIEXPORT jint JNICALL
Java_com_termux_app_control_jobs_ManagedNative_nativeAbi(JNIEnv *env, jclass type) {
    (void)env; (void)type;
    return 1;
}
