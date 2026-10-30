/*
 * Automatic glibc compatibility for Termux.
 *
 * Prebuilt Linux programs are usually linked against glibc and name /lib/ld-linux-*.so as their ELF
 * interpreter, which does not exist on Android, so the kernel reports "not found". Termux's glibc-runner
 * package provides glibc and its loader under $PREFIX/glibc. This library intercepts execve and, for a
 * glibc program, executes that loader with the program as an argument instead. Nothing else changes:
 * scripts and bionic programs pass straight through.
 *
 * The same source is built twice. TERMUX_GLIBC_SIDE=0 is preloaded into bionic processes before
 * termux-exec and hands every non-glibc exec to the next execve (termux-exec), which keeps its shebang and
 * path handling. TERMUX_GLIBC_SIDE=1 is preloaded by the loader into glibc processes and also:
 *  - redirects a program that re-executes itself through /proc/self/exe (which names the loader when the
 *    loader is run explicitly) back to the real program, so self-relaunching servers work;
 *  - restores the bionic LD_PRELOAD (termux-exec) for bionic children, which glibc processes cannot keep.
 *
 * The glibc side is freestanding (no libc, raw system calls) because it is built without glibc headers;
 * the bionic side only adds dlsym to find the next execve. Any unexpected input passes through unchanged.
 */

#ifndef TERMUX_GLIBC_SIDE
#define TERMUX_GLIBC_SIDE 0
#endif

#ifndef TERMUX_PREFIX
#define TERMUX_PREFIX "/data/data/com.termux/files/usr"
#endif
#define LOADER_DIR TERMUX_PREFIX "/glibc/lib/"
#define MAX_ARGS 2048
#define MAX_ENV 1024
#define MAX_PATH 4096

typedef unsigned long size_t_;
typedef unsigned char u8;

#if defined(__aarch64__)
#define NR_openat 56
#define NR_read 63
#define NR_close 57
#define NR_faccessat 48
#define NR_execve 221
static long sys3(long n, long a, long b, long c) {
    register long x8 __asm__("x8") = n, x0 __asm__("x0") = a, x1 __asm__("x1") = b, x2 __asm__("x2") = c;
    __asm__ volatile("svc #0" : "+r"(x0) : "r"(x8), "r"(x1), "r"(x2) : "memory", "cc");
    return x0;
}
static long sys4(long n, long a, long b, long c, long d) {
    register long x8 __asm__("x8") = n, x0 __asm__("x0") = a, x1 __asm__("x1") = b, x2 __asm__("x2") = c, x3 __asm__("x3") = d;
    __asm__ volatile("svc #0" : "+r"(x0) : "r"(x8), "r"(x1), "r"(x2), "r"(x3) : "memory", "cc");
    return x0;
}
#elif defined(__x86_64__)
#define NR_openat 257
#define NR_read 0
#define NR_close 3
#define NR_faccessat 269
#define NR_execve 59
static long sys3(long n, long a, long b, long c) {
    long r;
    __asm__ volatile("syscall" : "=a"(r) : "a"(n), "D"(a), "S"(b), "d"(c) : "rcx", "r11", "memory");
    return r;
}
static long sys4(long n, long a, long b, long c, long d) {
    long r;
    register long r10 __asm__("r10") = d;
    __asm__ volatile("syscall" : "=a"(r) : "a"(n), "D"(a), "S"(b), "d"(c), "r"(r10) : "rcx", "r11", "memory");
    return r;
}
#elif defined(__arm__)
#define NR_openat 322
#define NR_read 3
#define NR_close 6
#define NR_faccessat 334
#define NR_execve 11
/* r7 is the Thumb frame pointer, so it is saved around the call rather than bound to a variable. */
static long sys3(long n, long a, long b, long c) {
    register long r0 __asm__("r0") = a, r1 __asm__("r1") = b, r2 __asm__("r2") = c;
    __asm__ volatile("push {r7}\n\tmov r7, %1\n\tsvc #0\n\tpop {r7}" : "+r"(r0) : "r"(n), "r"(r1), "r"(r2) : "memory", "cc");
    return r0;
}
static long sys4(long n, long a, long b, long c, long d) {
    register long r0 __asm__("r0") = a, r1 __asm__("r1") = b, r2 __asm__("r2") = c, r3 __asm__("r3") = d;
    __asm__ volatile("push {r7}\n\tmov r7, %1\n\tsvc #0\n\tpop {r7}" : "+r"(r0) : "r"(n), "r"(r1), "r"(r2), "r"(r3) : "memory", "cc");
    return r0;
}
#elif defined(__i386__)
#define NR_openat 295
#define NR_read 3
#define NR_close 6
#define NR_faccessat 307
#define NR_execve 11
static long sys3(long n, long a, long b, long c) {
    long r;
    __asm__ volatile("push %%ebx\n\tmov %2, %%ebx\n\tint $0x80\n\tpop %%ebx" : "=a"(r) : "a"(n), "r"(a), "c"(b), "d"(c) : "memory");
    return r;
}
static long sys4(long n, long a, long b, long c, long d) {
    long r;
    __asm__ volatile("push %%ebx\n\tmov %2, %%ebx\n\tint $0x80\n\tpop %%ebx" : "=a"(r) : "a"(n), "r"(a), "c"(b), "d"(c), "S"(d) : "memory");
    return r;
}
#else
#error "Unsupported architecture"
#endif

