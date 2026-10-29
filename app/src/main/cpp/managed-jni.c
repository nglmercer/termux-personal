#define _GNU_SOURCE
#include <jni.h>
#include "managed-fork.h"
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <unistd.h>

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
    int gate[2];
    if (pipe2(gate, O_CLOEXEC) < 0) return -errno;
    int fd;
    pid_t child = termux_managed_fork_pidfd(&fd);
    if (child < 0) { int error = errno; close(gate[0]); close(gate[1]); return -error; }
    if (child == 0) {
        close(gate[1]);
        char byte;
        while (read(gate[0], &byte, 1) < 0 && errno == EINTR) { }
        _exit(0);
    }
    close(gate[0]);
    int error = 0;
    /* SIGKILL cannot be blocked or ignored by the inherited process state. */
    if (termux_managed_signal(fd, SIGKILL) < 0) error = errno;
    close(gate[1]);
    int status;
    pid_t waited;
    do { waited = waitpid(child, &status, 0); } while (waited < 0 && errno == EINTR);
    if (waited < 0 && error == 0) error = errno;
    if (waited > 0 && (!WIFSIGNALED(status) || WTERMSIG(status) != SIGKILL) && error == 0) error = EIO;
    close(fd);
    return error ? -error : 0;
}
