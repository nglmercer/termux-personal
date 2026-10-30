#ifndef TERMUX_MANAGED_CHILD_H
#define TERMUX_MANAGED_CHILD_H

#include <sys/types.h>

/*
 * Owned children for kernels without pidfd support (Android devices still ship 4.x kernels).
 *
 * Identity safety rests on reaping: a PID cannot be reused while its process is an unreaped
 * zombie. The owner waits with WNOWAIT, marks the child exited under its own lock, and only
 * then reaps. Signals are sent only under that lock and only before the mark, so they always
 * reach this child or its process group. This requires that nothing else in the process reaps
 * the child: no waitpid(-1) caller and no SIGCHLD set to SIG_IGN.
 *
 * Uses only fork, setpgid, dup2, chdir, fcntl, sigaction, sigprocmask, execve, waitid and kill,
 * which the Android application seccomp policy permits on every supported API level.
 */
struct termux_owned_child { pid_t pid; int stdin_write, stdout_read, stderr_read; };

/* Parent, worker thread. The child runs in its own process group and receives only the three
 * standard streams; every other descriptor listed in /proc/self/fd (or, without /proc, every
 * slot below a bounded soft RLIMIT_NOFILE) is closed on exec, and the child refuses otherwise.
 * On success returns 0 and the caller owns the pid (must reap exactly once) and three pipe ends.
 * Returns -1 with errno set and no child or descriptors left when fork, setup or exec fails;
 * an exec failure inside the child is reported as the child's errno after reaping it. */
int termux_owned_launch(const char *path, const char *cwd, char *const argv[], char *const envp[],
                        struct termux_owned_child *child);

/* Blocks until the child has exited without reaping it. 0 or -1 with errno. */
int termux_owned_wait_exit(pid_t pid);

/* Reaps an exited child exactly once. Exactly one of exited or signaled is set. */
struct termux_owned_result { int exited, exit_code, signaled, signal_number; };
int termux_owned_reap(pid_t pid, struct termux_owned_result *result);

/* TERM, KILL or INT to the child's process group. Only valid before the child is reaped. */
int termux_owned_signal(pid_t pid, int signal_number);

#endif
