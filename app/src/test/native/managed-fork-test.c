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
#include <sys/resource.h>
#include <sys/wait.h>
#include <unistd.h>

static void require(int condition, const char *message) {
    if (!condition) { perror(message); exit(1); }
}

int main(void) {
    struct termux_managed_child invalid_child = { .pid = 42, .pidfd = 42, .stdin_write = 42,
        .stdout_read = 42, .stderr_read = 42, .status_read = 42 };
    errno = 0;
    require(termux_managed_launch(-1, -1, NULL, NULL, &invalid_child) == -1 && errno == EINVAL,
        "reject invalid launch");
    require(invalid_child.pid == -1 && invalid_child.pidfd == -1 && invalid_child.stdin_write == -1 &&
        invalid_child.stdout_read == -1 && invalid_child.stderr_read == -1 && invalid_child.status_read == -1,
        "invalid launch clears ownership outputs");
    termux_managed_child_close(&invalid_child);
    int directory_fd = open("/tmp", O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    int regular_fd = open("/bin/echo", O_PATH | O_CLOEXEC);
    require(directory_fd >= 3 && regular_fd >= 3, "open descriptor validation inputs");
    char *const invalid_argv[] = {"echo", NULL};
    char *const invalid_env[] = {NULL};
    errno = 0;
    require(termux_managed_launch(directory_fd, regular_fd, invalid_argv, invalid_env, &invalid_child) == -1 && errno == EINVAL,
        "reject swapped descriptor types before launch");
    require(invalid_child.pid == -1 && invalid_child.pidfd == -1, "type rejection creates no child");
    close(directory_fd); close(regular_fd);
    struct termux_managed_pipes managed_pipes;
    require(termux_managed_pipes_open(NULL) == -1 && errno == EINVAL, "reject missing pipe storage");
    require(termux_managed_pipes_open(&managed_pipes) == 0, "open managed pipes");
    int *all_pipes[] = {managed_pipes.input, managed_pipes.output, managed_pipes.error, managed_pipes.status};
    for (size_t i = 0; i < 4; i++) for (size_t j = 0; j < 2; j++)
        require(all_pipes[i][j] >= 3 && (fcntl(all_pipes[i][j], F_GETFD) & FD_CLOEXEC), "protected pipe descriptor");
    termux_managed_pipes_close(&managed_pipes);
    for (size_t i = 0; i < 4; i++) for (size_t j = 0; j < 2; j++)
        require(all_pipes[i][j] == -1, "closed pipe descriptor invalidated");
    termux_managed_pipes_close(&managed_pipes);
    pid_t limited = fork();
    require(limited >= 0, "fork pipe quota test");
    if (limited == 0) {
        struct rlimit limit = { .rlim_cur = 7, .rlim_max = 7 };
        if (setrlimit(RLIMIT_NOFILE, &limit) < 0) _exit(1);
        if (termux_managed_pipes_open(&managed_pipes) != -1 || errno != EMFILE) _exit(2);
        for (int fd = 3; fd < 7; fd++) if (fcntl(fd, F_GETFD) != -1 || errno != EBADF) _exit(3);
        _exit(0);
    }
    int limited_status;
    require(waitpid(limited, &limited_status, 0) == limited && WIFEXITED(limited_status) && WEXITSTATUS(limited_status) == 0,
        "pipe allocation failure closes partial descriptors");
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
    errno = 0;
    require(termux_managed_signal(handle, SIGSTOP) == -1 && errno == EINVAL, "reject unsupported signal");
    if (termux_managed_signal(handle, SIGTERM) < 0) {
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
    require(termux_managed_signal(handle, SIGTERM) == -1 && errno == ESRCH, "stale handle rejection");
    require(close(handle) == 0, "close pidfd");
    for (int i = 0; i < 20; i++) require(termux_managed_probe_pidfd() == 0, "repeat disposable pidfd probe");

    int pipe_fds[2];
    require(pipe2(pipe_fds, O_CLOEXEC) == 0, "create exec pipe");
    int errors[2];
    require(pipe2(errors, O_CLOEXEC) == 0, "create exec error pipe");
    int input = open("/dev/null", O_RDONLY | O_CLOEXEC);
    int error_output = open("/dev/null", O_WRONLY | O_CLOEXEC);
    require(input >= 3 && error_output >= 3, "open child streams");
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
        char *const argv[] = {"/bin/echo", "exact argument", NULL};
        char *const environment[] = {NULL};
        termux_managed_exec_child(executable, directory, errors[1], input, pipe_fds[1], error_output, argv, environment);
    }
    close(input); close(error_output);
    close(executable);
    close(errors[1]);
    int exec_error;
    require(termux_managed_read_exec_status(errors[0], 2000, &exec_error) == 0, "exec pipe closed without error");
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
        termux_managed_exec_child(-1, -1, errors[1], -1, -1, -1, argv, environment);
    }
    close(errors[1]);
    require(termux_managed_read_exec_status(errors[0], 2000, &exec_error) == 1 && exec_error == EINVAL,
        "child reports invalid descriptors");
    close(errors[0]);
    require(waitpid(child, &status, 0) == child && WIFEXITED(status) && WEXITSTATUS(status) == 127, "failure exit");
    require(close(handle) == 0, "close failure pidfd");

    int out_pipe[2], err_pipe[2];
    require(pipe2(out_pipe, O_CLOEXEC) == 0 && pipe2(err_pipe, O_CLOEXEC) == 0 && pipe2(errors, O_CLOEXEC) == 0,
        "create separate stream pipes");
    int shell = open("/bin/sh", O_PATH | O_CLOEXEC);
    int cwd = open("/tmp", O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    input = open("/dev/null", O_RDONLY | O_CLOEXEC);
    require(shell >= 3 && cwd >= 3 && input >= 3, "open stream test descriptors");
    child = termux_managed_fork_pidfd(&handle);
    require(child >= 0, "stream clone3");
    if (child == 0) {
        close(out_pipe[0]); close(err_pipe[0]); close(errors[0]);
        char *const argv[] = {"sh", "-c", "printf out; printf err >&2", NULL};
        char *const environment[] = {NULL};
        termux_managed_exec_child(shell, cwd, errors[1], input, out_pipe[1], err_pipe[1], argv, environment);
    }
    close(shell); close(cwd); close(input); close(out_pipe[1]); close(err_pipe[1]); close(errors[1]);
    require(termux_managed_read_exec_status(errors[0], 2000, &exec_error) == 0, "stream exec status");
    close(errors[0]);
    char stream[4] = {0};
    require(read(out_pipe[0], stream, 3) == 3 && memcmp(stream, "out", 3) == 0, "separate stdout");
    require(read(err_pipe[0], stream, 3) == 3 && memcmp(stream, "err", 3) == 0, "separate stderr");
    close(out_pipe[0]); close(err_pipe[0]);
    require(waitpid(child, &status, 0) == child && WIFEXITED(status) && WEXITSTATUS(status) == 0, "stream child exit");
    close(handle);

    shell = open("/bin/sh", O_PATH | O_CLOEXEC);
    cwd = open("/tmp", O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    require(shell >= 3 && cwd >= 3, "open launcher descriptors");
    int unrelated = fcntl(cwd, F_DUPFD, 100);
    require(unrelated >= 100 && !(fcntl(unrelated, F_GETFD) & FD_CLOEXEC), "create inheritable descriptor");
    char script[160];
    require(snprintf(script, sizeof(script), "test ! -e /proc/self/fd/%d || exit 42; read x; printf '%%s' \"$x\"; printf error >&2; exit 9", unrelated) > 0,
        "format inheritance test");
    char *const launch_argv[] = {"sh", "-c", script, NULL};
    char *const launch_env[] = {NULL};
    struct termux_managed_child managed_child;
    require(termux_managed_launch(shell, cwd, launch_argv, launch_env, &managed_child) == 0, "managed launch");
    close(shell); close(cwd); close(unrelated);
    require(termux_managed_read_exec_status(managed_child.status_read, 2000, &exec_error) == 0, "managed exec status");
    close(managed_child.status_read);
    require(write(managed_child.stdin_write, "hello\n", 6) == 6, "managed stdin");
    close(managed_child.stdin_write);
    char launch_output[6] = {0};
    require(read(managed_child.stdout_read, launch_output, 5) == 5 && memcmp(launch_output, "hello", 5) == 0, "managed stdout");
    require(read(managed_child.stderr_read, launch_output, 5) == 5 && memcmp(launch_output, "error", 5) == 0, "managed stderr");
    close(managed_child.stdout_read); close(managed_child.stderr_read);
    struct termux_managed_result result;
    require(termux_managed_wait(managed_child.pid, &result) == 0 && result.exited && result.exit_code == 9 && !result.signaled,
        "managed exit status");
    errno = 0;
    require(termux_managed_wait(managed_child.pid, &result) == -1 && errno == ECHILD, "reject duplicate reap");
    close(managed_child.pidfd);

    shell = open("/bin/sh", O_PATH | O_CLOEXEC);
    cwd = open("/tmp", O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    char *const wait_argv[] = {"sh", "-c", "read x", NULL};
    require(termux_managed_launch(shell, cwd, wait_argv, launch_env, &managed_child) == 0, "launch cancellable child");
    close(shell); close(cwd);
    require(termux_managed_read_exec_status(managed_child.status_read, 2000, &exec_error) == 0, "cancellable exec status");
    require(termux_managed_signal(managed_child.pidfd, SIGTERM) == 0, "signal launched child handle");
    require(termux_managed_wait(managed_child.pid, &result) == 0 && result.signaled && result.signal_number == SIGTERM && !result.exited,
        "observe signalled child");
    errno = 0;
    require(termux_managed_signal(managed_child.pidfd, SIGKILL) == -1 && errno == ESRCH, "reject post-exit signal");
    int old_pidfd = managed_child.pidfd;
    termux_managed_child_close(&managed_child);
    require(managed_child.pidfd == -1 && managed_child.stdin_write == -1 && managed_child.stdout_read == -1 &&
        managed_child.stderr_read == -1 && managed_child.status_read == -1, "child descriptors invalidated");
    errno = 0;
    require(fcntl(old_pidfd, F_GETFD) == -1 && errno == EBADF, "pidfd closed");
    termux_managed_child_close(&managed_child);

    require(pipe2(errors, O_CLOEXEC) == 0, "create timeout pipe");
    errno = 0;
    require(termux_managed_read_exec_status(errors[0], 20, &exec_error) == -1 && errno == ETIMEDOUT, "bounded status wait");
    require(write(errors[1], "x", 1) == 1 && close(errors[1]) == 0, "write partial error");
    errno = 0;
    require(termux_managed_read_exec_status(errors[0], 2000, &exec_error) == -1 && errno == EPROTO, "reject partial error");
    close(errors[0]);

    for (int i = 0; i < 100; i++) {
        handle = -1;
        child = termux_managed_fork_pidfd(&handle);
        require(child >= 0, "immediate-exit clone3");
        if (child == 0) _exit(23);
        require(waitpid(child, &status, 0) == child && WIFEXITED(status) && WEXITSTATUS(status) == 23, "immediate exit");
        errno = 0;
        require(termux_managed_signal(handle, SIGTERM) == -1 && errno == ESRCH, "immediate exit handle rejection");
        require(close(handle) == 0, "close immediate pidfd");
    }
    puts("atomic pidfd creation, owned signal, and exited-child rejection passed");
    return 0;
}
