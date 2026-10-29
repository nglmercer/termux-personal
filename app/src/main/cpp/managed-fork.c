#define _GNU_SOURCE
#include "managed-fork.h"

#include <errno.h>
#include <linux/sched.h>
#include <signal.h>
#include <stdint.h>
#include <string.h>
#include <sys/syscall.h>
#include <unistd.h>
#include <fcntl.h>
#include <poll.h>
#include <time.h>
#include <limits.h>
#include <sys/wait.h>
#include <sys/stat.h>

#ifndef CLOSE_RANGE_CLOEXEC
#define CLOSE_RANGE_CLOEXEC (1U << 2)
#endif

void termux_managed_pipes_close(struct termux_managed_pipes *pipes) {
    if (pipes == NULL) return;
    int *pairs[] = {pipes->input, pipes->output, pipes->error, pipes->status};
    for (size_t i = 0; i < 4; i++) for (size_t j = 0; j < 2; j++) {
        if (pairs[i][j] >= 0) close(pairs[i][j]);
        pairs[i][j] = -1;
    }
}

int termux_managed_pipes_open(struct termux_managed_pipes *pipes) {
    if (pipes == NULL) { errno = EINVAL; return -1; }
    int *pairs[] = {pipes->input, pipes->output, pipes->error, pipes->status};
    for (size_t i = 0; i < 4; i++) pairs[i][0] = pairs[i][1] = -1;
    for (size_t i = 0; i < 4; i++) {
        if (pipe2(pairs[i], O_CLOEXEC) < 0) goto fail;
        for (size_t j = 0; j < 2; j++) {
            if (pairs[i][j] < 3) {
                int replacement = fcntl(pairs[i][j], F_DUPFD_CLOEXEC, 3);
                if (replacement < 0) goto fail;
                close(pairs[i][j]); pairs[i][j] = replacement;
            }
        }
    }
    return 0;
fail: {
        int saved = errno;
        termux_managed_pipes_close(pipes);
        errno = saved;
        return -1;
    }
}

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

int termux_managed_launch(int executable_fd, int cwd_fd, char *const argv[], char *const envp[],
                          struct termux_managed_child *child) {
    if (child == NULL) { errno = EINVAL; return -1; }
    child->pid = -1;
    child->pidfd = child->stdin_write = child->stdout_read = child->stderr_read = child->status_read = -1;
    if (executable_fd < 3 || cwd_fd < 3 || argv == NULL || argv[0] == NULL || envp == NULL) {
        errno = EINVAL; return -1;
    }
    struct stat executable_stat, cwd_stat;
    if (fstat(executable_fd, &executable_stat) < 0 || fstat(cwd_fd, &cwd_stat) < 0) return -1;
    if (!S_ISREG(executable_stat.st_mode) || !S_ISDIR(cwd_stat.st_mode)) { errno = EINVAL; return -1; }
    struct termux_managed_pipes pipes;
    if (termux_managed_pipes_open(&pipes) < 0) return -1;
    int pidfd;
    pid_t pid = termux_managed_fork_pidfd(&pidfd);
    if (pid < 0) {
        int saved = errno; termux_managed_pipes_close(&pipes); errno = saved; return -1;
    }
    if (pid == 0) {
        close(pipes.input[1]); close(pipes.output[0]); close(pipes.error[0]); close(pipes.status[0]);
        termux_managed_exec_child(executable_fd, cwd_fd, pipes.status[1], pipes.input[0], pipes.output[1], pipes.error[1], argv, envp);
    }
    close(pipes.input[0]); close(pipes.output[1]); close(pipes.error[1]); close(pipes.status[1]);
    child->pid = pid; child->pidfd = pidfd;
    child->stdin_write = pipes.input[1]; child->stdout_read = pipes.output[0];
    child->stderr_read = pipes.error[0]; child->status_read = pipes.status[0];
    return 0;
}

void termux_managed_child_close(struct termux_managed_child *child) {
    if (child == NULL) return;
    int *fds[] = {&child->pidfd, &child->stdin_write, &child->stdout_read, &child->stderr_read, &child->status_read};
    for (size_t i = 0; i < sizeof(fds) / sizeof(fds[0]); i++) {
        if (*fds[i] >= 0) close(*fds[i]);
        *fds[i] = -1;
    }
}

int termux_managed_signal(int pidfd, int signal_number) {
    if (pidfd < 0 || (signal_number != SIGTERM && signal_number != SIGKILL && signal_number != SIGINT)) {
        errno = EINVAL; return -1;
    }
#if defined(__NR_pidfd_send_signal)
    return (int)syscall(__NR_pidfd_send_signal, pidfd, signal_number, NULL, 0);
#else
    errno = ENOSYS; return -1;
#endif
}