#if !TERMUX_GLIBC_SIDE
#include <dlfcn.h>
/* termux-exec's execve, or libc's. Resolved at load: dlsym is not safe in a vfork child. */
typedef int (*execve_fn)(const char *, char *const[], char *const[]);
static execve_fn next_execve;
__attribute__((constructor)) static void resolve_next(void) { next_execve = (execve_fn) dlsym(RTLD_NEXT, "execve"); }
#endif

#define AT_FDCWD (-100)
#define O_RDONLY_CLOEXEC 02000000
#define X_OK 1

/* errno belongs to whichever C library loaded us; both accessors are optional weak symbols. */
extern int *__errno(void) __attribute__((weak));          /* bionic */
extern int *__errno_location(void) __attribute__((weak)); /* glibc */
static long fail(long result) {
    if (result < 0 && result > -4096) {
        int *errno_ptr = __errno ? __errno() : __errno_location ? __errno_location() : 0;
        if (errno_ptr) *errno_ptr = (int) -result;
        return -1;
    }
    return result;
}

/* Executes and returns a negative errno on failure. The bionic side goes through the next execve. */
static long exec_next(const char *path, char *const argv[], char *const envp[]) {
#if !TERMUX_GLIBC_SIDE
    if (next_execve) {
        next_execve(path, argv, envp);
        int *errno_ptr = __errno ? __errno() : __errno_location ? __errno_location() : 0;
        return errno_ptr ? -*errno_ptr : -8 /* ENOEXEC */;
    }
#endif
    return sys3(NR_execve, (long) path, (long) argv, (long) envp);
}

/* The compiler may emit calls to these for struct copies; keep them local to this object. */
__attribute__((visibility("hidden"))) void *memcpy(void *dst, const void *src, size_t_ n) {
    u8 *d = dst; const u8 *s = src; while (n--) *d++ = *s++; return dst;
}
__attribute__((visibility("hidden"))) void *memset(void *dst, int c, size_t_ n) {
    u8 *d = dst; while (n--) *d++ = (u8) c; return dst;
}

static size_t_ len(const char *s) { size_t_ n = 0; while (s[n]) n++; return n; }
static int starts(const char *s, const char *prefix) {
    while (*prefix) if (*s++ != *prefix++) return 0;
    return 1;
}
/* Copies src into dst at offset; returns the new offset, or -1 if it would not fit. */
static long put(char *dst, long at, long cap, const char *src) {
    if (at < 0) return -1;
    size_t_ n = len(src);
    if ((long) n + at >= cap) return -1;
    memcpy(dst + at, src, n + 1);
    return at + (long) n;
}
/* Value of NAME= in envp, or 0. */
static const char *env_get(char *const envp[], const char *name) {
    if (!envp) return 0;
    size_t_ n = len(name);
    for (int i = 0; envp[i]; i++)
        if (starts(envp[i], name) && envp[i][n] == '=') return envp[i] + n + 1;
    return 0;
}

/*
 * For a glibc ELF program, writes the matching Termux glibc loader path into loader and returns 1.
 * Returns 0 for anything else, including when glibc-runner is not installed.
 */
