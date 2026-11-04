/* Compile for each Android ABI to verify constants used by the freestanding shim. */
#define _GNU_SOURCE
#include <asm/stat.h>
#include <asm/unistd.h>
#include <fcntl.h>
#include <stddef.h>
#include <unistd.h>

enum { kernel_o_path = O_PATH, kernel_o_nonblock = O_NONBLOCK, kernel_o_cloexec = O_CLOEXEC };
#undef O_PATH
#undef O_NONBLOCK
#undef AT_FDCWD
#undef X_OK
#include "../../main/cpp/glibc-exec.c"

_Static_assert(O_PATH == kernel_o_path, "O_PATH mismatch");
_Static_assert(O_NONBLOCK == kernel_o_nonblock, "O_NONBLOCK mismatch");
_Static_assert(O_RDONLY_CLOEXEC == kernel_o_cloexec, "O_CLOEXEC mismatch");
_Static_assert(NR_openat == __NR_openat, "openat syscall mismatch");
_Static_assert(NR_read == __NR_read, "read syscall mismatch");
_Static_assert(NR_close == __NR_close, "close syscall mismatch");
_Static_assert(NR_execve == __NR_execve, "execve syscall mismatch");
_Static_assert(NR_faccessat == __NR_faccessat, "faccessat syscall mismatch");
#if defined(__arm__) || defined(__i386__)
_Static_assert(NR_fstat == __NR_fstat64, "fstat64 syscall mismatch");
_Static_assert(KERNEL_STAT_MODE_OFFSET == offsetof(struct stat64, st_mode), "stat64 mode mismatch");
#else
_Static_assert(NR_fstat == __NR_fstat, "fstat syscall mismatch");
_Static_assert(KERNEL_STAT_MODE_OFFSET == offsetof(struct stat, st_mode), "stat mode mismatch");
#endif
