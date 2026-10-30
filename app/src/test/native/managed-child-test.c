#define _GNU_SOURCE
#include "managed-child.h"

#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

static void require(int condition, const char *message) {
    if (!condition) { fprintf(stderr, "%s (errno %d)\n", message, errno); exit(1); }
}

static size_t read_all(int fd, char *buffer, size_t size) {
    size_t used = 0;
    for (;;) {
        ssize_t n = read(fd, buffer + used, size - 1 - used);
        if (n < 0 && errno == EINTR) continue;
        require(n >= 0, "read child output");
        if (n == 0) break;
        used += (size_t)n;
        require(used < size - 1, "child output fits");
    }
    buffer[used] = '\0';
    return used;
}

static void close_child(struct termux_owned_child *child) {
    close(child->stdin_write); close(child->stdout_read); close(child->stderr_read);
}

int main(void) {
    alarm(20); /* a missed group signal would otherwise leave the descendant holding stdout */
    char *const env[] = {"PATH=/usr/bin:/bin", NULL};
    struct termux_owned_child child;
    struct termux_owned_result result;
    char out[256], err[256];

    errno = 0;
    require(termux_owned_launch("relative", "/", (char *const[]){"x", NULL}, env, &child) == -1 && errno == EINVAL,
        "reject a relative executable");
    require(child.pid == -1 && child.stdin_write == -1, "rejection leaves no ownership");

    char *const streams[] = {"sh", "-c", "read line; printf \"out:$line\"; printf err >&2; exit 7", NULL};
    require(termux_owned_launch("/bin/sh", "/", streams, env, &child) == 0, "launch shell");
    require(child.stdin_write >= 3 && child.stdout_read >= 3 && child.stderr_read >= 3, "parent pipe ends");
    require(write(child.stdin_write, "in\n", 3) == 3, "write stdin");
    close(child.stdin_write); child.stdin_write = -1;
    read_all(child.stdout_read, out, sizeof(out));
    read_all(child.stderr_read, err, sizeof(err));
    require(strcmp(out, "out:in") == 0 && strcmp(err, "err") == 0, "separate output streams");
    require(termux_owned_wait_exit(child.pid) == 0, "wait without reaping");
    require(kill(child.pid, 0) == 0, "child remains an unreaped zombie after wait");
    require(termux_owned_signal(child.pid, SIGTERM) == 0, "signal to an unreaped group is harmless");
    require(termux_owned_reap(child.pid, &result) == 0 && result.exited && result.exit_code == 7, "exit code");
    require(termux_owned_reap(child.pid, &result) == -1 && errno == ECHILD, "a child is reaped only once");
    close(child.stdout_read); close(child.stderr_read);

    errno = 0;
    require(termux_owned_launch("/nonexistent/binary", "/", (char *const[]){"x", NULL}, env, &child) == -1 && errno == ENOENT,
        "exec failure reports the child errno");
    require(child.pid == -1 && child.stdout_read == -1, "exec failure leaves no ownership");
    errno = 0;
    require(termux_owned_launch("/bin/sh", "/nonexistent-dir", (char *const[]){"sh", NULL}, env, &child) == -1 && errno == ENOENT,
        "working directory failure reports the child errno");

    int leaked = open("/dev/null", O_RDONLY);
    require(leaked >= 3, "open an inheritable descriptor");
    char script[128];
    snprintf(script, sizeof(script), "[ -e /proc/self/fd/%d ] && exit 1; exit 0", leaked);
    require(termux_owned_launch("/bin/sh", "/", (char *const[]){"sh", "-c", script, NULL}, env, &child) == 0, "launch fd check");
    require(termux_owned_wait_exit(child.pid) == 0 && termux_owned_reap(child.pid, &result) == 0, "reap fd check");
    require(result.exited && result.exit_code == 0, "unrelated descriptors are not inherited");
    close_child(&child); close(leaked);

    require(termux_owned_launch("/bin/sh", "/", (char *const[]){"sh", "-c", "[ \"$(ps -o pgid= -p $$ | tr -d ' ')\" = \"$$\" ]", NULL},
        env, &child) == 0, "launch group check");
    require(termux_owned_wait_exit(child.pid) == 0 && termux_owned_reap(child.pid, &result) == 0, "reap group check");
    require(result.exited && result.exit_code == 0, "child leads its own process group");
    close_child(&child);

    /* A descendant in the group must stop with the leader; it holds stdout open until it does. */
    char *const tree[] = {"sh", "-c", "sleep 30 & sleep 30; exit 0", NULL};
    require(termux_owned_launch("/bin/sh", "/", tree, env, &child) == 0, "launch process tree");
    struct timespec pause = {0, 100 * 1000 * 1000};
    nanosleep(&pause, NULL);
    require(termux_owned_signal(child.pid, SIGTERM) == 0, "signal process group");
    require(termux_owned_wait_exit(child.pid) == 0 && termux_owned_reap(child.pid, &result) == 0, "reap signalled tree");
    require(result.signaled && result.signal_number == SIGTERM, "leader reports the terminating signal");
    read_all(child.stdout_read, out, sizeof(out));
    close_child(&child);

    require(termux_owned_signal(child.pid, SIGHUP) == -1 && errno == EINVAL, "only TERM, KILL and INT are accepted");
    printf("managed child launch, wait and group signal passed\n");
    return 0;
}