static int glibc_loader_for(const char *path, char *loader) {
    u8 head[4096];
    long fd = sys4(NR_openat, AT_FDCWD, (long) path, O_RDONLY_CLOEXEC, 0);
    if (fd < 0) return 0;
    long got = sys3(NR_read, fd, (long) head, sizeof(head));
    sys3(NR_close, fd, 0, 0);
    if (got < 64 || head[0] != 0x7f || head[1] != 'E' || head[2] != 'L' || head[3] != 'F') return 0;
    int is64 = head[4] == 2, little = head[5] == 1;
    if (!little || (head[4] != 1 && head[4] != 2)) return 0;
#define U16(o) ((unsigned) head[o] | ((unsigned) head[(o) + 1] << 8))
#define U32(o) ((unsigned long) head[o] | ((unsigned long) head[(o) + 1] << 8) | ((unsigned long) head[(o) + 2] << 16) | ((unsigned long) head[(o) + 3] << 24))
    unsigned long phoff = is64 ? U32(32) : U32(28);
    unsigned phentsize = is64 ? U16(54) : U16(42), phnum = is64 ? U16(56) : U16(44);
    if (phentsize < (is64 ? 56u : 32u) || phnum > 64) return 0;
    for (unsigned i = 0; i < phnum; i++) {
        unsigned long ph = phoff + (unsigned long) i * phentsize;
        if (ph + phentsize > (unsigned long) got) return 0;
        if (U32(ph) != 3 /* PT_INTERP */) continue;
        unsigned long off = is64 ? U32(ph + 8) : U32(ph + 4), size = is64 ? U32(ph + 32) : U32(ph + 16);
        if (size < 2 || size > 256 || off + size > (unsigned long) got) return 0;
        const char *interp = (const char *) head + off;
        if (interp[size - 1] != 0) return 0;
        const char *base = interp;
        for (const char *c = interp; *c; c++) if (*c == '/') base = c + 1;
        /* glibc loaders only; bionic programs name /system/bin/linker*. */
        if (!starts(base, "ld-linux")) return 0;
        if (put(loader, put(loader, 0, MAX_PATH, LOADER_DIR), MAX_PATH, base) < 0) return 0;
        return sys4(NR_faccessat, AT_FDCWD, (long) loader, X_OK, 0) == 0;
    }
    return 0;
#undef U16
#undef U32
}

/*
 * Runs a glibc program through the Termux loader. LD_PRELOAD is dropped from the environment because
 * bionic libraries cannot load into a glibc process; its value is kept so glibc processes can hand it back
 * to bionic children. The loader preloads the glibc build of this library when the app provides it.
 */
static long exec_glibc(const char *loader, const char *program, char *const argv[], char *const envp[]) {
    char *args[MAX_ARGS];
    char *env[MAX_ENV];
    char exe_var[MAX_PATH + 32], bionic_var[MAX_PATH + 32];
    int a = 0, e = 0;
    const char *preload = env_get(envp, "TERMUX_GLIBC_PRELOAD");
    const char *bionic = TERMUX_GLIBC_SIDE ? env_get(envp, "TERMUX_BIONIC_LD_PRELOAD") : env_get(envp, "LD_PRELOAD");

    args[a++] = (char *) loader;
    args[a++] = (char *) "--library-path";
    args[a++] = (char *) LOADER_DIR;
    if (preload && sys4(NR_faccessat, AT_FDCWD, (long) preload, 4 /* R_OK */, 0) == 0) {
        args[a++] = (char *) "--preload";
        args[a++] = (char *) preload;
    }
    args[a++] = (char *) "--argv0";
    args[a++] = (char *) (argv && argv[0] ? argv[0] : program);
    args[a++] = (char *) program;
    if (argv && argv[0]) for (int i = 1; argv[i]; i++) {
        if (a >= MAX_ARGS - 1) return -7; /* E2BIG */
        args[a++] = argv[i];
    }
    args[a] = 0;

    if (put(exe_var, put(exe_var, 0, sizeof(exe_var), "TERMUX_GLIBC_EXE="), sizeof(exe_var), program) < 0) return -36;
    if (envp) for (int i = 0; envp[i]; i++) {
        if (starts(envp[i], "LD_PRELOAD=") || starts(envp[i], "TERMUX_GLIBC_EXE=") || starts(envp[i], "TERMUX_BIONIC_LD_PRELOAD=")) continue;
        if (e >= MAX_ENV - 3) return -7;
        env[e++] = envp[i];
    }
    env[e++] = exe_var;
    if (bionic && *bionic) {
        if (put(bionic_var, put(bionic_var, 0, sizeof(bionic_var), "TERMUX_BIONIC_LD_PRELOAD="), sizeof(bionic_var), bionic) < 0) return -36;
        env[e++] = bionic_var;
    }
    env[e] = 0;
    return exec_next(loader, args, env);
}

