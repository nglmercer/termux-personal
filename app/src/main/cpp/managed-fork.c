#define _GNU_SOURCE
#include "managed-fork.h"

#include <errno.h>
#include <linux/sched.h>
#include <signal.h>
#include <stdint.h>
#include <string.h>
#include <sys/syscall.h>
#include <unistd.h>

pid_t termux_managed_fork_pidfd(int *pidfd) {
    if (pidfd == NULL) { errno = EINVAL; return -1; }
    *pidfd = -1;
#if defined(__NR_clone3) && defined(CLONE_PIDFD)
    struct clone_args args;
    memset(&args, 0, sizeof(args));
    args.flags = CLONE_PIDFD;
    args.pidfd = (uint64_t)(uintptr_t)pidfd;
    args.exit_signal = SIGCHLD;
    pid_t child = (pid_t)syscall(__NR_clone3, &args, sizeof(args));
    if (child == 0) *pidfd = -1;
    return child;
#else
    errno = ENOSYS;
    return -1;
#endif
}
