#define _GNU_SOURCE
#include "managed-child.h"
#include "managed-fork.h"

#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <string.h>
#include <stdint.h>
#include <sys/resource.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <unistd.h>

/* Bound for the fallback scan when /proc/self/fd cannot be listed. */
#define MAX_INHERIT_SCAN (1 << 17)

static void set_cloexec(int fd) {
    int flags = fcntl(fd, F_GETFD);
    if (flags >= 0 && !(flags & FD_CLOEXEC)) fcntl(fd, F_SETFD, flags | FD_CLOEXEC);
}

/* Child-only, async-signal-safe: raw getdents64 instead of opendir, which may allocate.
 * Returns 0 when every descriptor from 3 up is close-on-exec. */
static int cloexec_inherited(int max_fd) {
    int directory = open("/proc/self/fd", O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (directory >= 0) {
        char buffer[4096];
        for (;;) {
            long n = syscall(SYS_getdents64, directory, buffer, sizeof(buffer));
            if (n < 0 && errno == EINTR) continue;
            if (n < 0) { close(directory); goto scan; }
            if (n == 0) break;
            for (long offset = 0; offset < n;) {
                /* struct linux_dirent64: u64 ino, s64 off, u16 reclen, u8 type, char name[]. */
                unsigned short length;
                memcpy(&length, buffer + offset + 16, sizeof(length));
                const char *name = buffer + offset + 19;
                long fd = 0;
                int digits = name[0] != '\0';
                for (const char *c = name; *c; c++) {
                    if (*c < '0' || *c > '9' || fd > INT32_MAX / 10) { digits = 0; break; }
                    fd = fd * 10 + (*c - '0');
                }
                if (digits && fd >= 3 && fd != directory) set_cloexec((int)fd);
                if (length == 0) break;
                offset += length;
            }
        }
        close(directory);
        return 0;
    }
scan:
    if (max_fd < 0) return -1;
    for (int fd = 3; fd < max_fd; fd++) set_cloexec(fd);
    return 0;
}

/* Child-only and async-signal-safe: never returns. */
static void exec_child(const char *path, const char *cwd, char *const argv[], char *const envp[],
                       const struct termux_managed_pipes *pipes, int max_fd) {
    int failure = 0;
    if (setpgid(0, 0) < 0 || dup2(pipes->input[0], STDIN_FILENO) < 0 || dup2(pipes->output[1], STDOUT_FILENO) < 0 ||
        dup2(pipes->error[1], STDERR_FILENO) < 0 || chdir(cwd) < 0) failure = errno;
    if (!failure) {
        sigset_t mask;
        struct sigaction action;
        memset(&action, 0, sizeof(action));
        action.sa_handler = SIG_DFL;
        sigemptyset(&action.sa_mask);
        /* Runtime handlers and masks must not leak into the job; KILL and STOP fail harmlessly. */
        for (int signal_number = 1; signal_number < NSIG; signal_number++) sigaction(signal_number, &action, NULL);
        if (sigemptyset(&mask) < 0 || sigprocmask(SIG_SETMASK, &mask, NULL) < 0) failure = errno;
    }
    if (!failure) {
        /* close_range is unavailable on older kernels, and seccomp may trap it. */
        if (cloexec_inherited(max_fd) < 0) failure = EMFILE;
        else { execve(path, argv, envp); failure = errno; }
    }
    const char *bytes = (const char *)&failure;
    size_t remaining = sizeof(failure);
    while (remaining) {
        ssize_t n = write(pipes->status[1], bytes, remaining);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) break;
        bytes += n; remaining -= (size_t)n;
    }
    _exit(127);
}

int termux_owned_launch(const char *path, const char *cwd, char *const argv[], char *const envp[],
                        struct termux_owned_child *child) {
    if (child == NULL) { errno = EINVAL; return -1; }
    child->pid = -1; child->stdin_write = child->stdout_read = child->stderr_read = -1;
    if (path == NULL || path[0] != '/' || cwd == NULL || cwd[0] != '/' || argv == NULL || argv[0] == NULL || envp == NULL) {
        errno = EINVAL; return -1;
    }
    struct rlimit limit;
    if (getrlimit(RLIMIT_NOFILE, &limit) < 0) return -1;
    /* -1 makes the child refuse to exec if it cannot list descriptors and the limit is too large to scan. */
    int max_fd = limit.rlim_cur == RLIM_INFINITY || limit.rlim_cur > MAX_INHERIT_SCAN ? -1 : (int)limit.rlim_cur;
    struct termux_managed_pipes pipes;
    if (termux_managed_pipes_open(&pipes) < 0) return -1;
    pid_t pid = fork();
    if (pid < 0) { int saved = errno; termux_managed_pipes_close(&pipes); errno = saved; return -1; }
    if (pid == 0) exec_child(path, cwd, argv, envp, &pipes, max_fd);
    close(pipes.input[0]); close(pipes.output[1]); close(pipes.error[1]); close(pipes.status[1]);
    pipes.input[0] = pipes.output[1] = pipes.error[1] = pipes.status[1] = -1;
    int child_error = 0;
    int status = termux_managed_read_exec_status(pipes.status[0], 30000, &child_error);
    int saved = errno;
    close(pipes.status[0]); pipes.status[0] = -1;
    if (status != 0) {
        /* The child exits on its own after reporting; on a status I/O failure it may still run. */
        if (status < 0) kill(pid, SIGKILL);
        struct termux_owned_result ignored;
        termux_owned_reap(pid, &ignored);
        termux_managed_pipes_close(&pipes);
        errno = status > 0 ? child_error : saved;
        return -1;
    }
    child->pid = pid;
    child->stdin_write = pipes.input[1]; child->stdout_read = pipes.output[0]; child->stderr_read = pipes.error[0];
    return 0;
}

int termux_owned_wait_exit(pid_t pid) {
    if (pid <= 0) { errno = EINVAL; return -1; }
    siginfo_t info;
    for (;;) {
        memset(&info, 0, sizeof(info));
        if (waitid(P_PID, (id_t)pid, &info, WEXITED | WNOWAIT) == 0) return 0;
        if (errno != EINTR) return -1;
    }
}

int termux_owned_reap(pid_t pid, struct termux_owned_result *result) {
    if (pid <= 0 || result == NULL) { errno = EINVAL; return -1; }
    memset(result, 0, sizeof(*result));
    siginfo_t info;
    int waited;
    do { memset(&info, 0, sizeof(info)); waited = waitid(P_PID, (id_t)pid, &info, WEXITED); }
    while (waited < 0 && errno == EINTR);
    if (waited < 0) return -1;
    if (info.si_code == CLD_EXITED) { result->exited = 1; result->exit_code = info.si_status; return 0; }
    if (info.si_code == CLD_KILLED || info.si_code == CLD_DUMPED) {
        result->signaled = 1; result->signal_number = info.si_status; return 0;
    }
    errno = EPROTO; return -1;
}

int termux_owned_signal(pid_t pid, int signal_number) {
    if (pid <= 0 || (signal_number != SIGTERM && signal_number != SIGKILL && signal_number != SIGINT)) {
        errno = EINVAL; return -1;
    }
    return kill(-pid, signal_number);
}
