#define _GNU_SOURCE
#include <jni.h>
#include "managed-fork.h"
#include <errno.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <unistd.h>

/* Library ABI marker only; this does not probe kernel or seccomp support. */
JNIEXPORT jint JNICALL
Java_com_termux_app_control_jobs_ManagedNative_nativeAbi(JNIEnv *env, jclass type) {
    (void)env; (void)type;
    return 1;
}

/* Worker-only probe: a disposable child exits immediately; never signal a PID. */
JNIEXPORT jint JNICALL
Java_com_termux_app_control_jobs_ManagedNative_nativeProbePidfd(JNIEnv *env, jclass type) {
    (void)env; (void)type;
    int fd;
    pid_t child = termux_managed_fork_pidfd(&fd);
    if (child < 0) return -errno;
    if (child == 0) _exit(0);
    int error = 0;
#if defined(__NR_pidfd_send_signal)
    if (syscall(__NR_pidfd_send_signal, fd, 0, NULL, 0) < 0) error = errno;
#else
    error = ENOSYS;
#endif
    int status;
    pid_t waited;
    do { waited = waitpid(child, &status, 0); } while (waited < 0 && errno == EINTR);
    if (waited < 0 && error == 0) error = errno;
    if (waited > 0 && (!WIFEXITED(status) || WEXITSTATUS(status) != 0) && error == 0) error = EIO;
    close(fd);
    return error ? -error : 0;
}
