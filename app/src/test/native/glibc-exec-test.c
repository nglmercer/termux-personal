/* Host checks for glibc-exec.c, run by scripts/test-glibc-exec-host.sh against a fake Termux prefix. */
#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

int main(int argc, char **argv) {
    if (argc > 1 && !strcmp(argv[1], "report")) {
        /* Started through the loader: report what the program sees. */
        char self[4096] = {0};
        readlink("/proc/self/exe", self, sizeof(self) - 1);
        printf("exe=%s argv0=%s glibc_exe=%s preload=%s\n", self, argv[0],
               getenv("TERMUX_GLIBC_EXE") ? getenv("TERMUX_GLIBC_EXE") : "-", getenv("LD_PRELOAD") ? getenv("LD_PRELOAD") : "-");
        return 0;
    }
    if (argc > 1 && !strcmp(argv[1], "relaunch")) {
        /* What Bun does: re-execute /proc/self/exe with ordinary arguments. */
        char self[4096] = {0};
        if (readlink("/proc/self/exe", self, sizeof(self) - 1) < 0) return 2;
        char *args[] = {self, "report", NULL};
        execv(self, args);
        perror("relaunch");
        return 3;
    }
    if (argc > 2 && !strcmp(argv[1], "run")) {
        char *args[] = {argv[2], "report", NULL};
        extern char **environ;
        execve(argv[2], args, environ);
        perror("run");
        return 4;
    }
    return 1;
}
