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

/* Optional per-child resource limits, applied in the child between fork and execve only; the app
 * process is never limited. A zero address_space_bytes or cpu_seconds, or a negative nice_level,
 * means "no limit". A limit whose bit is set in `required` must be applied or the launch fails
 * (EINVAL for a bad value, otherwise the errno of the failing setrlimit/setpriority ORed with
 * TERMUX_LIMIT_FAILURE_FLAG); any other
 * limit is best-effort and a failure to apply it is not reported by this call.
 *
 * RLIMIT_AS bounds virtual address space, not resident memory, and breaks runtimes that reserve
 * large ranges (JVM, V8). RLIMIT_CPU counts CPU seconds, not wall time or a percentage: the child
 * gets SIGXCPU at the soft limit and SIGKILL a few seconds later. nice_level only lowers priority
 * (0..19): an unprivileged process cannot raise it. RLIMIT_RSS and RLIMIT_NPROC are deliberately
 * absent: the first is not enforced by Linux, the second is per-UID and would hit every other
 * Termux process. An existing hard limit that is already tighter is kept, which counts as applied. */
#define TERMUX_LIMIT_ADDRESS_SPACE 1u
#define TERMUX_LIMIT_CPU_TIME 2u
#define TERMUX_LIMIT_NICE 4u
#define TERMUX_LIMIT_ALL (TERMUX_LIMIT_ADDRESS_SPACE | TERMUX_LIMIT_CPU_TIME | TERMUX_LIMIT_NICE)
/* Set in the errno of a failed launch when a required limit, not the exec, was what failed. */
#define TERMUX_LIMIT_FAILURE_FLAG 0x10000
struct termux_owned_limits {
    unsigned long long address_space_bytes, cpu_seconds;
    int nice_level;
    unsigned int required;
};

/* Parent, worker thread. The child runs in its own process group and receives only the three
 * standard streams; every other descriptor listed in /proc/self/fd (or, without /proc, every
 * slot below a bounded soft RLIMIT_NOFILE) is closed on exec, and the child refuses otherwise.
 * On success returns 0 and the caller owns the pid (must reap exactly once) and three pipe ends.
 * Returns -1 with errno set and no child or descriptors left when fork, setup or exec fails;
 * an exec failure inside the child is reported as the child's errno after reaping it. */
int termux_owned_launch(const char *path, const char *cwd, char *const argv[], char *const envp[],
                        struct termux_owned_child *child);

/* Same as termux_owned_launch with optional limits (NULL means none). */
int termux_owned_launch_limited(const char *path, const char *cwd, char *const argv[], char *const envp[],
                                const struct termux_owned_limits *limits, struct termux_owned_child *child);

/* Blocks until the child has exited without reaping it. 0 or -1 with errno. */
int termux_owned_wait_exit(pid_t pid);

/* Reaps an exited child exactly once. Exactly one of exited or signaled is set. */
struct termux_owned_result { int exited, exit_code, signaled, signal_number; };
int termux_owned_reap(pid_t pid, struct termux_owned_result *result);

/* TERM, KILL or INT to the child's process group. Only valid before the child is reaped. */
int termux_owned_signal(pid_t pid, int signal_number);

#endif
