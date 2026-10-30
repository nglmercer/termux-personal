#define _GNU_SOURCE
#include <jni.h>
#include "managed-fork.h"

/* Library ABI marker only; this does not probe kernel or seccomp support. */
JNIEXPORT jint JNICALL
Java_com_termux_app_control_jobs_ManagedNative_nativeAbi(JNIEnv *env, jclass type) {
    (void)env; (void)type;
    return 2;
}

/* Worker-only probe: a pipe keeps the disposable child alive until signaled. */
JNIEXPORT jint JNICALL
Java_com_termux_app_control_jobs_ManagedNative_nativeProbePidfd(JNIEnv *env, jclass type) {
    (void)env; (void)type;
    return termux_managed_probe_pidfd();
}

#include <errno.h>
#include <stdlib.h>
#include <string.h>
#include "managed-child.h"

#define MAX_LAUNCH_STRINGS 4096

static void free_strings(char **strings, jsize count) {
    if (strings == NULL) return;
    for (jsize i = 0; i < count; i++) free(strings[i]);
    free(strings);
}

/* Copies a Java String[] into a NULL-terminated C array; returns NULL on any invalid element. */
static char **copy_strings(JNIEnv *env, jobjectArray array, jsize *count) {
    *count = array == NULL ? -1 : (*env)->GetArrayLength(env, array);
    if (*count < 0 || *count > MAX_LAUNCH_STRINGS) return NULL;
    char **strings = calloc((size_t)*count + 1, sizeof(char *));
    if (strings == NULL) return NULL;
    for (jsize i = 0; i < *count; i++) {
        jstring value = (jstring)(*env)->GetObjectArrayElement(env, array, i);
        if (value == NULL) { free_strings(strings, i); return NULL; }
        const char *chars = (*env)->GetStringUTFChars(env, value, NULL);
        if (chars == NULL) { (*env)->DeleteLocalRef(env, value); free_strings(strings, i); return NULL; }
        strings[i] = strdup(chars);
        (*env)->ReleaseStringUTFChars(env, value, chars);
        (*env)->DeleteLocalRef(env, value);
        if (strings[i] == NULL) { free_strings(strings, i); return NULL; }
    }
    return strings;
}

/* Worker-only. out receives {pid, stdin, stdout, stderr}; returns 0 or a negative errno. */
JNIEXPORT jint JNICALL
Java_com_termux_app_control_jobs_ManagedNative_nativeLaunch(JNIEnv *env, jclass type, jstring path, jstring cwd,
                                                             jobjectArray argv, jobjectArray envp, jintArray out) {
    (void)type;
    if (path == NULL || cwd == NULL || out == NULL || (*env)->GetArrayLength(env, out) != 4) return -EINVAL;
    jsize argc, envc;
    char **c_argv = copy_strings(env, argv, &argc);
    char **c_envp = copy_strings(env, envp, &envc);
    const char *c_path = (*env)->GetStringUTFChars(env, path, NULL);
    const char *c_cwd = (*env)->GetStringUTFChars(env, cwd, NULL);
    int result = -EINVAL;
    if (c_argv != NULL && argc > 0 && c_envp != NULL && c_path != NULL && c_cwd != NULL) {
        struct termux_owned_child child;
        if (termux_owned_launch(c_path, c_cwd, c_argv, c_envp, &child) == 0) {
            jint values[4] = { child.pid, child.stdin_write, child.stdout_read, child.stderr_read };
            (*env)->SetIntArrayRegion(env, out, 0, 4, values);
            result = 0;
        } else result = errno > 0 ? -errno : -EIO;
    }
    if (c_path != NULL) (*env)->ReleaseStringUTFChars(env, path, c_path);
    if (c_cwd != NULL) (*env)->ReleaseStringUTFChars(env, cwd, c_cwd);
    free_strings(c_argv, argc > 0 ? argc : 0);
    free_strings(c_envp, envc > 0 ? envc : 0);
    return result;
}

/* Worker-only; blocks until the child exits without reaping it. */
JNIEXPORT jint JNICALL
Java_com_termux_app_control_jobs_ManagedNative_nativeWaitExit(JNIEnv *env, jclass type, jint pid) {
    (void)env; (void)type;
    return termux_owned_wait_exit(pid) == 0 ? 0 : -errno;
}

/* out receives {exited, exitCode, signaled, signal}; returns 0 or a negative errno. */
JNIEXPORT jint JNICALL
Java_com_termux_app_control_jobs_ManagedNative_nativeReap(JNIEnv *env, jclass type, jint pid, jintArray out) {
    (void)type;
    if (out == NULL || (*env)->GetArrayLength(env, out) != 4) return -EINVAL;
    struct termux_owned_result result;
    if (termux_owned_reap(pid, &result) < 0) return -errno;
    jint values[4] = { result.exited, result.exit_code, result.signaled, result.signal_number };
    (*env)->SetIntArrayRegion(env, out, 0, 4, values);
    return 0;
}

/* The Java owner calls this only before it has reaped the child. */
JNIEXPORT jint JNICALL
Java_com_termux_app_control_jobs_ManagedNative_nativeSignal(JNIEnv *env, jclass type, jint pid, jint signal_number) {
    (void)env; (void)type;
    return termux_owned_signal(pid, signal_number) == 0 ? 0 : -errno;
}
