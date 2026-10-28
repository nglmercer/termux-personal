#define _GNU_SOURCE
#include "managed-fork.h"

#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/syscall.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <unistd.h>

static int signal_handle(int handle, int signal_number) {
#if defined(__NR_pidfd_send_signal)
    return (int)syscall(__NR_pidfd_send_signal, handle, signal_number, NULL, 0);
#else
    (void)handle; (void)signal_number; errno = ENOSYS; return -1;
#endif
}

static void require(int condition, const char *message) {
    if (!condition) { perror(message); exit(1); }
}

int main(void) {
    int handle = -1;
    errno = 0;
    require(termux_managed_fork_pidfd(NULL) == -1 && errno == EINVAL, "reject missing handle");
    pid_t child = termux_managed_fork_pidfd(&handle);
    if (child < 0 && (errno == ENOSYS || errno == EPERM || errno == EACCES)) {
        fprintf(stderr, "SKIP: atomic pidfd creation unavailable (errno %d)\n", errno);
        return 77;
    }
    require(child >= 0, "clone3");
    if (child == 0) {
        if (handle != -1) _exit(71);
        for (;;) pause();
    }
    require(handle >= 0, "pidfd returned");
    int flags = fcntl(handle, F_GETFD);
    require(flags >= 0 && (flags & FD_CLOEXEC), "pidfd close-on-exec");
    if (signal_handle(handle, SIGTERM) < 0) {
        int signal_error = errno;
        /* This is a disposable, unreaped test child, not an API fallback. */
        kill(child, SIGKILL); waitpid(child, NULL, 0); close(handle);
        if (signal_error == ENOSYS || signal_error == EPERM || signal_error == EACCES) {
            fprintf(stderr, "SKIP: pidfd signalling unavailable (errno %d)\n", signal_error);
            return 77;
        }
        errno = signal_error; require(0, "signal pidfd");
    }
    int status;
    require(waitpid(child, &status, 0) == child && WIFSIGNALED(status) && WTERMSIG(status) == SIGTERM, "wait original child");
    errno = 0;
    require(signal_handle(handle, SIGTERM) == -1 && errno == ESRCH, "stale handle rejection");
    require(close(handle) == 0, "close pidfd");

    int pipe_fds[2];
    require(pipe2(pipe_fds, O_CLOEXEC) == 0, "create exec pipe");
    int errors[2];
    require(pipe2(errors, O_CLOEXEC) == 0, "create exec error pipe");
    char temporary[] = "/tmp/termux-managed-exec-XXXXXX";
    require(mkdtemp(temporary) != NULL, "create executable directory");
    int directory = open(temporary, O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    int source = open("/bin/echo", O_RDONLY | O_CLOEXEC);
    int copy = openat(directory, "program", O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC, 0700);
    require(directory >= 0 && source >= 0 && copy >= 0, "open executable copy");
    char buffer[16384];
    for (;;) {
        ssize_t n = read(source, buffer, sizeof(buffer));
        if (n < 0 && errno == EINTR) continue;
        require(n >= 0, "read executable copy");
        if (n == 0) break;
        for (ssize_t written = 0; written < n;) {
            ssize_t amount = write(copy, buffer + written, (size_t)(n - written));
            if (amount < 0 && errno == EINTR) continue;
            require(amount > 0, "write executable copy"); written += amount;
        }
    }
    require(close(source) == 0 && close(copy) == 0, "close executable copy");
    int executable = openat(directory, "program", O_PATH | O_CLOEXEC | O_NOFOLLOW);
    require(executable >= 0, "open executable descriptor");
    require(unlinkat(directory, "program", 0) == 0 && symlinkat("/bin/false", directory, "program") == 0,
        "replace executable pathname");
    handle = -1;
    child = termux_managed_fork_pidfd(&handle);
    require(child >= 0, "exec clone3");
    if (child == 0) {
        close(pipe_fds[0]);
        close(errors[0]);
        if (dup2(pipe_fds[1], STDOUT_FILENO) < 0) _exit(72);
        close(pipe_fds[1]);
        char *const argv[] = {"/bin/echo", "exact argument", NULL};
        char *const environment[] = {NULL};
        termux_managed_exec_child(executable, directory, errors[1], argv, environment);
    }
    close(executable);
    close(errors[1]);
    int exec_error;
    require(read(errors[0], &exec_error, sizeof(exec_error)) == 0, "exec succeeded without error");
    close(errors[0]);
    close(pipe_fds[1]);
    char output[32];
    size_t count = 0;
    while (count < sizeof(output)) {
        ssize_t n = read(pipe_fds[0], output + count, sizeof(output) - count);
        if (n < 0 && errno == EINTR) continue;
        require(n >= 0, "read child output");
        if (n == 0) break;
        count += (size_t)n;
    }
    require(count == 15 && memcmp(output, "exact argument\n", 15) == 0, "child exact argv output");
    close(pipe_fds[0]);
    require(waitpid(child, &status, 0) == child && WIFEXITED(status) && WEXITSTATUS(status) == 0, "exec child exit");
    require(close(handle) == 0, "close exec pidfd");
    require(unlinkat(directory, "program", 0) == 0 && close(directory) == 0 && rmdir(temporary) == 0,
        "remove executable test directory");

    require(pipe2(errors, O_CLOEXEC) == 0, "create failure pipe");
    child = termux_managed_fork_pidfd(&handle);
    require(child >= 0, "failure clone3");
    if (child == 0) {
        close(errors[0]);
        char *const argv[] = {"missing", NULL};
        char *const environment[] = {NULL};
        termux_managed_exec_child(-1, -1, errors[1], argv, environment);
    }
    close(errors[1]);
    require(read(errors[0], &exec_error, sizeof(exec_error)) == sizeof(exec_error) && exec_error == EBADF,
        "child reports cwd failure");
    close(errors[0]);
    require(waitpid(child, &status, 0) == child && WIFEXITED(status) && WEXITSTATUS(status) == 127, "failure exit");
    require(close(handle) == 0, "close failure pidfd");

    for (int i = 0; i < 100; i++) {
        handle = -1;
        child = termux_managed_fork_pidfd(&handle);
        require(child >= 0, "immediate-exit clone3");
        if (child == 0) _exit(23);
        require(waitpid(child, &status, 0) == child && WIFEXITED(status) && WEXITSTATUS(status) == 23, "immediate exit");
        errno = 0;
        require(signal_handle(handle, SIGTERM) == -1 && errno == ESRCH, "immediate exit handle rejection");
        require(close(handle) == 0, "close immediate pidfd");
    }
    puts("atomic pidfd creation, owned signal, and exited-child rejection passed");
    return 0;
}