#if TERMUX_GLIBC_SIDE
/* A bionic child of a glibc process gets termux-exec back; glibc-only variables are not passed on. */
static long exec_bionic(const char *path, char *const argv[], char *const envp[]) {
    const char *bionic = env_get(envp, "TERMUX_BIONIC_LD_PRELOAD");
    if (!bionic || !*bionic) return sys3(NR_execve, (long) path, (long) argv, (long) envp);
    char *env[MAX_ENV];
    char preload_var[MAX_PATH + 16];
    int e = 0;
    if (envp) for (int i = 0; envp[i]; i++) {
        if (starts(envp[i], "LD_PRELOAD=") || starts(envp[i], "TERMUX_GLIBC_EXE=")) continue;
        if (e >= MAX_ENV - 2) return sys3(NR_execve, (long) path, (long) argv, (long) envp);
        env[e++] = envp[i];
    }
    if (put(preload_var, put(preload_var, 0, sizeof(preload_var), "LD_PRELOAD="), sizeof(preload_var), bionic) < 0)
        return sys3(NR_execve, (long) path, (long) argv, (long) envp);
    env[e++] = preload_var;
    env[e] = 0;
    return sys3(NR_execve, (long) path, (long) argv, (long) env);
}
#endif

__attribute__((visibility("default"))) int execve(const char *path, char *const argv[], char *const envp[]) {
    char loader[MAX_PATH];
    if (!path) return (int) fail(exec_next(path, argv, envp));
#if TERMUX_GLIBC_SIDE
    /*
     * A program started through the loader sees the loader as /proc/self/exe. When it re-executes "itself"
     * with ordinary arguments, run the real program again instead of handing those arguments to the loader.
     */
    const char *self = env_get(envp, "TERMUX_GLIBC_EXE");
    if (self && starts(path, LOADER_DIR "ld-linux") &&
        (!argv || !argv[0] || !argv[1] || (argv[1][0] != '-' && argv[1][0] != '/'))) {
        if (glibc_loader_for(self, loader)) return (int) fail(exec_glibc(loader, self, argv, envp));
    }
    if (glibc_loader_for(path, loader)) return (int) fail(exec_glibc(loader, path, argv, envp));
    return (int) fail(exec_bionic(path, argv, envp));
#else
    if (glibc_loader_for(path, loader)) return (int) fail(exec_glibc(loader, path, argv, envp));
    return (int) fail(exec_next(path, argv, envp));
#endif
}

#if TERMUX_GLIBC_SIDE
/*
 * glibc implements the rest of the exec family with an internal execve that a preload cannot replace, so
 * route them here. The bionic side exports only execve: termux-exec handles the family there and reaches
 * this library through its own execve.
 */
extern char **environ __attribute__((weak));

__attribute__((visibility("default"))) int execv(const char *path, char *const argv[]) {
    return execve(path, argv, environ);
}

__attribute__((visibility("default"))) int execvpe(const char *file, char *const argv[], char *const envp[]) {
    if (!file || !*file) return (int) fail(-2 /* ENOENT */);
    for (const char *c = file; *c; c++) if (*c == '/') return execve(file, argv, envp);
    const char *path = env_get(envp, "PATH");
    if (!path) path = "/bin:/usr/bin";
    char candidate[MAX_PATH];
    long denied = 0;
    for (const char *dir = path;;) {
        const char *end = dir;
        while (*end && *end != ':') end++;
        long at = 0;
        if (end == dir) at = put(candidate, 0, MAX_PATH, ".");
        else if (end - dir < MAX_PATH - 2) { memcpy(candidate, dir, (size_t_) (end - dir)); candidate[end - dir] = 0; at = end - dir; }
        else at = -1;
        at = put(candidate, at, MAX_PATH, "/");
        at = put(candidate, at, MAX_PATH, file);
        if (at > 0) {
            char loader[MAX_PATH];
            long result = glibc_loader_for(candidate, loader) ? exec_glibc(loader, candidate, argv, envp) :
                exec_bionic(candidate, argv, envp);
            if (result == -13 /* EACCES */) denied = result;
            else if (result != -2 /* ENOENT */ && result != -20 /* ENOTDIR */) return (int) fail(result);
        }
        if (!*end) break;
        dir = end + 1;
    }
    return (int) fail(denied ? denied : -2);
}

__attribute__((visibility("default"))) int execvp(const char *file, char *const argv[]) {
    return execvpe(file, argv, environ);
}
#endif