int termux_managed_probe_pidfd(void) {
#if defined(__NR_waitid)
    /* Check pidfd wait support before creating a child that may need reaping. */
    siginfo_t preflight;
    memset(&preflight, 0, sizeof(preflight));
    if (syscall(__NR_waitid, 3 /* P_PIDFD */, INT_MAX, &preflight, WEXITED | WNOHANG, NULL) == 0 || errno != EBADF)
        return -(errno ? errno : EIO);
#else
    return -ENOSYS;
#endif
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
    if (termux_managed_signal(fd, SIGKILL) < 0) error = errno;
    close(gate[1]);
    struct termux_managed_result result;
    if (termux_managed_wait(fd, &result) < 0) {
        /* Do not wait on a PID that another component may have reaped and the
         * kernel may have reused. Report uncertainty if pidfd wait fails. */
        if (error == 0) error = errno;
    } else if ((!result.signaled || result.signal_number != SIGKILL) && error == 0) error = EIO;
    close(fd);
    return error ? -error : 0;
}

int termux_managed_wait(int pidfd, struct termux_managed_result *result) {
    if (pidfd < 0 || result == NULL) { errno = EINVAL; return -1; }
    memset(result, 0, sizeof(*result));
#if defined(__NR_waitid)
    siginfo_t info;
    memset(&info, 0, sizeof(info));
    int waited;
    do { waited = (int)syscall(__NR_waitid, 3 /* P_PIDFD */, pidfd, &info, WEXITED, NULL); }
    while (waited < 0 && errno == EINTR);
    if (waited < 0) return -1;
    if (info.si_code == CLD_EXITED) { result->exited = 1; result->exit_code = info.si_status; return 0; }
    if (info.si_code == CLD_KILLED || info.si_code == CLD_DUMPED) {
        result->signaled = 1; result->signal_number = info.si_status; return 0;
    }
    errno = EPROTO; return -1;
#else
    errno = ENOSYS; return -1;
#endif
}

void termux_managed_exec_child(int executable_fd, int cwd_fd, int error_fd,
                               int stdin_fd, int stdout_fd, int stderr_fd,
                               char *const argv[], char *const envp[]) {
    int failure;
    if (executable_fd < 3 || cwd_fd < 3 || error_fd < 3 || stdin_fd < 3 || stdout_fd < 3 || stderr_fd < 3 ||
        error_fd == executable_fd || error_fd == cwd_fd || error_fd == stdin_fd || error_fd == stdout_fd || error_fd == stderr_fd)
        failure = EINVAL;
    else if (dup2(stdin_fd, STDIN_FILENO) < 0 || dup2(stdout_fd, STDOUT_FILENO) < 0 ||
             dup2(stderr_fd, STDERR_FILENO) < 0 || fchdir(cwd_fd) < 0) failure = errno;
    else {
        sigset_t mask;
        struct sigaction action;
        memset(&action, 0, sizeof(action));
        action.sa_handler = SIG_DFL;
        if (sigemptyset(&mask) < 0 || sigprocmask(SIG_SETMASK, &mask, NULL) < 0 ||
            sigemptyset(&action.sa_mask) < 0 || sigaction(SIGTERM, &action, NULL) < 0 ||
            sigaction(SIGINT, &action, NULL) < 0 || sigaction(SIGPIPE, &action, NULL) < 0) {
            failure = errno;
        } else {
#if defined(__NR_close_range)
        if (syscall(__NR_close_range, 3U, UINT_MAX, CLOSE_RANGE_CLOEXEC) < 0) failure = errno;
        else {
#if defined(__NR_execveat)
            syscall(__NR_execveat, executable_fd, "", argv, envp, AT_EMPTY_PATH);
            failure = errno;
#else
            failure = ENOSYS;
#endif
        }
#else
        failure = ENOSYS;
#endif
        }
    }
    const char *bytes = (const char *)&failure;
    size_t remaining = sizeof(failure);
    while (remaining) {
        ssize_t n = write(error_fd, bytes, remaining);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) break;
        bytes += n; remaining -= (size_t)n;
    }
    _exit(127);
}

int termux_managed_read_exec_status(int fd, int timeout_ms, int *child_error) {
    if (fd < 0 || child_error == NULL || timeout_ms < 1 || timeout_ms > 30000) { errno = EINVAL; return -1; }
    *child_error = 0;
    struct timespec now;
    if (clock_gettime(CLOCK_MONOTONIC, &now) < 0) return -1;
    int64_t deadline = (int64_t)now.tv_sec * 1000 + now.tv_nsec / 1000000 + timeout_ms;
    size_t received = 0;
    while (received < sizeof(*child_error)) {
        if (clock_gettime(CLOCK_MONOTONIC, &now) < 0) return -1;
        int64_t remaining = deadline - ((int64_t)now.tv_sec * 1000 + now.tv_nsec / 1000000);
        if (remaining <= 0) { errno = ETIMEDOUT; return -1; }
        struct pollfd item = { .fd = fd, .events = POLLIN };
        int ready = poll(&item, 1, (int)remaining);
        if (ready < 0 && errno == EINTR) continue;
        if (ready < 0) return -1;
        if (ready == 0) { errno = ETIMEDOUT; return -1; }
        if (item.revents & (POLLERR | POLLNVAL)) { errno = EIO; return -1; }
        ssize_t n = read(fd, (char *)child_error + received, sizeof(*child_error) - received);
        if (n < 0 && errno == EINTR) continue;
        if (n < 0) return -1;
        if (n == 0) {
            if (received) { errno = EPROTO; return -1; }
            return 0;
        }
        received += (size_t)n;
    }
    if (*child_error <= 0) { errno = EPROTO; return -1; }
    return 1;
}
