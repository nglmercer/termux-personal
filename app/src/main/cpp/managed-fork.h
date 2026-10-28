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

#endif
