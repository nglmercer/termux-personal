#ifndef TERMUX_MANAGED_FORK_H
#define TERMUX_MANAGED_FORK_H

#include <sys/types.h>

/*
 * clone3(CLONE_PIDFD) atomically creates a child and its identity-safe handle.
 * Parent: returns child PID and stores a CLOEXEC pidfd. Child: returns 0 and stores -1.
 * Failure: returns -1, stores -1, and sets errno; there is no PID-based fallback.
 *
 * The child of a multithreaded process must perform only async-signal-safe native
 * operations and immediately exec or _exit. It must never return to JNI or Java.
 */
pid_t termux_managed_fork_pidfd(int *pidfd);

/* Pipes are [read, write]. All descriptors are >= 3 and close-on-exec. */
struct termux_managed_pipes { int input[2], output[2], error[2], status[2]; };
int termux_managed_pipes_open(struct termux_managed_pipes *pipes);
void termux_managed_pipes_close(struct termux_managed_pipes *pipes);

/* Child-only: wire three descriptors, fchdir and exec a pinned executable.
 * All supplied descriptors must be >= 3 and CLOEXEC; error_fd must be distinct
 * from the other descriptors. On failure report errno and _exit(127). */
void termux_managed_exec_child(int executable_fd, int cwd_fd, int error_fd,
                               int stdin_fd, int stdout_fd, int stderr_fd,
                               char *const argv[], char *const envp[]);

/* Parent-only, worker thread: 0 = pipe closed without a reported error (exec
 * outcome still unconfirmed), 1 = child reported errno, -1 = protocol/I/O
 * error or timeout (errno set). Requires a pipe with one reader and no other
 * inherited writers. Does not close the caller-owned descriptor. */
int termux_managed_read_exec_status(int fd, int timeout_ms, int *child_error);

#endif
